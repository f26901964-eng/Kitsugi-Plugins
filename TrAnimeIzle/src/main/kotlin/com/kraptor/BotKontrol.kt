package com.kraptor

import android.app.AlertDialog
import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.ValueCallback
import android.webkit.WebView
import android.widget.LinearLayout
import android.widget.TextView
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlin.coroutines.resume

/**
 * Bot kontrolü (DEX'teki BotKontrolKt'nin kaynak yeniden yazımı + v17 sertleştirme).
 *
 * Yasal yol (A1): cihazda bir WebView açılır, site normal bir oturumla yüklenir.
 * Challenge sayfası göründüğünde 5 parçalık bulmaca HTTP üzerinden çözülür
 * (parça hash'leri DOM'dan, parça resim boyutları doğrudan istekle alınır;
 * boyutu diğerlerinden farklı olan parça cevap olarak gönderilir).
 * Harici captcha çözme servisi, bot ordusu, UA/TLS oyunu KULLANILMAZ.
 *
 * v17 DEĞİŞİKLİKLERİ (kanıt: cihaz ekran görüntüsü + site arşivi 22.09.2026):
 *  - Site Cloudflare Error 1005 (ASN ban) arkasında: veri merkezi IP'leri kapalı,
 *    cihaz (residential) challenge'sız ya da IconCaptcha (v2.5.0) ile geçer.
 *  - Başarı ARTIK yalnız `AitrWeb.Session` değil: sayfa challenge olmadan
 *    tam yüklendiyse (readyState=complete + gövde eşiği üstünde) siteye ait
 *    TÜM çerez seti (cf_clearance vs. dahil) oturum olarak kabul edilir.
 *  - Uygulamanın kendi WebView'i siteyi zaten açtıysa (KNOWN_WEBVIEW_PLUGINS
 *    kancası) CookieManager seti ÖNCE kontrol edilir — diyalog hiç açılmaz.
 *  - Diyaloğa durum satırı eklenir ("oturum açılıyor", "doğrulama çözülüyor") —
 *    kullanıcı artık ne olduğunu görebilir (v16'da 42 sn "anlamsız sayfa").
 *  - "Çerez gerekmedi" onayı kalıcı tutulur (bot_session_ok) → sonraki
 *    çalıştırmalarda diyalog açılmaz; site davranışı değişirse ilk bot
 *    cevabında otomatik yenileme devreye girer.
 *  - Captcha akışı, arşivdeki icon-captcha.min.js (IconCaptcha v2.5.0) ile
 *    birebir: [icon-hash] ×5, GET ?cid=0&hash=, POST {cID,pC,rT:2}.
 */
object BotKontrolKt {

    const val TAG = "kraptor_TrAnime"

