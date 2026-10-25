package com.kraptor

import android.app.AlertDialog
import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.ValueCallback
import android.webkit.WebView
import android.widget.LinearLayout
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
 * Bot kontrolü (DEX'teki BotKontrolKt'nin kaynak yeniden yazımı).
 *
 * Yasal yol (A1): cihazda bir WebView açılır, site normal bir oturumla yüklenir.
 * Challenge sayfası göründüğünde 5 parçalık bulmaca HTTP üzerinden çözülür
 * (parça hash'leri DOM'dan, parça resim boyutları doğrudan istekle alınır;
 * boyutu diğerlerinden farklı olan parça cevap olarak gönderilir).
 * Oturum kurulunca `AitrWeb.Session` çerezi WebView'in cookie kutusundan
 * okunup kalıcı SharedPreferences'e ("bot_cookies") kaydedilir.
 * Harici captcha çözme servisi, bot ordusu, UA/TLS oyunu KULLANILMAZ.
 *
 * NOT (T3): DEX'te BASE_URL "https://www.tranimeizle.io" sabitti; uygulama
 * tarafı ".live" kullanıyordu. Bu yeniden yazımda tüm adresler verilen
 * [url]'in host'undan türetilir — tek doğru host, eklentinin mainUrl'i.
 */
object BotKontrolKt {

    private const val TAG = "Ayzen_tranimeizle"
    private const val BOT_TIMEOUT_MS = 45_000L
    private const val HTTP_TIMEOUT_MS = 7_000
    private const val PREFS_NAME = "bot_cookies"
    private const val SESSION_COOKIE_NAME = "AitrWeb.Session"
    private const val MONITOR_MAX_ITERATIONS = 42
    private const val MONITOR_POLL_MS = 1_000L

    private const val UA_FIREFOX =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:152.0) Gecko/20100101 Firefox/152.0"

    @Volatile
    private var activeDialog: AlertDialog? = null

    private val webViewMutex = Mutex()

    /** Tek kullanımlık devam (continuation) kilidi. */
    private class ResumeGuard {
        @Volatile
        var active = true
    }

    // ------------------------------------------------------------------
    // Kalıcı çerez (SharedPreferences: "bot_cookies", anahtar = host)
    // ------------------------------------------------------------------

    fun getSavedCookie(context: Context, url: String): String {
        val host = hostOf(url)
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(host, "") ?: ""
    }

    fun clearCookie(context: Context, url: String) {
        val host = hostOf(url)
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().remove(host).apply()
        CookieManager.getInstance().flush()
    }

    /**
     * WebView'in cookie kutusunu temizler ve [cookieString] içindeki
     * `k=v` parçalarını site host'una yeniden yazar (session aktarımı).
     */
    fun resetCookieManagerTo(cookieString: String, siteUrl: String) {
        val cm = CookieManager.getInstance()
        @Suppress("DEPRECATION")
        cm.removeAllCookies()
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
     * [url] sayfasının bot korumasını aşarak oturum çerezi döndürür.
     *
     *  - forceRefresh=false ve geçerli bir kayıt varsa WebView AÇILMAZ,
     *    kayıtlı çerez geri verilir (ve cookie kutusuna yazılır).
     *  - Aksi halde WebView diyalogu ile challenge çözülür.
     *  - Toplam 45 sn'yi aşarsa (Mutex TIMEOUT) kayıtlı çereze düşülür.
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
                        val cached = getSavedCookie(context, url)
                        if (cached.isNotEmpty()) {
                            resetCookieManagerTo(cached, url)
                            Log.d(TAG, "Cached cookie kullanılıyor (WebView açılmadı)")
                            return@withLock cached
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
        val baseUrl = "https://$host"
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

                val webView = WebView(context).apply {
                    layoutParams = LinearLayout.LayoutParams(-1, -1)
                    settings.apply {
                        javaScriptEnabled = true
                        domStorageEnabled = true
                        useWideViewPort = true
                        loadWithOverviewMode = true
                        supportZoom = false
                        displayZoomControls = false
                        builtInZoomControls = false
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
                    monitorChallenge(webView, guard, cont, url, host, prefs)
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
     * Challenge izleyici döngüsü:
     *  1. Sayfa /api/Captcha üzerindeyse → DOM'dan 5 [icon-hash] topla.
     *  2. Her hash'in resim boyutunu HTTP ile al (boyutu farklı olan = cevap).
     *  3. Cevabı POST et (cID=0&pC=<hash>&rT=2, 200 beklenir).
     *  4. /api/CaptchaChallenge/ GET'i ile 302 doğrula.
     *  5. WebView'in challenge'dan içeri geçip `AitrWeb.Session` çerezini
     *     cookie kutusuna yazmasını bekle; yazılınca kaydet + diyalogu kapat.
     */
    private suspend fun monitorChallenge(
        webView: WebView,
        guard: ResumeGuard,
        cont: CancellableContinuation<String>,
        url: String,
        host: String,
        prefs: SharedPreferences
    ) {
        val sizeCache = HashMap<String, Int>()
        var iterations = 0

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
            val cm = CookieManager.getInstance()
            val inChallenge = pageUrl.contains("/api/Captcha", ignoreCase = true)

            if (inChallenge) {
                val jarCookie = cm.getCookie(url) ?: ""
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
            }

            val session = extractSessionCookie(cm.getCookie(url))
            if (session.isNotEmpty()) {
                Log.d(
                    TAG,
                    if (pageUrl.contains("/api/Captcha", ignoreCase = true))
                        ">>> CAPTCHA ÇÖZÜLDÜ (challenge→article) <<<"
                    else
                        ">>> CACHED COOKIE GEÇERLİ (captcha gerekmedi) <<<"
                )
                prefs.edit().putString(host, session).apply()
                withContext(Dispatchers.Main) {
                    webView.evaluateJavascript(
                        "(function() { document.body.innerHTML = " +
                            "'<h1 style=\"color:#00ff00;font-size:48px;" +
                            "text-align:center;margin-top:40%\">Doğrulama Tamamlandı!</h1>'; });",
                        null
                    )
                }
                delay(800)
                withContext(Dispatchers.Main) {
                    activeDialog?.dismiss()
                    activeDialog = null
                }
                safeResume(guard, cont, session)
                return
            }
        }

        Log.e(TAG, ">>> Bot kontrolü zaman aşımında (çerez alınamadı) <<<")
        withContext(Dispatchers.Main) {
            activeDialog?.dismiss()
            activeDialog = null
        }
        safeResume(guard, cont, "")
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
     * Başarı = HTTP 200.
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
