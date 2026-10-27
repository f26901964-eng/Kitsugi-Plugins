# TrAnimeIzle — HTML haritası ve video kaynak tablosu (28.10.2026)

Bu belge, **TrAnimeIzle eklentisinin (v39)** üzerine kurulduğu sitenin
HTML/API sözleşmesini tek bir tabloda toplar. Her satır bir kanıta bağlanmıştır;
"tahmin" yoktur, doğrulanamayan satırlar **açıkça işaretlenmiştir**.

---

## 0. Kanıt durumu (neden "site HTML'i canlı okunamadı")

| Kanıt | Ne diyor |
| --- | --- |
| Depo `site-dump-data` / `tranime-dump-data` dalları → `tizle__home_direct__*.html` | `www.tranimeizle.live` / `.io` / `.top` → **HTTP 403**, gövde: *"Sorry, you have been blocked"* (**Cloudflare Error 1005** — ASN/veri merkezi IP engeli) |
| Aynı dal → `tizle__home_jina__*.html` | Okuyucu köprüsü (`r.jina.ai`) üzerinden de **Error 1005** — site datacenter IP'sini her kanaldan reddediyor |
| `tizle__search_*` dosyaları | `/arama/…`, `?q=`, `/search?` üçü de aynı engel sayfası → **arama ucu da okunamadı** |
| Depo `js/site.js` arşivi (22.09.2026) | Sitenin kendi istemci kodu: `fansubSources` → `sourcePlayer` → `preVideoAd` zinciri |
| `research/anizm-tranimeizle-kaynak-protokolleri-2026-10-26.md` | Aynı zincirin önceki ölçümü + host envanteri |

> **Sonuç:** Bu ortamdan (ve GitHub runner'larından) sitenin **canlı HTML'i
> alınamıyor**. Sözleşme bu yüzden *sitenin kendi JS'i* + *sahada çalışan iki
> bağımsız istemci* ile çapraz doğrulandı:
>
> 1. **Aniyomi** — `yuzono/anime-extensions`, `src/tr/tranimeizle/…/TRAnimeIzle.kt`
>    ve `ShittyCaptchaInterceptor.kt`
> 2. **CloudStream** — `UmayTrade/MyTV`, `TrAnimeIzle/src/main/kotlin/com/Blockades/TrAnimeIzle.kt` (v4)
> 3. Depodaki `site.js` arşivi + önceki cihaz kanıtları (v17–v34 raporları)
>
> İki istemcinin **aynı seçicileri ve aynı uçları** kullanması, tablodaki
> satırların "gerçek site yapısı" olduğunun kanıtıdır. Cihazdan **birebir HTML
> dökümü** almak için: `tools/tizle_dump.py` (bkz. §7).

---

## 1. Sayfa haritası (ne, nerede, hangi HTML düğümü)

| # | Ne | URL | Yöntem | HTML'de aranan düğüm/öznitelik | Kanıt |
| --- | --- | --- | --- | --- | --- |
| 1 | Ana sayfa | `/?sayfa={n}` | GET | `div.flx-block` kartları | MyTV |
| 2 | Popüler | `/listeler/populer/sayfa-{n}` | GET | `div.post-body div.flx-block` | Aniyomi |
| 3 | Son bölümler | `/listeler/yenibolum/sayfa-{n}` | GET | aynı kart yapısı | Aniyomi |
| 4 | Yeni animeler | `/listeler/eklenen/sayfa-{n}` | GET | aynı kart yapısı | MyTV |
| 5 | Arama | `/arama/{sorgu}?page={n}` | GET | aynı kart yapısı | Aniyomi + MyTV |
| 6 | Anime detayı | `/anime/{slug}-izle` | GET | `div.playlist-title h1`, `div.poster .social-icon img`, `div.col-md-6 > div.row`, `a.genre`, `div.p-10 > p` | Aniyomi |
| 7 | Bölüm listesi | detay sayfası | — | `div.animeDetail-items > ol a:has(div.episode-li)`, `.etitle > span`, `.etitle > small.author` | Aniyomi + MyTV |
| 8 | Bölüm sayfası | `/{slug}-{n}-bolum-izle` | GET | `input#EpisodeId`, `div.fansubSelector[data-fid]`, `animeWatch.initialize(aId, eId, fId)` | Aniyomi + MyTV |

### Kart (liste/arama) alanları

| Alan | Kaynak | Not |
| --- | --- | --- |
| Adres | `data-href` (⚠️ `<a href>` değil) | Kart JS ile yönlendiriyor |
| Başlık | `div.bar > h4` | Sondaki `İzle` / `Bölüm` atılır |
| Kapak | `img` → `src` / `data-src` | Lazy-load |
| Puan | `rating[data-src]` | 0–10 |

### Bölüm URL'i → seri URL'i (Aniyomi'nin birebir kuralı)

```
/anime + <kart yolu>  →  "-bolum" öncesini al  →  son "-" sonrasını at  →  + "-izle"
Örnek: /naruto-1120-bolum-izle  →  /anime/naruto-izle
```

---

## 1.5 Birebir ölçülen HTML — ana sayfa (Wayback, 31.08.2026)

Elimizdeki **tek gerçek site HTML'i**. Kaynak: **`tranime-dump-data`** dalı →
`arsiv/home__20260831013714.html` (68.806 B), `tools/tizle_archive.py` ile
Wayback CDX'ten alındı (döküm artık ayrı dala itiliyor: aynı repoda çalışan
başka bir oturum `site-dump-data` dalını eziyordu). Bu bölümdeki her satır **ölçüm**, tahmin değil.

