package com.kraptor

import com.lagradost.api.Log

/**
 * Lisans kapısı — **KALDIRILDI** (v28).
 *
 * DEX'ten taşınan bu sınıf, uygulamanın APK imzasının SHA-256 özetini üç sabit hash
 * ile karşılaştırıyor ve eşleşmezse eklentiyi sessizce devre dışı bırakıyordu
 * (arama boş, load null, loadLinks false → kullanıcı "akış bulunamadı" görüyordu).
 * Kitsugi'nin yeniden imzalanmış/yeniden derlenmiş her sürümünde hash listeye
 * uymadığı için eklenti HİÇ çalışmıyordu.
 *
 * Eklentinin sitenin içeriğine erişmesi için uygulama imzasına bakmasına gerek yok;
 * bu yüzden kapı kaldırıldı. Sınıf, dışarıdan çağrılabilecek eski API'ler
 * bozulmasın diye korunuyor ama artık her zaman izin verir.
 */
object TrAnimeIzleHelper {

    private const val TAG = "kraptor_TrAnime"

    @Volatile
    var isAllowedVersion: Boolean = true
        private set

    /** Geriye dönük uyumluluk: eskiden imza doğrulaması yapıyordu, artık no-op. */
    fun setup(@Suppress("UNUSED_PARAMETER") context: android.content.Context) {
        isAllowedVersion = true
        Log.d(TAG, "Lisans kapisi kaldirildi (v28) — eklenti her kurulumda etkin.")
    }

    fun setAllowedVersion(@Suppress("UNUSED_PARAMETER") value: Boolean) {
        isAllowedVersion = true
    }
}
