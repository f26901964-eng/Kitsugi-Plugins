package com.kraptor

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Process
import com.lagradost.api.Log
import java.security.MessageDigest

/**
 * Lisans/kapak doğrulama yardımcı sınıfı (DEX'teki TrAnimeIzleHelper birebir karşılığı).
 *
 * Uygulamanın paket imzasının SHA-256 özetini alıp izinli hash listesinden geçirir.
 * İzinli değilse eklenti arama/yüklemeyi boş sonuçla yanıtlar (kapanmaz, sadece pasif kalır).
 */
object TrAnimeIzleHelper {

    private const val TAG = "CSLocal"

    // Bu paket adları doğrudan reddedilir (debug/sahte kurulumlar).
    private val hataliPaketAdlari = setOf(
        "com.lagradost.cloudstream3.prerelease.debug"
    )

    // İzinli uygulama imza (SHA-256 hex) listesi.
    private val izinliImzaHashleri = setOf(
        "7bbc1d7254613ffaa026de47d975f73968361ef3e5d7b818ce0a83a33039c6b8",
        "9b4f6c87eeb70b257c7f968e65b15616824bce33e9cc74a07ad3dd26632a1db7",
        "c2755b1fbc6cefa6aa20739f527d09246525618a7e16edb2e2171c2c9de70c72"
    )

    @Volatile
    var isAllowedVersion: Boolean = false
        private set

    fun setup(context: Context) {
        val pkg = gercekPaketAdiniBul(context)
        if (hataliPaketAdlari.contains(pkg)) return
        val hash = imzaHashiniAl(context, pkg)
        isAllowedVersion = izinliImzaHashleri.contains(hash)
        // v27: kapak kapaliyken eklenti sessizce bos sonuc donuyor; kullanici
        // bunu "eklenti bozuk / coktu" sanuyordu. En azindan sebebi bilelim.
        if (!isAllowedVersion) {
            Log.e(TAG, "Eklenti bu uygulama kurulumunda devre disi (imza=$hash). Arama bos doner.")
        } else {
            Log.d(TAG, "Paket: $pkg izinliSurum: $isAllowedVersion")
        }
    }

    fun setAllowedVersion(value: Boolean) {
        isAllowedVersion = value
    }

    /**
     * Eklentiler bazen başka bir UID altında çalışır (isolated process vb.);
     * bu yüzden paket adı UID üzerinden bulunur, bulunamazsa context paketi kullanılır.
     *
     * DEX'teki çağrı SDK korumasızdı (API 24+); minSdk 21 olduğu için
     * burası korumalı olarak yeniden yazıldı (21–23'te context paketi kullanılır).
     */
    private fun gercekPaketAdiniBul(context: Context): String {
        return try {
            val packages = if (Build.VERSION.SDK_INT >= 24)
                context.packageManager.getPackagesForUid(Process.myUid())
            else null
            packages?.firstOrNull() ?: context.packageName
        } catch (_: Exception) {
            context.packageName
        }
    }

    private fun imzaHashiniAl(context: Context, packageName: String): String = try {
        val signature = if (Build.VERSION.SDK_INT >= 28) {
            context.packageManager
                .getPackageInfo(packageName, PackageManager.GET_SIGNING_CERTIFICATES)
                .signingInfo?.apkContentsSigners?.firstOrNull()
        } else {
            @Suppress("DEPRECATION")
            context.packageManager
                .getPackageInfo(packageName, 64 /* PackageManager.GET_SIGNATURES */)
                .signatures?.firstOrNull()
        } ?: return ""
        MessageDigest.getInstance("SHA-256")
            .digest(signature.toByteArray())
            .joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }
    } catch (_: Exception) {
        ""
    }
}