```html
<div class="flx-block" data-href="/detective-conan-1080-bolum-izle">
    <a class="news-image" href="/detective-conan-1080-bolum-izle">
        <img alt="" class="img-responsive" src="https://static.tranimeizle.top/animes/506/medium.jpeg" />
    </a>
    <div class="reatting">
        <i class="fa fa-star"></i> ×5
    </div>
    <div class="shadow-overlay"></div>
    <div class="bar">
        <h4>Detective Conan 1080. Bölüm İzle</h4>
        <span class="info-chip pull-right">
            <a class="text-white-imp" href="/detective-conan-1080-bolum-izle">Anime İzle</a>
        </span>
        <span class="info-chip">BÖL 1080 / 0</span>
    </div>
</div>
```

| Ölçüm | Değer | Not |
| --- | --- | --- |
| `div.flx-block` sayısı | **16** | ana sayfa = son eklenen bölümler |
| `data-href` sayısı | **16** | kartın **gerçek** adresi `<a href>` değil, `data-href` |
| `div.bar > h4` | 16 | başlık: `"<Seri> <N>. Bölüm İzle"` |
| `span.info-chip` | 16 çift | `BÖL 1080 / 0` = (bölüm / sezon?) — **bölüm numarası buradan da okunabilir** |
| Poster alanı | `static.tranimeizle.top/animes/<id>/medium.jpeg` | görseller ayrı alan adında |
| `canonical` | `https://www.tranimeizle.io/` | 31.08.2026 itibarıyla kanonik alan adı `.io` |
| `<title>` | `Anime izle \| Türk anime tv \| Türkçe anime izle` | |
| Arama URL'i | `/arama/{encodeURIComponent(q)}` | sayfadaki inline JS'ten birebir |
| Arama kutusu | `input#searchBar` | JS `window.location.href = searchUrl` |
| Tür sayfaları | `/animeizle/<tür>-anime-izle-1` | 19 tür: aksiyon, arabalar, askeri, bilim-kurgu, buyu, cocuk, dogaustu-gucler, donghua, dovus, dram, ecchi, fantastik, gerilim, gizem, harem, isekai, josei, kisilik-bolunmesi, komedi, korku… |
| Yüklenen JS | `/js/site.js`, `/js/mm7.js?=v5`, `/js/spo.js?=v8`, `/js/siyahmod.js?=v49`, `/js/icon-captcha.min.js` | oynatıcı zinciri **`site.js`** içinde |
| Reklam/3. taraf | `wargamings.net/…class=video-card`, `googletagmanager` | kart sınıfıyla çakışabilir → seçici `div.flx-block` ile sınırlı |

