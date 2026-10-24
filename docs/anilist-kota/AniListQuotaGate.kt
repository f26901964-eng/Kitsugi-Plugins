package com.kitsugi.animelist.data.remote

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import java.util.ArrayDeque

/**
 * AniList GraphQL için **tek kota kapısı**.
 *
 * ## Neden gerekli
 * AniList, dokümante ettiği 90 istek/dk yerine uzun süredir **30 istek/dk**
 * (degraded mode, `X-RateLimit-Limit: 30`) uyguluyor ve üstüne **burst limiter**
 * çalıştırıyor. Limit **IP başına** sayılır; mobil operatörlerin CGNAT'ı yüzünden
 * aynı public IP'yi başka kullanıcılar da tüketebilir. 429 aldıktan sonra
 * AniList 1 dakikalık pencere kapatır; bu pencerede yapılan her istek de 429'dur.
 *
 * ## Ne yapar
 * - [WINDOW_MS] kayan pencerede **bütçe** (`limit - rezerv`) kadar isteğe izin verir.
 * - İki istek arasına `ceil(60_000 / bütçe)` ms **minimum aralık** koyar.
 *   (`round` kullanılsaydı 2222 ms × 27 aralık = 59 994 ms olur ve 28 istek aynı
 *   60 sn penceresine sığardı; bu dosyadaki `ceil` bunu matematiksel olarak engeller.)
 * - **Öncelik** uygular: bütçenin %60'ı dolduktan sonra PREFETCH, %40'ından sonra
 *   BACKGROUND gönderilmez. Böylece arka plan eşitleme, kullanıcının bildirim
 *   ekranının önünü kesmez.
 * - `X-RateLimit-Limit` / `X-RateLimit-Remaining` / `X-RateLimit-Reset` başlıklarını
 *   öğrenir; `limit` 90 varsayımıyla başlamaz.
 * - 429'da `Retry-After` (yoksa `X-RateLimit-Reset`, yoksa 60 sn) kadar **kapıyı
 *   kapatır**; bu süre boyunca hiçbir çağıran istek göndermez.
 *
 * ## Ne yapmaz
 * Kuyruğa sonsuza kadar istek koymaz: [Decision.Rejected] döndürdüğünde çağıran
 * katman önbellekten/bayat veriden cevap üretmeli, kullanıcıya geri sayım
 * göstermelidir. "Sessizce bekle" davranışı ANR ve 45 sn'lik iskelet ekranları
 * üretir; bu kapı bilinçli olarak **fail-fast + geri sayım** seçer.
 *
 * ## Doğrulama
 * `docs/anilist-kota/anilist_kota_sim.py` bu dosyanın algoritma portudur ve
 * 5 garantiyi (pencere, aralık, Retry-After, öncelik rezervi, başlık öğrenme)
 * test eder. Sabitleri değiştirirseniz simülasyonu da çalıştırın.
 *
 * Tüm erişim `synchronized` altındadır; bekleme **kilit dışında** yapılır, bu
 * yüzden çağrılar birbirini kilitlemez.
 */
object AniListQuotaGate {

    /** İsteğin kullanıcı açısından aciliyeti. Sıra önemlidir (rank büyük = daha az önemli). */
    enum class Priority(val rank: Int) {
        /** Kullanıcının beklediği okuma/yazma: bildirim, liste güncelleme, giriş. */
        CRITICAL(0),

        /** Ekranda görünen içerik: detay, arama sonucu, açık sekme. */
        INTERACTIVE(1),

        /** Görünmeyen raflar, sonraki sayfa, ısıtma. */
        PREFETCH(2),

        /** Toplu eşitleme, içe aktarma, periyodik işler (WorkManager). */
        BACKGROUND(3),
    }

    /** Kapının kararı. */
    sealed interface Decision {
        /** İstek gönderilebilir (bekleme kapı içinde yapıldı). */
        data object Granted : Decision

        /** İstek **gönderilmedi**. Çağıran önbellek/geri sayım göstermeli. */
        data class Rejected(val retryInMs: Long, val reason: Reason) : Decision
    }

    enum class Reason {
        /** Öncelik rezervi: bütçe arka plan işine ayrılmadı. */
        QUOTA_RESERVED,

        /** 429 penceresi açık; [Decision.Rejected.retryInMs] kadar beklemek gerek. */
        COOLDOWN,

        /** Sıra çok uzun; bu istek için beklemek kullanıcıyı kilitleyecekti. */
        WAIT_TOO_LONG,
    }