    const val UA_FIREFOX =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:152.0) Gecko/20100101 Firefox/152.0"

    private const val BOT_TIMEOUT_MS = 45_000L
    private const val HTTP_TIMEOUT_MS = 7_000
    private const val PREFS_NAME = "bot_cookies"
    private const val SESSION_COOKIE_NAME = "AitrWeb.Session"
    private const val MONITOR_MAX_ITERATIONS = 45
    private const val MONITOR_POLL_MS = 1_000L
    private const val PAGE_LOADED_MIN_BODY = 1_500

    @Volatile
    private var activeDialog: AlertDialog? = null

    private val webViewMutex = Mutex()

    /** Tek kullanımlık devam (continuation) kilidi. */
    private class ResumeGuard {
        @Volatile
        var active = true
    }

    // ------------------------------------------------------------------
    // Çerez seti (SharedPreferences: "bot_cookies", anahtar = host)
    // ------------------------------------------------------------------

    /**
     * CookieManager'dan site host'u için TOPLAM çerez setini döndürür
     * (hem `host` hem `www.host` için; "a=b; c=d" biçimi).
     * Uygulamanın kendi WebView'inin yüklediği çerezleri de yakalar.
     */
    fun collectJar(anySiteUrl: String): String {
        val base = hostOf(anySiteUrl).removePrefix("www.")
        val cm = CookieManager.getInstance()
        val seen = LinkedHashSet<String>()
        for (u in listOf("https://$base/", "https://www.$base/")) {
            try {
                cm.getCookie(u)?.split(";")?.forEach { part ->
                    part.trim().takeIf { it.isNotEmpty() }?.let(seen::add)
                }
            } catch (_: Exception) {
            }
        }
        return seen.joinToString("; ")
    }

    /** Sette bir oturum çerezi var mı? (AitrWeb.Session veya Cloudflare) */
    fun jarHasSession(jar: String): Boolean =
        jar.contains(SESSION_COOKIE_NAME, ignoreCase = true) ||
            jar.contains("cf_clearance", ignoreCase = true)

    /** Önceki çalıştırmada kaydedilen oturum seti (yoksa ""). */
    fun getSavedCookie(context: Context, url: String): String {
        val host = hostOf(url)
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(host, "") ?: ""
    }

    /** Oturum setini kalıcı olarak kaydeder (boş set yazılmaz). */
    fun saveJar(context: Context, url: String, jar: String) {
        if (jar.isBlank()) return
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putString(hostOf(url), jar).apply()
        CookieManager.getInstance().flush()
    }

    /** "Challenge yok, çerez gerekmedi" onayı (kalıcı). */
    fun markSessionOk(context: Context, url: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putString(hostOf(url) + ":ok", "1").apply()
    }

    fun isSessionOk(context: Context, url: String): Boolean =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(hostOf(url) + ":ok", "") == "1"

    fun clearCookie(context: Context, url: String) {
        val host = hostOf(url)
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().remove(host).remove(host + ":ok").apply()
        CookieManager.getInstance().flush()
    }

    /**
     * WebView'in cookie kutusunu temizler ve [cookieString] içindeki
     * `k=v` parçalarını site host'una yeniden yazar (session aktarımı).
     */
    fun resetCookieManagerTo(cookieString: String, siteUrl: String) {
        val cm = CookieManager.getInstance()
        // compileSdk 35 stub'larında artık-argsız removeAllCookies() yok;
        // API 26 altında kaldırma atlanır (aynı host'a setCookie ile ezilir).
        if (Build.VERSION.SDK_INT >= 26) {
            cm.removeAllCookies(null)
        }
        cm.flush()
        if (cookieString.isBlank()) return
        val host = hostOf(siteUrl)
        val domain = "www." + host.removePrefix("www.")
        val rebuilt = cookieString.split(";")
            .map { it.trim() }
            .filter { it.isNotEmpty() && it.contains("=") }
            .joinToString("; ") { "$it; Path=/; Domain=$domain" }
        cm.setCookie(siteUrl, rebuilt)
        cm.flush()
    }

    /**
     * Çerez dizgesi içinden `.AitrWeb.Session=...` parçasını döndürür
     * (bulunamazsa boş string).
     */
    fun extractSessionCookie(cookieHeader: String?): String {
        if (cookieHeader.isNullOrBlank()) return ""
        return cookieHeader.split(";")
            .map { it.trim() }
            .firstOrNull { it.startsWith("$SESSION_COOKIE_NAME=") }
            ?: ""
    }

    /**
     * Challenge URL'sinden hash parametresini çıkarır
     * (ör. `.../api/Captcha/?cid=0&hash=abc123` → `abc123`).
     */
    fun extractHashFromUrl(url: String): String {
        return try {
            val u = URL(url)
            val candidates = (u.path?.split("&") ?: emptyList()) +
                (u.query ?: "").split("&")
            candidates.firstOrNull { it.startsWith("hash=") }
                ?.substringAfter("hash=")
                ?.takeIf { it.isNotBlank() }
                ?: ""
        } catch (_: Exception) {
            ""
        }
    }

    private fun hostOf(url: String): String =
        url.substringAfter("://", url).substringBefore("/")

    private fun hostBaseUrl(anySiteUrl: String): String {
        val host = hostOf(anySiteUrl)
        return "https://" + (if (host.startsWith("www.")) host else "www.$host")
    }

    // ------------------------------------------------------------------
    // Ana giriş: botKontrol
    // ------------------------------------------------------------------

    /**
     * [url] sayfasının bot koruması için oturum çerez seti döndürür.
     *
     *  forceRefresh=false ve geçerli bir oturum varsa WebView AÇILMAZ:
     *    1) CookieManager setinde oturum çerezi (uygulamanın WebView'i),
     *    2) kayıtlı çerez seti,
     *    3) "çerez gerekmedi" onayı ("" döner).
     *  Aksi halde (veya forceRefresh=true) WebView diyalogu ile oturum açılır.
     *  Toplam 45 sn'yi aşarsa kayıtlı çereze düşülür.
     */
    suspend fun botKontrol(
        context: Context,
        url: String,
        forceRefresh: Boolean = false
    ): String {
        return try {
            withTimeout(BOT_TIMEOUT_MS) {
                webViewMutex.withLock {
                    if (!forceRefresh) {
                        val jar = collectJar(url)
                        if (jarHasSession(jar)) {
                            Log.d(TAG, "CookieManager seti geçerli — WebView açılmadı")
                            saveJar(context, url, jar)
                            return@withLock jar
                        }
                        val cached = getSavedCookie(context, url)
                        if (cached.isNotEmpty()) {
                            resetCookieManagerTo(cached, url)
                            Log.d(TAG, "Kayıtlı oturum çerezi kullanılıyor — WebView açılmadı")
                            return@withLock cached
                        }
                        if (isSessionOk(context, url)) {
                            Log.d(TAG, "Önceden onaylı: challenge yok, çerez gerekmedi — WebView açılmadı")
                            return@withLock ""
                        }
                    }
                    runCaptchaSolve(context, url, forceRefresh)
                }
            }
        } catch (e: TimeoutCancellationException) {
            Log.e(TAG, ">>> Mutex TIMEOUT (45s) — cached cookie ile devam <<<")
            getSavedCookie(context, url)
        }
    }

    // ------------------------------------------------------------------
    // WebView + challenge çözme (runCaptchaSolve)
    // ------------------------------------------------------------------

    suspend fun runCaptchaSolve(
        context: Context,
        url: String,
        forceRefresh: Boolean
    ): String = suspendCancellableCoroutine { cont ->
        val guard = ResumeGuard()
        val host = hostOf(url)
        val prefs: SharedPreferences =
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        cont.invokeOnCancellation {
            activeDialog?.dismiss()
            activeDialog = null
        }

        Handler(Looper.getMainLooper()).post {
            try {
                val container = LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    val pad = (16 * context.resources.displayMetrics.density).toInt()
                    setPadding(pad, pad, pad, pad)
                    setBackgroundColor(0xFF121212.toInt())
                    setLayoutParams(
                        LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            LinearLayout.LayoutParams.MATCH_PARENT
                        )
                    )
                }

                // v17: durum satırı — "anlamsız sayfa" yerine ne olduğu görünsün.
                val statusView = TextView(context).apply {
                    text = "TrAnimeIzle oturumu açılıyor…"
                    setTextColor(0xFFFFFFFF.toInt())
                    textSize = 14f
                    setPadding(0, 0, 0, (8 * context.resources.displayMetrics.density).toInt())
                }
                container.addView(statusView)

                val webView = WebView(context).apply {
                    layoutParams = LinearLayout.LayoutParams(-1, -1)
                    settings.apply {
                        javaScriptEnabled = true
                        domStorageEnabled = true
                        useWideViewPort = true
                        loadWithOverviewMode = true
                    }
                    setFocusable(true)
                    setFocusableInTouchMode(true)
                    setBackgroundColor(0xFFFFFFFF.toInt())
                    setInitialScale(100)
                }
                container.addView(webView)

                if (!forceRefresh) {
                    val cached = getSavedCookie(context, url)
                    if (cached.isNotEmpty()) resetCookieManagerTo(cached, url)
                }

                Log.d(TAG, "İlk URL yükleniyor: $url")
                webView.loadUrl(url)

                val dialog = AlertDialog.Builder(context)
                    .setView(container)
                    .setCancelable(false)
                    .setNegativeButton("İptal") { _, _ ->
                        Log.w(TAG, "Kullanıcı oturumu iptal etti")
                        safeResume(guard, cont, "")
                    }
                    .create()
                activeDialog = dialog
                dialog.window?.apply {
                    setBackgroundDrawableResource(android.R.color.transparent)
                    setDimAmount(0.75f)
                    setLayout(
                        WindowManager.LayoutParams.MATCH_PARENT,
                        WindowManager.LayoutParams.MATCH_PARENT
                    )
                    addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                }
                dialog.setOnDismissListener {
                    safeResume(guard, cont, "")
                }
                dialog.show()

                // Challenge izleme döngüsü (ayrı coroutine, UI thread).
                MainScope().launch {
                    monitorChallenge(webView, statusView, guard, cont, url, host, prefs)
                }
            } catch (e: Exception) {
                Log.e(TAG, "WebView başlatılamadı: ${e.message}")
                safeResume(guard, cont, "")
            }
        }
    }

    private fun safeResume(
        guard: ResumeGuard,
        cont: CancellableContinuation<String>,
        value: String
    ) {
        if (!guard.active) return
        guard.active = false
        cont.resume(value)
    }

    /**
     * Sayfa tam yüklendi mi?
     * @return -2 = hâlâ Cloudflare challenge sayfası, -1 = belirsiz/yüklenmedi,
     *         >=0 = gövde uzunluğu (readyState=complete).
     * v18: CF managed challenge sayfaları "yüklendi" sayılmamalı — WebView
     * (gerçek Chromium) challenge'ı normalde kendiliğinden çözer, o beklenir.
     */
    private fun pageLoadedBodyLength(webView: WebView): Int {
        val ref = java.util.concurrent.atomic.AtomicInteger(-1)
        val latch = java.util.concurrent.CountDownLatch(1)
        try {
            webView.evaluateJavascript(
                "(function(){try{var h=document.body?document.body.innerHTML:'';" +
                    "if(/just a moment|checking your browser|cf-challenge/i.test(" +
                    "(document.title||'')+h))return -2;" +
                    "return (document.readyState==='complete')?h.length:-1" +
                    "}catch(e){return -1}})()",
                ValueCallback { raw ->
                    ref.set(raw?.trim()?.toIntOrNull() ?: -1)
                    latch.countDown()
                }
            )
        } catch (e: Exception) {
            return -1
        }
        latch.await(2, java.util.concurrent.TimeUnit.SECONDS)
        return ref.get()
    }

    /**
     * Oturum izleme döngüsü (v17 başarı koşulları, öncelik sırasıyla):
     *   A) `AitrWeb.Session` (veya cf_clearance) çerezi sette göründü → oturum.
     *   B) Sayfa challenge'sız tam yüklendi (gövde eşiği üstünde) → site bu
     *      IP'de challenge istemiyor; set (boş dahil) oturum kabul edilir.
     *   C) Sayfa /api/Captcha üzerinde → 5 parçalık bulmaca çözülür, sonra
     *      A/B tekrar denenir.
     */
    private suspend fun monitorChallenge(
        webView: WebView,
        statusView: TextView,
        guard: ResumeGuard,
        cont: CancellableContinuation<String>,
        url: String,
        host: String,
        prefs: SharedPreferences
    ) {
        val sizeCache = HashMap<String, Int>()
        var iterations = 0
        var warnedSmallBody = false

        while (iterations++ < MONITOR_MAX_ITERATIONS && guard.active) {
            delay(MONITOR_POLL_MS)
            if (activeDialog == null || !guard.active) break

            // Bu döngü MainScope (UI thread) üzerinde çalışır; WebView okumaları
            // doğrudan yapılabilir.
            val pageUrl = try {
                webView.url
            } catch (_: Exception) {
                null
            }
            if (pageUrl == null) continue
            val jar = collectJar(url)
            val inChallenge = pageUrl.contains("/api/Captcha", ignoreCase = true)

            if (inChallenge) {
                statusView.text = "Doğrulama sayfası çözümleniyor…"
                val jarCookie = CookieManager.getInstance().getCookie(url) ?: ""
                val hashes = withContext(Dispatchers.Main) { getIconHashes(webView) }
                when {
                    hashes == null ->
                        Log.d(TAG, "DOM'da henüz 5 resim yok, bekleniyor... (JS sonucu yok)")
                    hashes.size != 5 ->
                        Log.d(
                            TAG,
                            "DOM'da henüz 5 resim yok, bekleniyor... (Bulunan: ${hashes.size})"
                        )
                    else -> {
                        Log.d(
                            TAG,
                            ">>> CAPTCHA TESPİT EDİLDİ. EKRANDAKİ RESİMLER ANALİZ EDİLİYOR <<<"
                        )
                        Log.d(TAG, "Ekrandaki 5 Hash alındı. Boyutlar bekleniyor...")

                        val started = System.currentTimeMillis()
                        val sizes = withContext(Dispatchers.IO) {
                            coroutineScope {
                                hashes.map { hash ->
                                    async {
                                        sizeCache.getOrPut(hash) {
                                            getImageSize(hash, jarCookie, url)
                                        }
                                    }
                                }.awaitAll()
                            }
                        }
                        val waited = System.currentTimeMillis() - started
                        val sizeReport = hashes.zip(sizes).joinToString(" | ") {
                            "Tile ${it.first.take(8)}… → ${it.second} byte"
                        }
                        Log.d(TAG, "Boyutlar: $sizeReport (waited ${waited}ms)")

                        val counts = sizes.groupingBy { it }.eachCount()
                        val outlier = sizes.zip(hashes)
                            .firstOrNull { (size, _) -> size > 0 && counts[size] == 1 }?.second

                        if (outlier == null) {
                            Log.e(TAG, "Farklı boyut bulunamadı! Boyutlar: $sizeReport")
                            continue
                        }
                        Log.d(TAG, "FARKLI OLAN TESPİT EDİLDİ: $outlier")

                        delay(500)
                        Log.d(TAG, ">>> HTTP POST ile cevap gönderiliyor <<<")
                        val ok = submitCaptchaAnswer(
                            hostBaseUrl(url), outlier, jarCookie, url
                        )
                        if (!ok) {
                            Log.e(TAG, "Cevap gönderilemedi")
                            continue
                        }
                        Log.d(TAG, "Cevap gönderildi (rT=2), 302 bekleniyor...")
                        delay(MONITOR_POLL_MS)
                        val solved = followCaptchaChallenge(
                            hostBaseUrl(url), jarCookie, url
                        )
                        if (solved) {
                            Log.d(TAG, ">>> CAPTCHA ÇÖZÜLDÜ (HTTP 302) <<<")
                        } else {
                            Log.e(TAG, "CaptchaChallenge 302 dönmedi")
                        }
                        // WebView'in challenge sayfasından içeri geçmesi için bekle.
                        delay(2_000)
                    }
                }
                continue
            }

            // A) Oturum çerezi sette göründü.
            if (jarHasSession(jar)) {
                Log.d(TAG, ">>> OTURUM ÇEREZİ ALINDI (set ${jar.length} karakter) <<<")
                finishSession(statusView, guard, cont, url, host, prefs, jar, noChallenge = false)
                return
            }

            // B) Challenge'sız tam yüklendi → site bu istemciden challenge istemiyor.
            val bodyLen = pageLoadedBodyLength(webView)
            if (bodyLen == -2) {
                // v18: Cloudflare managed challenge — WebView'in kendisi
                // çözer; sayfa içeriğe geçene kadar beklenir.
                statusView.text = "Doğrulama sayfası yükleniyor (Cloudflare)…"
                continue
            }
            if (bodyLen >= PAGE_LOADED_MIN_BODY) {
                Log.d(
                    TAG,
                    ">>> SAYFA CHALLENGE'SIZ YÜKLENDİ (gövde ${bodyLen} karakter, set ${jar.length} karakter) <<<"
                )
                finishSession(statusView, guard, cont, url, host, prefs, jar, noChallenge = true)
                return
            }

            if (!warnedSmallBody && iterations >= 6) {
                warnedSmallBody = true
                Log.w(TAG, "Sayfa hâlâ küçük (body=$bodyLen) — beklenmeye devam")
            }
            if (iterations == 3) {
                statusView.text = "Sayfa yükleniyor…"
            }
        }

        Log.e(TAG, ">>> Bot kontrolü zaman aşımında (çerez alınamadı) <<<")
        withContext(Dispatchers.Main) {
            activeDialog?.dismiss()
            activeDialog = null
        }
        safeResume(guard, cont, "")
    }

    /** Oturum tamamlandı: seti kaydet + diyalogu kapat + continuation'ı sürdür. */
    private suspend fun finishSession(
        statusView: TextView,
        guard: ResumeGuard,
        cont: CancellableContinuation<String>,
        url: String,
        host: String,
        prefs: SharedPreferences,
        jar: String,
        noChallenge: Boolean
    ) {
        if (jar.isNotEmpty()) {
            prefs.edit().putString(host, jar).apply()
        }
        if (noChallenge && !jarHasSession(jar)) {
            // Challenge'sız yol: site bu istemciden çerez istemiyor → onayla,
            // bir sonraki çalıştırmada diyalog açılmasın.
            prefs.edit().putString(host + ":ok", "1").apply()
        }
        statusView.text = "Oturum tamamlandı"
        Log.d(TAG, "Diyalog kapatılıyor")
        withContext(Dispatchers.Main) {
            activeDialog?.dismiss()
            activeDialog = null
        }
        safeResume(guard, cont, jar)
    }

    /**
     * DOM'daki `[icon-hash]` özniteliklerini döndürür.
     * null = JS çalıştırılamadı / zaman aşımı.
     */
    private fun getIconHashes(webView: WebView): List<String>? {
        val result = java.util.concurrent.atomic.AtomicReference<List<String>?>()
        val latch = java.util.concurrent.CountDownLatch(1)
        try {
            webView.evaluateJavascript(
                "(function() { return JSON.stringify(" +
                    "Array.from(document.querySelectorAll('[icon-hash]'))" +
                    ".map(el => el.getAttribute('icon-hash'))); })()",
                ValueCallback { raw ->
                    try {
                        val json = raw?.removeSurrounding("\"") ?: "null"
                        if (json == "null") {
                            result.set(null)
                        } else {
                            val arr = JSONArray(json)
                            val list = (0 until arr.length()).map { arr.getString(it) }
                            result.set(list)
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "DOM Hash parse hatası: ${e.message}")
                        result.set(null)
                    }
                    latch.countDown()
                }
            )
        } catch (e: Exception) {
            return null
        }
        val finished = latch.await(3, java.util.concurrent.TimeUnit.SECONDS)
        return if (finished) result.get() else null
    }

    // ------------------------------------------------------------------
    // HTTP yardımcıları (doğrudan HttpURLConnection — cookie kutusuna dokunmaz)
    // ------------------------------------------------------------------

    /** Parça resminin bayt boyutunu döndürür (hata → -1). */
    private suspend fun getImageSize(hash: String, cookie: String, referer: String): Int =
        withContext(Dispatchers.IO) {
            try {
                val imgUrl = "${hostBaseUrl(referer)}/api/Captcha/?cid=0&hash=$hash"
                val conn = (URL(imgUrl).openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = HTTP_TIMEOUT_MS
                    readTimeout = HTTP_TIMEOUT_MS
                    setRequestProperty("Cookie", cookie)
                    setRequestProperty("Referer", referer)
                    setRequestProperty("User-Agent", UA_FIREFOX)
                    setRequestProperty(
                        "Accept",
                        "image/avif,image/webp,image/png,image/svg+xml,image/*;q=0.8,*/*;q=0.5"
                    )
                }
                val code = conn.responseCode
                if (code != 200) {
                    Log.e(TAG, "getImageSize($hash) HTTP $code")
                    conn.disconnect()
                    return@withContext -1
                }
                val size = if (conn.contentLength > 0) conn.contentLength
                else conn.inputStream.use { it.readBytes().size }
                conn.disconnect()
                size
            } catch (e: Exception) {
                Log.e(TAG, "getImageSize($hash) hata: ${e.message}")
                -1
            }
        }

    /**
     * Cevabı gönderir: POST `<base>/api/Captcha/` gövde `cID=0&pC=<hash>&rT=2`.
     * Başarı = HTTP 200. (IconCaptcha v2.5.0: {cID, pC, rT:2})
     */
    private suspend fun submitCaptchaAnswer(
        base: String,
        hash: String,
        cookie: String,
        referer: String
    ): Boolean = withContext(Dispatchers.IO) {
        var conn: HttpURLConnection? = null
        try {
            conn = (URL("$base/api/Captcha/").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                connectTimeout = HTTP_TIMEOUT_MS
                readTimeout = HTTP_TIMEOUT_MS
                setRequestProperty("Cookie", cookie)
                setRequestProperty("Referer", referer)
                setRequestProperty("User-Agent", UA_FIREFOX)
                setRequestProperty(
                    "Content-Type", "application/x-www-form-urlencoded; charset=UTF-8"
                )
                setRequestProperty("X-Requested-With", "XMLHttpRequest")
                setRequestProperty("Origin", base)
                setRequestProperty("Accept", "*/*")
            }
            val body = "cID=0&pC=${URLEncoder.encode(hash, "UTF-8")}&rT=2"
            conn!!.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            Log.d(TAG, "submitCaptchaAnswer HTTP $code")
            code == 200
        } catch (e: Exception) {
            Log.e(TAG, "submitCaptchaAnswer hata: ${e.message}")
            false
        } finally {
            conn?.disconnect()
        }
    }

    /**
     * Challenge sonucu doğrulaması: GET `<base>/api/CaptchaChallenge/`
     * (yönlendirmeler KAPALI) → 302 = çözüldü.
     */
    private suspend fun followCaptchaChallenge(
        base: String,
        cookie: String,
        referer: String
    ): Boolean = withContext(Dispatchers.IO) {
        var conn: HttpURLConnection? = null
        try {
            val challengeUrl = "$base/api/CaptchaChallenge/"
            conn = (URL(challengeUrl).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                instanceFollowRedirects = false
                connectTimeout = HTTP_TIMEOUT_MS
                readTimeout = HTTP_TIMEOUT_MS
                setRequestProperty("Cookie", cookie)
                setRequestProperty("Referer", referer)
                setRequestProperty("User-Agent", UA_FIREFOX)
                setRequestProperty(
                    "Accept",
                    "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"
                )
                setRequestProperty("Upgrade-Insecure-Requests", "1")
            }
            val code = conn.responseCode
            Log.d(TAG, "followCaptchaChallenge HTTP $code")
            code == 302
        } catch (e: Exception) {
            Log.e(TAG, "followCaptchaChallenge hata: ${e.message}")
            false
        } finally {
            conn?.disconnect()
        }
    }
}