> **Ölçümün doğrudan sonucu:** ana sayfa/liste/arama kartları **bölüm** sayfasına
> gider (`…-bolum-izle`), **seri** sayfasına değil. Bu yüzden eklenti her kartı
> `TrAnimeIzleMatch.toSeriesUrl()` ile `/anime/<slug>-izle` biçimine çevirip
> seri bazında tekilleştiriyor (aynı serinin 10 bölümü 10 kart olmasın).

---

## 2. Video kaynak zinciri (sitenin kendi 4 adımı)

```
bölüm sayfası            input#EpisodeId / animeWatch.initialize(aId, eId, fId)
        │
        ├─ fansub seçiciler       div.fansubSelector[data-fid]      (data-fad = ad)
        ▼
POST /api/fansubSources   {"EpisodeId":123,"FansubId":456}   (JSON gövde!)
        │                 → HTML: li.sourceBtn[data-id]  (p.title = HOST adı)
        ▼
POST /api/sourcePlayer/{id}   (boş gövde)
        │                     → {"source":"<iframe src=\"…/embed2/?id=<GERÇEK ADRES>\">"}
        ├─ (boş/bozuk cevap)  POST /api/preVideoAd/{id}  → düz oynatıcı HTML'i
        ▼
oynatıcı HTML'i  →  sarmal açılır  →  host  →  extractor  →  m3u8/mp4
```

| Adım | Uç | Gövde / Yanıt | Kritik ayrıntı |
| --- | --- | --- | --- |
| 1 | `GET` bölüm sayfası | HTML | `#EpisodeId` yoksa `animeWatch.initialize(aId, eId, fId)` içinden |
| 2 | `POST /api/fansubSources` | `{"EpisodeId":…,"FansubId":…}` → HTML | **Gerçek JSON gövde** şart; form gövde boş liste döndürür (v23 kök nedeni) |
| 3 | `POST /api/sourcePlayer/{id}` | boş gövde → `{"source":"…"}` | `source` alanı **iframe HTML'i**; `contributerNotes` de döner |
| 3b | `POST /api/preVideoAd/{id}` | boş gövde → düz HTML | Sitenin "günün ilk tıklaması" yolu; bağımsız 2. kaynak |
| 4 | oynatıcı sayfası | HTML/JS | Sarmal açılır (aşağıdaki tablo) |

### Captcha (ikon doğrulama) — yalnızca gerektiğinde

Site, bot şüphesinde istemciyi `/api/CaptchaChallenge` sayfasına yönlendirir.
Akış (Aniyomi `ShittyCaptchaInterceptor` birebir):

| # | İstek | Yanıt |
| --- | --- | --- |
| 1 | `POST /api/Captcha/` · form `cID=0&rT=1&tM=light` | 5 adet görsel kimliği: `["h1","h2",…]` |
| 2 | `GET /api/Captcha/?cid=0&hash=<h>` ×5 | 5 ikon (PNG baytları) → her birinin **MD5**'i |
| 3 | `POST /api/Captcha/` · form `cID=0&rT=2&pC=<tek olan hash>` | Doğrulama; çerez tazelenir |
| 4 | İlk istek tekrar | Gerçek sayfa |

> **v39 düzeltmesi:** Eski kod gövdede geçen *herhangi bir* `/api/Captcha`
> metnini "bot" sayıyordu. Sitenin kendi JS'i bu uçtan söz ettiği için **normal
> sayfalar** da bot sanılıp WebView diyaloğu açılıyordu. Artık yalnızca gerçek
> kapı işaretleri (`captcha-holder`, `iconCaptcha`, `CaptchaChallenge`) aranıyor
> ve kapı **HTTP ile sessizce** çözülüyor (diyalog yok).

---

## 3. Oynatıcı sarmalları (host'a gitmeden önce açılan katmanlar)

