# DiziBox ve HDFilmIzle video akışı incelemesi

**Tarih:** 25 Ekim 2026
**Dal:** `arena/35e4fac2-kitsugi-plugins`
**Kapsam:** DiziBox ve HDFilmIzle video bağlantılarının, altyazıların ve mevcut kalite/ses seçeneklerinin kaynağını incelemek; kanıtla desteklenen ayrıştırma hatalarını düzeltmek.

## Sonuç özeti

- DiziBox'ta güçlü bir kök neden bulundu: `/embed/sheila/{id}` bağlantısı doğrudan HLS gibi gönderiliyor; böylece ilişkili MolyStream oynatıcı sayfasının (`/embed/{id}`) şifresi çözülmüyor ve akış isteği için gereken oynatıcı-embed `Referer` bilgisi kaybolabiliyor. Akış zinciri, mevcut bağımsız DiziBox kaynak kodu ve MolyStream ayrıştırıcısı ile karşılaştırılarak güncellendi.
- DiziBox'ın medya ayrıştırıcısı altyazı nesnelerini kaynak listesinden ayırabiliyordu ama `subtitleCallback` çağrısı yapmıyordu. Altyazı gönderimi eklendi. Kaynak `kind` değeri korunuyor ve filtre `source.kind` üzerinden yapılıyor; URL'den `kind` türetilmiyor.
- HDFilmIzle/VidRame'de altyazı dizisi yoksa veya bozuksa `ObjectMapper.readValue()` video bağlantısı gönderilmeden önce istisna oluşturabiliyordu. Bu hata kaldırıldı; `sources` ve `configs.tracks` ayrı, toleranslı biçimde ayrıştırılıyor. Birden fazla kaynak/etiket destekleniyor.
- HDFilmIzle `.to` sayfalarının gerçek iframe/script gövdesi araştırma ortamındaki sayfa okuyucularından alınamadı. Bu nedenle `.to` için ses dili/DUAL yapısı veya canlı kaynak URL'si **doğrulanmış sayılmıyor**. Kod değişiklikleri mevcut VidRame işaretçileri ve bağımsız kaynak kodlarında görülen yapıyla sınırlı tutuldu.
- Yerel Gradle/Kotlin testleri çalıştırılamadı: bu çalışma ortamında `java`/JDK bulunmuyor. Bunun yerine GitHub Actions'ta tüm etkin eklentilerin derlemesi ve yeni Kotlin regresyon testleri başarıyla tamamlandı (run `40192751945`). Depo Python testleri de 10/10 başarılı; `git diff --check` temiz.

## İncelenen örnek sayfalar ve sınırlar

DiziBox'ta *Supernatural* S1E1, *The Mentalist* S1E1 ve *Sherlock* S1E1 sayfaları; HDFilmIzle `.to` üzerinde aynı üç diziye ait örnek sayfalar incelendi. Örnekler:

