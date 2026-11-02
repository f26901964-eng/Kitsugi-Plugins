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

## Canlı host doğrulaması (CI, imzasız rapor)

`ANIZM_SAFE_LIVE=1` ile çalışan `AnizmSafeLiveProbeTest`, gerçek `AnizmStreams` kodunu canlı siteye karşı koştu. Rapor imzalı URL/sorgu/cookie/token içermez; yalnız host, durum, içerik tipi ve süre bayrakları yazar. Son başarılı koşu: [41491086855](https://github.com/f26901964-eng/Kitsugi-Plugins/actions/runs/41491086855).

Hedef: **Shingeki no Kyojin 1. bölüm** (`anizm.com.tr`). Arama → seri → 26 bölüm → 1. bölüm zinciri doğrulandı. Çözücü **13 akış** yayınladı:

| Kategori | Adet | Canlı HTTP sonucu | Yorum |
|---|---:|---|---|
| Google Drive | 7 | `206 video/mp4`; ilk 64 KiB örnekte MP4 süresi yaklaşık **1540–1552 s** | Drive kaynakları bu koşuda gerçek uzun video gövdesi döndürdü; HTML/kota yanıtı medya yapılmadı. |
| SendVid | 3 | `206 video/mp4`; MP4 süresi **5.0 s**, yaklaşık 36 KiB | Üç ayrı SendVid linki de hosttan kısa hata klibi döndürdü. Bu, eklentinin çözemediği bir URL değil; hostun verdiği medya bu. Kısa süre/boyut tek başına filtre nedeni yapılmadı. |
| OK.ru | 1 | HLS manifest `400` | Metadata’dan HLS bulundu; okcdn manifest isteği CI ortamında 400 döndü. 403/400’tan tek başına “ölü video” sonucu çıkarılmadı. |
| YourUpload / vidcache | 2 | `SocketTimeoutException` | CI ortamından CDN’ye zaman aşımı. Cihazda oynatma veya hostun kesin ölü olduğu iddiası yok. |
| Aincrad / AnizmPlayer | 0 | — | Bu bölümün canlı kaynak listesinde AnizmPlayer kaynağı dönmedi; HLS master düzeltmesi ağsız regresyonla doğrulandı. |

Canlı prob sonrası yapılan ek düzeltmeler:
- SendVid/vidcache/okcdn için açık `Origin` verilmemişse Referer’ın origin’i tüm emission yollarında ekleniyor; mevcut Origin değiştirilmiyor.
- SendVid embed sayfası açıkça “temporarily unavailable / video not found” diyorsa MP4 meta olsa bile yayınlanmıyor. Canlıdaki 5 saniyelik klipler sayfa hata metni vermeden geçerli MP4 döndürdüğü için süre/boyutla otomatik silinmedi.
- Güvenli prob HLS manifestlerini `Range` başlığı olmadan dener; binary/MP4 için sınırlı ön ek okur.

## Tekilleştirme ve uygulama sınırı

- Eklenti tekilleştirmesi artık yalnız URL değil: URL + tür + kalite + kanonik başlıklar + tüm ses parçaları/başlıkları + `extractorData`.
- Aynı URL farklı Referer/Cookie/ses/interceptor koşullarıyla gelirse bastırılmaz; birebir aynı koşul tekrarları bastırılır.
- Bu, **eklenti içi** düzeltmedir. Kitsugi-Beta uygulamasındaki `CsStreamSanitizer`, `CsStreamRunner`, `PlayerMediaSourceFactory` ve `CsVideoInterceptorFactory` hâlâ URL bazlı tekilleştirme veya ilk ses parçası/boş `extractorData` kullanıyor olabilir. Devir yamasındaki uygulama değişiklikleri bu depoda uygulanmadı ve cihazda yüklü oldukları varsayılmadı.
- Uygulama tarafındaki bu açıklar, ekran görüntüsündeki tüm hataların kesin kök nedeni olarak sunulmamıştır.

## Testler

- Yerel: `python3 -m unittest discover -s tests -v` → 30 test geçti.
- Yerel: `python3 tools/check_kotlin_comments.py` ve `git diff --check` temiz.
- Android/Gradle: yayınlama yapmayan `Derleme Testi` PR #64 üzerinden çalıştırıldı. Son koşu [41491086855](https://github.com/f26901964-eng/Kitsugi-Plugins/actions/runs/41491086855) **başarılı**: `Etkin Eklentileri Derle`, `Birim Testleri (regresyon)` (`:Anizm:testDebugUnitTest` dahil), `Anizm paket ve manifest tutarlılığı` ve artifact yükleme adımları geçti.
- Paket doğrulaması: CI içinde `Anizm/build/Anizm.cs3` üretildi; `classes.dex` mevcut, `.cs3` iç `manifest.json` adı `Anizm` ve sürümü `22`; `makePluginsJson` çıktısındaki tek Anizm girdisi de sürüm `22`. Artifact: `Kitsugi-Plugins-41491086855` (14 gün saklanır). Bu artifact yayın değildir; `builds` dalına yazılmadı.
- İlk CI denemeleri test altyapısı hataları verdi (test classpath'inde CloudStream API/çalışma zamanı bağımlılıkları, OkHttp test imzası, Drive test fixture'ı). Bunlar düzeltildi; son koşu temiz. Canlı host probları bu koşuda etiketle tetiklenmedi ve çalışma ortamı'tan doğrudan host erişimi kurulamadı.
- Canlı video hostları: sandbox’tan doğrudan erişim kurulamadı; CI içinde güvenli canlı prob çalıştırıldı. 403/TLS/zaman aşımı sonucu “ölü video” olarak yorumlanmadı.
- Gerçek Android cihazda Media3/MPV ile görüntü+ses testi yapılmadı.

## Yayın durumu

**Bu dalın v22 değişiklikleri yayımlanmadı.** Tabandaki v21 daha önce yayımlanmıştı; v22 yalnızca çalışma dalındadır. `builds` dalına, `prebuilt/` klasörüne veya cihazlara dağıtım yapılmadı. Yayın için ayrı ve açık karar gerekir.
