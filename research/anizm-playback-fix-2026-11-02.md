# Anizm v22 — oynatma düzeltmeleri ve doğrulama durumu

**Tarih:** 2 Kasım 2026  
**Kapsam:** `f26901964-eng/Kitsugi-Plugins`, çalışma dalı `arena/b9adaf81-kitsugi-plugins`  
**Taban:** `main` üzerinde başka bir çalışma tarafından yayımlanmış Anizm v21. Bu dal v21’i tekrar yayımlamaz; v22 ek düzeltmelerini içerir.

**Girdi:** Google Drive devir klasörü (`00_OKU_Kaynaklar_ve_Devir.md`, üç ekran görüntüsü, `anizm-v20-to-v21.patch`). Uygulama yaması yalnızca referans olarak okundu; bu depoya uygulanmadı.

## Kaynak bazında sonuç

| Kaynak | Yapılan değişiklik | Kanıtlanan | Henüz doğrulanmayan |
|---|---|---|---|
| **Aincrad / AnizmPlayer** | İmzalı HLS master artık varyantlara bölünmeden, sorgu/imza korunarak `M3U8` tipinde yayınlanıyor. `.txt` kopya yalnızca gövde `#EXTM3U` ise kullanılıyor. Çözümleme ve oynatma UA'sı eşitlendi. | Ağsız davranış testi: imzalı master aynen korunuyor, varyant açılımı yapılmıyor. | Canlı `getVideo`, master/segment erişimi, cihazda görüntü+ses ve kalite seçimi. |
| **Odnoklassniki / OK.ru** | HLS master korunuyor; progressive alternatifler ayrı yayınlanıyor. Embed `Referer` ve `Origin` taşınıyor. Host eşleşmesi suffix değil gerçek host/subdomain. | Ağsız test: HLS + iki MP4 alternatifi, doğru Referer/Origin. | OK.ru metadata/okcdn HLS canlı erişimi ve cihazda oynatma. Hosta özgü kesin kök neden iddiası yok. |
| **SendVid** | Yerel çözücü `og:video`, `<source>` ve `video_source` MP4 yolunu okuyor; doğrudan MP4'te özgün Referer korunuyor. Boyut/süre tek başına hata klibi sayılmıyor. | Ağsız test: MP4 embed çözümü, ayrı linklerin bağımsızlığı, hata HTML'sinin medya yapılmaması. | Ekran görüntüsündeki beş saniyelik hata klibi hosttan geliyorsa eklenti bunu geri getiremez. Diğer SendVid linkleri canlı denenmedi. |
| **YourUpload / vidcache** | Generic extractor yeniden yayınında `link.referer`, büyük/küçük harfe duyarsız başlıklar, `Cookie`, `Origin`, `User-Agent`, tüm `audioTracks` ve `extractorData` korunuyor. Vidcache/YourUpload için gerekirse embed Origin türetiliyor. | Ağsız test: Referer, cookie, UA, iki ses parçası ve interceptor verisi kaybolmuyor. | Gerçek extractor yönlendirmesi, CDN ve cihazda oynatma. |
| **Google Drive** | `/uc` / `/download` sorgusu korunuyor; gerçek GET onay formu alanları action sorgusuyla birleştiriliyor; kota/HTML video diye yayınlanmıyor; yanıt en çok 4 KiB binary / 256 KiB HTML örnekleniyor; nihai redirect adresi ve HLS/DASH tipi korunuyor. | Ağsız test: form, kota, 404, belirsiz erişim, uzantısız HLS, sınırlı okuma ve yanıt kapatma. | Gerçek dosya izni, cookie, kota/virüs taraması onayı, transcode ve cihazda oynatma. |

## Tekilleştirme ve uygulama sınırı

- Eklenti tekilleştirmesi artık yalnız URL değil: URL + tür + kalite + kanonik başlıklar + tüm ses parçaları/başlıkları + `extractorData`.
- Aynı URL farklı Referer/Cookie/ses/interceptor koşullarıyla gelirse bastırılmaz; birebir aynı koşul tekrarları bastırılır.
- Bu, **eklenti içi** düzeltmedir. Kitsugi-Beta uygulamasındaki `CsStreamSanitizer`, `CsStreamRunner`, `PlayerMediaSourceFactory` ve `CsVideoInterceptorFactory` hâlâ URL bazlı tekilleştirme veya ilk ses parçası/boş `extractorData` kullanıyor olabilir. Devir yamasındaki uygulama değişiklikleri bu depoda uygulanmadı ve cihazda yüklü oldukları varsayılmadı.
- Uygulama tarafındaki bu açıklar, ekran görüntüsündeki tüm hataların kesin kök nedeni olarak sunulmamıştır.

## Testler

- Yerel: `python3 -m unittest discover -s tests -v` → 30 test geçti.
- Yerel: `python3 tools/check_kotlin_comments.py` ve `git diff --check` temiz.
- Android/Gradle: yayınlama yapmayan `Derleme Testi` iş akışında `:Anizm:testDebugUnitTest`, `:Anizm:make`, `makePluginsJson` ve `.cs3` iç manifest tutarlılığı çalıştırılır. Sonuç CI koşusundan sonra buraya işlenir.
- Canlı video hostları: bu çalışma ortamından doğrudan erişim kurulamadı. 403/TLS/zaman aşımı sonucu “ölü video” olarak yorumlanmadı.
- Gerçek Android cihazda Media3/MPV ile görüntü+ses testi yapılmadı.

## Yayın durumu

**Bu dalın v22 değişiklikleri yayımlanmadı.** Tabandaki v21 daha önce yayımlanmıştı; v22 yalnızca çalışma dalındadır. `builds` dalına, `prebuilt/` klasörüne veya cihazlara dağıtım yapılmadı. Yayın için ayrı ve açık karar gerekir.