| Sarmal | Biçim | Açma kuralı | Durum |
| --- | --- | --- | --- |
| **luffytra2 / embed2** | `https://…/embed2/?id=<gerçek adres>` | `id=` değeri alınır; `&` ile **kesilmez**, ham + URL-çözülmüş biçim denenir | ✅ v39 (`embedInnerUrl`) |
| **Aitr indirme köprüsü** | `…/downloadru2.php?url=<base64>` | base64 çözülür → iç adres | ✅ v39 (`downloadBridgeUrl`, saf Kotlin çözücü) |
| **AitrVip / OpTraco** | `https://optraco.top/explorer/<id>` | sayfadaki `file: "…m3u8"` (göreli olabilir) | ✅ v39 (`optracoMediaUrl` + `OptracoExtractor`) |
| **Hdvid** | `https://hdvid.tv/…` | `sources:[{file:"…"}]` | ✅ `HdvidExtractor` |
| **Hexload** | `https://hexload.com/…` | `POST /download` (`op=download3`) → `result.url` | ✅ `Hexload` |
| **Beta Player** | `…/watch/<hash>` (puffytr) | `/stream/<hash>/native.m3u8` · `master.txt` | ✅ v30 `resolveSitePlayer` |
| **Sistenn ailesi** | `https://host/#<kod>` | AES-128-CBC `/api/v1/video` (`kiemtienmua911ca` / `1234567890oiuytr`) | ✅ v30 |
| **FireStream** | `/e/<slug>` + `token-blob` | `POST /api/videos/<slug>/resolve` | ✅ v30 |

---

## 4. Host matrisi (sitede görülen video sunucuları)

| Host | Adres biçimi | Tespit | Çözücü | Durum |
| --- | --- | --- | --- | --- |
| **Ok.RU** | `ok.ru/videoembed/<id>` | `ok.ru`, `odnoklassniki` | yerel: `dk?cmd=videoPlayerMetadata` → `hlsManifestUrl` + `videos[]` | ✅ v30 |
| **Sibnet** | `video.sibnet.ru/shell.php?videoid=<id>` | `sibnet.ru` | yerel: `player.src([{src:"/videos/…"}])` | ✅ v30 |
| **SendVid** | `sendvid.com/<id>` · `/embed/<id>` | `sendvid.com` | yerel: `video_source = "…mp4"` | ✅ v39 |
| **Vidmoly** | `vidmoly.to/me/biz` `/watch|<embed>/<id>` | `vidmoly.` | yerel: `sources:[{file:"…"}]` | ✅ v39 |
| **OpTraco / AitrVip** | `optraco.top/explorer/<id>` | `optraco.` | yerel `file:` + kayıtlı extractor | ✅ v39 |
| **Hdvid** | `hdvid.tv/…` | `hdvid.` | kayıtlı extractor | ✅ |
| **Hexload** | `hexload.com/<id>` | `hexload.` | kayıtlı extractor (POST /download) | ✅ |
| **Google Drive** | `drive.google.com/file/d/<id>` | `drive.google` `docs.google` | yerel: playback API → `progressiveTranscodes[]`; yoksa uyarı formu | ✅ v30 |
| **Voe** | `voe.sx/e/<id>` | `voe.` | uygulamanın extractor katmanı (`loadExtractor`) | ⚠️ uygulama sürümüne bağlı |
| **Filemoon** | `filemoon.sx/e/<id>` | `filemoon` | extractor katmanı (JS şifreli) | ⚠️ uygulama sürümüne bağlı |
| **MixDrop** | `mixdrop.co/e/<id>` | `mixdrop` | extractor katmanı | ⚠️ uygulama sürümüne bağlı |
| **Mp4Upload** | `mp4upload.com/embed-<id>.html` | `mp4upload` | extractor katmanı | ⚠️ uygulama sürümüne bağlı |
| **Streamlare / Streamtape / Vudeo / YourUpload / Uqload / Dood** | çeşitli | host adı | extractor katmanı | ⚠️ uygulama sürümüne bağlı |
| **Mail.RU / Yandex** | `my.mail.ru/…`, `yadi.sk/…` | host adı | extractor katmanı (varsa) | ⚠️ doğrulanmadı |
| **Mega** | `mega.nz/file/<id>#<key>` | `mega.nz` / `mega.co` | **yok** — dosya AES-CTR ile şifreli, tarayıcı/anahtar çözümü gerekir | ❌ sonraki adım |
| **Openload/Vidmoly ölü alan adları** | `vidmoly.me`, `streamlare.com` | — | — | ❌ host ölü (26.10.2026 ölçümü) |