    /** Teşhis paneli / log için anlık görüntü. */
    data class Snapshot(
        val limit: Int,
        val budget: Int,
        val usedInWindow: Int,
        val minGapMs: Long,
        val cooldownRemainingMs: Long,
        val nextFreeSlotInMs: Long,
        val lastRemaining: Int?,
    )

    private const val WINDOW_MS = 60_000L
    private const val DEFAULT_LIMIT = 30      // doküman 90 diyor; fiili limit 30
    private const val MIN_LIMIT = 5
    private const val MAX_LIMIT = 1_000
    private const val COOLDOWN_MIN_MS = 2_000L
    private const val COOLDOWN_MAX_MS = 120_000L
    private const val DEFAULT_PAUSE_MS = 60_000L

    private val lock = Any()

    /** Son 60 sn içinde **planlanmış** istek başlangıçları (ms, epoch). */
    private val starts = ArrayDeque<Long>()

    private var limit: Int = DEFAULT_LIMIT
    private var nextSlotAt: Long = 0L
    private var blockedUntil: Long = 0L
    private var softBlockedUntil: Long = 0L
    private var lastRemaining: Int? = null

    // ── türetilmiş değerler (kilit altında okunmalı) ─────────────────────────

    private fun budgetLocked(): Int {
        val reserve = maxOf(2, limit / 10)          // %10 emniyet payı
        return maxOf(2, limit - reserve)
    }

    /**
     * Minimum istek aralığı. **Yukarı** yuvarlanır: `60_000 / 27 = 2222.2` iken
     * 2222 ms kullanmak 28 isteği tek pencereye sokar (0, 2222, … 59 994).
     */
    private fun minGapLocked(): Long {
        val budget = budgetLocked()
        return (WINDOW_MS + budget - 1) / budget
    }

    private fun pruneLocked(now: Long) {
        while (starts.isNotEmpty() && starts.first() <= now - WINDOW_MS) {
            starts.removeFirst()
        }
    }

    /**
     * Bu istek için en erken uygun zamanı hesaplar ve **rezerve eder**.
     * Temizlik, `now`'a göre değil aday `slot`'un kendi penceresine göre yapılır;
     * aksi hâlde pencere sınırında 28. istek sızabilir.
     */
    private fun planLocked(now: Long): Long {
        var slot = maxOf(now, nextSlotAt, blockedUntil, softBlockedUntil)
        val budget = budgetLocked()
        for (i in 0 until 4) {
            while (starts.isNotEmpty() && starts.first() <= slot - WINDOW_MS) {
                starts.removeFirst()
            }
            if (starts.size < budget) return slot
            slot = starts.first() + WINDOW_MS
        }
        return slot
    }

    private fun commitLocked(slot: Long) {
        starts.addLast(slot)
        nextSlotAt = slot + minGapLocked()
    }

    // ── genel API ───────────────────────────────────────────────────────────

    /**
     * Kapıdan izin ister. İzin verilirse dönüş **ancak sıra geldiğinde** olur
     * (bekleme bu fonksiyonun içinde, kilit dışında yapılır).
     *
     * @param maxWaitMs bu süreden fazla beklemek gerekiyorsa istek hiç
     *   gönderilmez — çağıran önbellekten cevap vermeli / geri sayım göstermeli.
     */
    suspend fun acquire(
        priority: Priority,
        maxWaitMs: Long = defaultMaxWaitMs(priority),
    ): Decision {
        val now = System.currentTimeMillis()
        var waitMs = 0L
        var rejection: Decision.Rejected? = null

        synchronized(lock) {
            pruneLocked(now)
            val budget = budgetLocked()

            // 1) Öncelik rezervi: düşük öncelikli iş, kullanıcının bütçesini yemesin.
            val reserved = when (priority) {
                Priority.PREFETCH -> (budget * 60) / 100
                Priority.BACKGROUND -> (budget * 40) / 100
                else -> Int.MAX_VALUE
            }
            if (starts.size >= reserved) {
                rejection = Decision.Rejected(
                    retryInMs = DEFAULT_PAUSE_MS,
                    reason = Reason.QUOTA_RESERVED,
                )
                return@synchronized
            }

            // 2) Sıra hesapla + rezerve et.
            val slot = planLocked(now)
            waitMs = slot - now
            if (waitMs > maxWaitMs) {
                rejection = Decision.Rejected(
                    retryInMs = waitMs,
                    reason = if (blockedUntil > now) Reason.COOLDOWN else Reason.WAIT_TOO_LONG,
                )
                return@synchronized
            }
            commitLocked(slot)
        }

        rejection?.let { return it }
        if (waitMs > 0L) delay(waitMs)
        return Decision.Granted
    }