- DiziBox: [Supernatural](https://www.dizibox.live/supernatural-1-sezon-1-bolum-hd-3-izle/), [The Mentalist](https://www.dizibox.live/the-mentalist-1-sezon-1-bolum-izle/), [Sherlock](https://www.dizibox.live/sherlock-1-sezon-1-bolum-hd-1-izle/)
- HDFilmIzle: [Supernatural](https://www.hdfilmizle.to/dizi/supernatural-izle/sezon-1/bolum-1/), [The Mentalist](https://www.hdfilmizle.to/dizi/the-mentalist/sezon-1/bolum-1/), [Sherlock](https://www.hdfilmizle.to/dizi/sherlock/sezon-1/bolum-1/)

Okunabilir sayfa çıktısı HDFilmIzle Supernatural sayfasında `DUAL` ve `Vidrame` etiketlerini gösterdi; DiziBox sayfalarında da katalog/episode metni görüldü. Ancak hiçbir örnekte oyuncunun ham iframe URL'si, şifreli JS verisi veya HLS manifesti döndürülmedi. DiziBox için Jina Reader isteği sunucu/VPN IP engeliyle 403 aldı. Dolayısıyla burada canlı sayfadan alınmış bir test fixture'ı yoktur.

## DiziBox: bulunan akış zinciri ve düzeltme

### Dış kaynaklarla çapraz kontrol

Bağımsız [samkofte/dizibox-api](https://github.com/samkofte/dizibox-api) deposunun 14 Ekim 2026 tarihli kaynak kodu, `dizibox.live` için şu akışı uygular:

1. Bölüm sayfasındaki iframe'i bulur ve ilk oyuncu sayfasını bölüm URL'sini `Referer` yaparak ister.
2. İlk oyuncu sayfasındaki iç iframe'i bulur.
3. MolyStream gömülü oyuncu sayfasını DiziBox ana adresini `Referer` yaparak ister.
4. Sayfadaki `CryptoJS.AES.decrypt(...)` yükünü yerel CryptoJS uyumlu AES çözümüyle açar.
5. Açılan içerikteki `file` değerini MolyStream `/embed/sheila/...` akış adresi olarak alır; altyazıları `file` uzantısı `.vtt`/`.srt` ve etiket bilgisiyle ayıklar.
6. Depodaki proxy, HLS isteğinde embed URL'sini `Referer` olarak kullanır.

Kaynak: [client.py](https://github.com/samkofte/dizibox-api/blob/3f8654add4ad000df334c3ceb560d39d5c53f09e/dizibox_api/client.py) ve [app.py](https://github.com/samkofte/dizibox-api/blob/3f8654add4ad000df334c3ceb560d39d5c53f09e/app.py). Bu kod incelendi; canlı sayfada veya burada çalıştırılarak doğrulanmadı.

Ayrıca 18 Mayıs 2026 tarihli [MolyStream ayrıştırıcısı](https://github.com/manitux-app/manitux/blob/b714b70e34e1/Manitux.Core/Extractors/Molystream.cs), `.org` MolyStream adreslerinde `/embed/sheila/{id}` yolunu `/embed/{id}` oynatıcı sayfasına dönüştürüp şifreli yapılandırmayı çözmeyi, akışın Referer'ını gerçek embed sayfası yapmayı ve HLS varyantlarını çözmeyi deniyor. Tarihsel bir [BetterPlayer raporu](https://github.com/jhomlala/betterplayer/issues/309) da 2021 tarihli `.net` varyantında `Referer` ve `User-Agent` olmadan MolyStream kaynağının izin hatası verdiğini gösteriyor. Bu eski `.net` örneği, güncel `.org` içeriğinin birebir kanıtı değil; yalnızca korumalı istek davranışına ek destek.

### Yerel kök neden

`DiziBox.kt` içindeki önceki özel durum `/embed/sheila/` adresini hemen `ExtractorLinkType.M3U8` olarak yayımlıyordu. Bu yolun ilişkili `/embed/{id}` oyuncu sayfasına dönüştürülmesi, şifreli yükün çözülmesi ve sayfa içindeki gerçek `file`/altyazı nesnelerinin okunması atlanıyordu. Bağlantıya üstteki DiziBox/King sayfasının Referer'ı veriliyordu; oysa bağımsız akış kodu MolyStream embed URL'sini HLS isteğinin Referer'ı olarak kullanıyor.

### Uygulanan düzeltme

- MolyStream `.../embed/sheila/{id}` iframe'i, yalnızca MolyStream hostlarında, `.../embed/{id}` oynatıcı sayfasına normalize ediliyor ve önce o sayfa okunuyor.
- MolyStream oynatıcı sayfası isteğinde DiziBox ana adresi `Referer` olarak gönderiliyor; DiziBox çerezleri üçüncü taraf hosta aktarılmıyor.
- Açılan oyuncu sayfasından çıkan MolyStream kaynak isteği, embed sayfasının kendisini `Referer` olarak kullanıyor; gerekli `User-Agent`/`Origin` korunuyor.
- `tracks` içindeki VTT/SRT altyazıları `SubtitleFile` olarak bildiriliyor. Altyazıların `kind`/etiket/dil verileri korunuyor.
- Medya kaynaklarındaki `label` bulunduğunda CloudStream kalite adı ve bağlantı etiketi olarak kullanılıyor; etiket yoksa kalite `Unknown` bırakılıyor.
- Kaynak ayrıştırıcısı `sources: [...]` dizisini, altyazı `tracks` nesnelerinden ayrı okuyor. `MediaSource.kind` aynen taşınıyor ve captions/subtitles filtresi `source.kind` üzerinden uygulanıyor; URL uzantısı `kind` yerine kullanılmıyor.

**DiziBox ses/kalite sonucu:** İncelenebilen bağımsız akış kodu tek MolyStream HLS URL'si ve ayrı altyazı listesi gösteriyor; açık bir ayrı ses-track nesnesi göstermiyor. HLS manifestinin adaptive kalite/ses grupları burada elde edilemedi. Bu nedenle ses seçimi veya belirli çözünürlüklerin mevcut olduğu iddia edilmiyor. Kaynak scriptinde etiket varsa artık ayrı kalite olarak gösterilir; HLS manifest seviyesindeki seçim canlı oynatımda doğrulanmalıdır.

## HDFilmIzle `.to` / VidRame: bulgular ve düzeltme

### Yerel çağrı zinciri

Yerel `HDFilmIzle.loadLinks()` bölüm sayfasındaki `iframe[src]`/`iframe[data-src]` URL'lerini topluyor ve CloudStream `loadExtractor()` üzerinden kayıtlı `VidRameExtractor`'a veriyor. Extractor, VidRame sayfasındaki `sources:` ve `configs.tracks =` JS parçalarını arıyor. Önceki kod:

- `sources: [` sonrasından yalnızca tek nesne çıkarmaya çalışıyordu; birden fazla kaynak/kalite varsa JSON nesnesi olarak okunamıyordu.
- `configs.tracks =` yoksa `substringAfter()` tüm script metnini bırakabiliyordu.
- `ObjectMapper.readValue(subData)` `tryParseJson()` kontrolünden önce çağrıldığı için eksik/bozuk altyazı verisi video kaynağı callback'ini de engelleyebiliyordu.
- Kaynak etiketi/kalitesi kayboluyor ve her bağlantı `Unknown` olarak yayımlanıyordu.
- `kind == "captions"` karşılaştırması büyük/küçük harfe duyarlıydı.

Bu, **doğrudan koddan doğrulanabilen** bir hata yoludur; canlı `.to` oyuncu sayfasında `configs.tracks` alanının hangi bölümlerde eksik olduğuna dair ham fixture elde edilemedi.

### Uygulanan düzeltme

- Yeni `VidRamePlayerParser`, gözlenen `sources: [...]` ve `configs.tracks = [...]` işaretçilerini dengeli köşeli-parantez taramasıyla ayırıyor; birden fazla nesneyi okuyor.
- Altyazı dizisi bulunmaz/bozuk olursa boş liste dönüyor; bu durum video kaynaklarının ayrıştırılmasını durdurmuyor.
- `EE.dd("...")` çözümü mevcut uygulamadaki URL-safe Base64 → ROT13 → ters çevirme sırasını koruyor; yalnızca tanınan `EE.dd` ifadesi veya açık URL/path yayımlanıyor.
- `captions`/`caption`/`subtitles` türleri büyük-küçük harf duyarsız ayrılıyor; SRT/VTT adresleri ve etiket/dil bilgisi callback'e aktarılıyor.
- `sources` dizisindeki her kaynak ayrı callback alıyor. `label` varsa `getQualityFromName()` ile kalite adı atanıyor; `type`/uzantı MP4 veya DASH olarak tanımlanıyorsa uygun CloudStream türü kullanılıyor, aksi halde mevcut HLS varsayımı korunuyor.
- Kaynak scriptleri ayrı olsa bile video ve altyazı nesneleri toplanıyor; ham player dokümanı/şifreli token'lar log'a yazılmıyor.

### `.to` ses dilleri ve kardeş alan adı ayrımı

Mevcut `.to` örnek sayfasının okunabilir çıktısında `DUAL` etiketi görülmesi, bunun HLS içinde iki ses grubu mu, ayrı VidRame `sources`/`parts` mi, yoksa sayfa düzeyinde bir sürüm seçimi mi olduğunu kanıtlamıyor. Buna karşılık [falsisdev/anthology HDFilmIzle kodu](https://raw.githubusercontent.com/falsisdev/anthology/main/src/hdfilmizle/index.js) `.vip` ve `.ink` alan adları için `let parts = [...]`, `part.lang` ve Vidrame/Vidmoxy/FastPlay yollarını ayrıştırıyor. Bu, farklı kardeş alan adlarına ait kanıttır; `.to` sayfasına uygulanmadı.

**HDFilmIzle ses/kalite sonucu:** `.to` için açık bir ses-track seçici veya gerçek manifest görünmediğinden kodda ses-track URL'si uydurulmadı. VidRame JS kaynaklarında `label` varsa her etiket ayrı kaliteye bağlanır; label yoksa `Unknown` kalır. HLS manifestindeki ses ve adaptive kalite seçenekleri canlı manifest olmadan teyit edilemez.

## Değişen dosyalar

- `DiziBox/src/main/kotlin/com/keyiflerolsun/DiziBox.kt`
- `DiziBox/src/main/kotlin/com/keyiflerolsun/DiziBoxPlayerParser.kt`
- `DiziBox/src/test/kotlin/com/keyiflerolsun/DiziBoxPlayerParserTest.kt`
- `DiziBox/build.gradle.kts` — sürüm 25 → 26
- `HDFilmIzle/src/main/kotlin/com/nikyokki/VidRameExtractor.kt`
- `HDFilmIzle/src/main/kotlin/com/nikyokki/VidRamePlayerParser.kt` (yeni)
- `HDFilmIzle/src/main/kotlin/com/nikyokki/HDFilmIzle.kt` — kullanılmayan yerel altyazı modelinin kaldırılması
- `HDFilmIzle/src/test/kotlin/com/nikyokki/VidRamePlayerParserTest.kt` (yeni)
- `HDFilmIzle/build.gradle.kts` — sürüm 9 → 10

Yeni testler sentetik fixture'lar kullanıyor; canlı site yanıtı değildir.

## Doğrulama durumu ve kalan yayın kapısı

- `python3 -m unittest discover -s tests -v`: **10/10 başarılı**.
- `git diff --check`: **başarılı**.
- Yerel Kotlin/Gradle çalıştırıcısı bu ortamda yok; buna karşılık GitHub Actions run [`40192751945`](https://github.com/f26901964-eng/Kitsugi-Plugins/actions/runs/40192751945) başarılı: tüm etkin eklentiler derlendi, Kotlin regresyon testleri geçti ve indirilebilir artifact üretildi.
- Artifact: `Kitsugi-Plugins-40192751945` (72,608 byte; 14 günlük saklama). Run sayfasındaki **Artifacts** bölümünden ZIP'i indirip içindeki `DiziBox.cs3` ve `HDFilmIzle.cs3` dosyalarını test için kullanın.
- PR [#37](https://github.com/f26901964-eng/Kitsugi-Plugins/pull/37) açık, henüz `main` ile birleştirilmedi. Bu nedenle app-facing plugin feed'in güncellendiği veya CDN/önbellek görünürlüğünün doğrulandığı iddia edilmiyor. Önceki araştırmada raw GitHub ve jsDelivr manifestleri uyuşmuyordu ve purge isteği throttling almıştı.
- DiziBox/HDFilmIzle canlı sayfalarından akış URL'si/manifest alınamadığı için gerçek canlı playback smoke-test'i yapılmadı. CI, Kotlin derlemesini/testlerini doğrular; üçüncü taraf oynatıcıların güncel davranışını değil.
- Son CI düzeltmesi `4d157ea` ile Kotlin `Sequence` sonuçları açıkça `List`'e dönüştürüldü. Güncel kod `arena/35e4fac2-kitsugi-plugins` dalında; PR #37 üzerinden incelenebilir.

Canlı doğrulamayı tamamlamak için güncel uygulamada DiziBox MolyStream ve HDFilmIzle VidRame örneklerinden birer playback denemesi; başarısızsa logcat'ten yalnızca `DZBX`/`VidEx` çevresindeki hata satırları veya kişisel/signed query değerleri çıkarılmış iframe/player HTML fixture'ı gerekir. Ham DRM/erişim anahtarları veya hesap bilgileri paylaşılmamalıdır.