> **"⚠️ uygulama sürümüne bağlı" ne demek?** Bu hostlar eklentide değil,
> **CloudStream'in kendi extractor katmanında** çözülür (`loadExtractor`).
> Uygulama paketinde o extractor varsa oynar; yoksa kaynak listede görünür ama
> açılmaz. Yerel çözücüsü olan hostlar (✅) bundan bağımsızdır.

---

## 5. v39'da kodda ne değişti (tablo ↔ kod eşlemesi)

| Tablo satırı | Kod karşılığı | Dosya |
| --- | --- | --- |
| §2 kaynak butonları → hedef listesi | `TrAnimeIzleSite.playerTargets()` — iframe/embed2/downloadru2/doğrudan medya tek listede | `TrAnimeIzleSite.kt` |
| §3 embed2 sarmalı | `embedInnerUrl()` (ham + URL-çözülmüş, `&` korunur) | `TrAnimeIzleSite.kt` |
| §3 downloadru2 köprüsü | `downloadBridgeUrl()` + saf Kotlin `base64Decode()` | `TrAnimeIzleSite.kt` |
| §3 OpTraco oynatıcısı | `optracoMediaUrl()` + `resolveOptraco()` | `TrAnimeIzleSite.kt`, `TrAnimeIzleStreams.kt` |
| §4 SendVid / Vidmoly | `resolveSendvid()`, `resolveVidmoly()` | `TrAnimeIzleStreams.kt` |
| §2 captcha | `isCaptchaPage()` + `isCaptchaUrl()` + `captchaRetry()` → `solveCaptcha()` (WebView'siz) | `TrAnimeIzle.kt` |
| §2 yanlış "bot" tespiti | `isBotBody()` artık genel `/api/Captcha` metnine bakmıyor | `TrAnimeIzle.kt` |

| §2 captcha kapısının **adresten** tanınması (v40) | `isCaptchaUrl()` — `/api/CaptchaChallenge` yönlendirmesi gövde boş olsa da yakalanır | `TrAnimeIzle.kt` |
| §2 gövdenin **tek okunması** (v40) | `get`/`getXhr`/`postJson`/`postEmpty`/`postForm` → `var text = resp.text` bir kez | `TrAnimeIzle.kt` |

**Sürüm:** `TrAnimeIzle` **40**.

---

## 6. Doğrulanmayanlar / cihaz kontrol listesi

Bu ortam sitenin canlı HTML'ini okuyamadığı için şunlar **cihazda**
doğrulanmalı (hepsi log'lanıyor: `adb logcat | grep -E "kraptor_TrAnime"`):

1. `GET /arama/<sorgu>` gerçekten kart döndürüyor mu? (log: `search(...) → N kart`)
2. Bölüm sayfasında `input#EpisodeId` var mı, yoksa `animeWatch.initialize` yakalanıyor mu? (log: `episodeId=… fansub=…`)
3. `fansubSources` kaç `li.sourceBtn` döndürüyor? (log: `kaynak butonu: N`)
4. Her buton için `sourcePlayer` cevabı `embed2/?id=` taşıyor mu? (log: `toplam N akış`)
5. Captcha kapısı çıkıyor mu, HTTP çözümü geçiyor mu? (log: `captcha çözüldü → …`)
6. Hangi hostlar çözülemiyor? (log: `medya olmayan adres yayınlanmadı` / `loadExtractor hatası`)

---

## 7. Tam HTML dökümü nasıl alınır (kanıt üretimi)

`tools/tizle_dump.py` — sitenin **her adımının** ham HTML'ini diske yazar
(ana sayfa, arama, detay, bölüm, her fansub için `fansubSources`, her kaynak
için `sourcePlayer` + `preVideoAd`, her oynatıcı ve iç iframe sayfası).

```bash
python3 tools/tizle_dump.py --out tranime-dump      # tüm zincir
python3 tools/tizle_dump.py --probe                 # ek olarak akışları test eder
```

Çıktı: `tranime-dump/` (dosya adları adımı söyler) + `SUMMARY.md`.
Bu klasörü paylaştığınızda seçiciler **tahmin değil, ölçüm** olur.