    /**
     * Bloklayan (suspend olmayan) eski çağrı yerleri için köprü.
     *
     * `AniListSyncManager`, `AniListImportManager`, profil ViewModel'leri gibi
     * doğrudan `graphql.anilist.co`'ya POST atan yollar bu kapıyı atladığı için
     * bütçeyi görünmez şekilde tüketiyordu. Bu köprü IO thread'inde kullanılmalı.
     */
    fun acquireBlocking(
        priority: Priority,
        maxWaitMs: Long = 15_000L,
    ): Decision = runBlocking { acquire(priority, maxWaitMs) }

    /**
     * HTTP yanıt başlıklarını öğrenir. Her yanıtta (başarılı/başarısız) çağrılmalı.
     *
     * @param resetEpochSeconds `X-RateLimit-Reset` (Unix saniye)
     */
    fun onHttpResponse(
        limitHeader: Int?,
        remainingHeader: Int?,
        resetEpochSeconds: Long? = null,
    ) {
        synchronized(lock) {
            if (limitHeader != null && limitHeader in MIN_LIMIT..MAX_LIMIT) {
                limit = limitHeader
            }
            lastRemaining = remainingHeader
            // Kota bitti/bitiyor: pencere sıfırlanana kadar düşük öncelikli iş durur.
            if (remainingHeader != null && remainingHeader <= 1 && resetEpochSeconds != null) {
                val resetAt = resetEpochSeconds * 1_000L
                if (resetAt > System.currentTimeMillis()) {
                    softBlockedUntil = maxOf(softBlockedUntil, resetAt)
                }
            }
        }
    }

    /**
     * 429 alındı. `Retry-After` (ms) varsa ona, yoksa 60 sn'ye kadar kapıyı kapatır.
     * Bu süre içinde hiçbir öncelik istek göndermez; retry yapmak 429 üretmekten
     * başka bir şey yapmaz.
     */
    fun onRateLimited(retryAfterMs: Long?) {
        val pause = (retryAfterMs ?: DEFAULT_PAUSE_MS)
            .coerceIn(COOLDOWN_MIN_MS, COOLDOWN_MAX_MS)
        synchronized(lock) {
            blockedUntil = maxOf(blockedUntil, System.currentTimeMillis() + pause)
        }
    }

    /** Servis tamamen kapalıysa (403 "temporarily disabled") kapıyı kapatır. */
    fun onServiceDown(pauseMs: Long = 45_000L) {
        synchronized(lock) {
            blockedUntil = maxOf(blockedUntil, System.currentTimeMillis() + pauseMs)
        }
    }

    /** Şu an kaç ms sonra istek çıkabilir? UI geri sayımı için. */
    fun waitEstimateMs(priority: Priority = Priority.INTERACTIVE): Long {
        val now = System.currentTimeMillis()
        return synchronized(lock) {
            pruneLocked(now)
            val budget = budgetLocked()
            val reserved = when (priority) {
                Priority.PREFETCH -> (budget * 60) / 100
                Priority.BACKGROUND -> (budget * 40) / 100
                else -> Int.MAX_VALUE
            }
            if (starts.size >= reserved) return@synchronized DEFAULT_PAUSE_MS
            maxOf(0L, planLocked(now) - now)
        }
    }

    /** 429/servis kesintisi ne zaman bitiyor? (0 = açık) */
    fun cooldownRemainingMs(): Long {
        val now = System.currentTimeMillis()
        return synchronized(lock) { maxOf(0L, blockedUntil - now) }
    }

    fun snapshot(): Snapshot {
        val now = System.currentTimeMillis()
        return synchronized(lock) {
            pruneLocked(now)
            Snapshot(
                limit = limit,
                budget = budgetLocked(),
                usedInWindow = starts.size,
                minGapMs = minGapLocked(),
                cooldownRemainingMs = maxOf(0L, blockedUntil - now),
                nextFreeSlotInMs = maxOf(0L, planLocked(now) - now),
                lastRemaining = lastRemaining,
            )
        }
    }

    /** Test/teşhis için tüm durumu sıfırlar. Üretimde çağrılmamalı. */
    fun resetForTest() {
        synchronized(lock) {
            starts.clear()
            limit = DEFAULT_LIMIT
            nextSlotAt = 0L
            blockedUntil = 0L
            softBlockedUntil = 0L
            lastRemaining = null
        }
    }

    private fun defaultMaxWaitMs(priority: Priority): Long = when (priority) {
        Priority.CRITICAL -> 25_000L     // kullanıcı ekranda bekliyor
        Priority.INTERACTIVE -> 12_000L  // iskelet gösterilebilir
        Priority.PREFETCH -> 3_000L
        Priority.BACKGROUND -> 2_000L
    }
}
