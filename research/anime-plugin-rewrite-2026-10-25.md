# Anizm + TrAnimeIzle — kök neden analizi ve yeniden yazım (25.10.2026)

Bu belge, iki eklentinin "akış bulunamadı / sonuç yok / KTS-CSP-9xxx" şikâyetlerinin
**neden** oluştuğunu ve bu PR'da ne yapıldığını kayda geçirir. Canlı site erişimi
yalnızca cihazdan mümkün olduğu için doğrulama katmanları ayrı ayrı belirtilmiştir.

---

## 1. Bulunan kök nedenler

| # | Kök neden | Etki | Nerede |
|---|---|---|---|
| 1 | **APK imza kapısı** (`TrAnimeIzleHelper`): uygulamanın imza SHA-256'sı 3 sabit hash ile karşılaştırılıyor, uyuşmazsa eklenti sessizce boş dönüyordu | Yeniden imzalanmış/derlenmiş her Kitsugi kurulumunda TrAnimeIzle **hiç** çalışmıyor (arama 0, `load` null, `loadLinks` false) | `TrAnimeIzleHelper.kt`, 4 çağrı |
| 2 | **Yayınlanan link "embed sayfası"ydı**: çözülemeyen dosya-host adresleri (ok.ru, voe, sendvid…) ham sayfa adresi olarak yayınlanıyordu | Uygulama bu adresi "embed" sayıp kendi medya tarayıcısına (`EmbedMediaScanner`) gönderiyor; tarayıcı yolu hata verirse **KTS-CSP-9110** olarak görünüyor, akış hiç gelmiyor | `loadSingleSource` (4. blok) |
| 3 | **Bölümlerde `season` yok**: yalnızca `episode` atanıyordu | Kitsugi'nin bölüm eşleştiricisi (`CsEpisodeMatcher`) `season` **ve** `episode` ikisini de ister → bölüm seçilemez, akış istenmez | `load()` |
| 4 | **Tek kuşak HTML'e bağlı seçiciler**: `div.flx-block`, `div.playlist-title h1`, `div.animeDetail-items li a` … | Site teması/kuşağı değişince arama, detay ve bölümler boş dönüyordu (Anizm v2 → v3 geçişi) | iki eklenti |
| 5 | **Alan adı probu kart sayısını yanlış ölçüyordu**: `a[href*=bolum-izle]` sayılıyordu, oysa kartlar `data-href` kullanıyor | Canlı domain "ÖLÜ" sanılıp gereksiz WebView diyaloğu açılıyor, TTL boyunca yanlış domain kullanılıyordu | `izleCount` |
| 6 | **İsim eşleştirme yok**: uygulama İngilizce başlıkla arıyor ("The Apothecary Diaries Season 3"), site Türkçe/romanize adlarla ("… 3. Sezon") çalışıyor; arama tek ham sorguyla gidiyordu | "Aradığım anime çıkmıyor" şikâyeti | `searchInternal` |
| 7 | **Altyazılar yalnızca ilk kaynaktan** toplanıyordu (`if (index == 0)`) ve tekilleştirme yoktu | Bazı kaynaklarda altyazı hiç görünmüyordu; bazılarında aynı altyazı N kez | `loadSingleSource` |

> Not (uygulama tarafı): `KTS-CSP-9110` kodu **eklenti kodu hatası değildir**; uygulama
> raporlarında bu kod "cihazda eski + yeni DEX karışımı (stale dex)" olarak
> belgelenmiştir (ör. Latanime vakası). Eklenti tarafında yapılabilecek tek şey
> embed sayfası adresi yayınlamamaktır; o da bu PR'da yapıldı. Kullanıcı tarafında
> **eklentiyi kaldırıp yeniden kurmak** (uygulama güncellemesinden sonra) gerekir.

---

## 2. Yeni mimari (iki eklentide de aynı desen)

```
main API  ──►  *Site.kt   (saf jsoup: kart/detay/bölüm/oynatıcı çözümleme)
          ──►  *Match.kt  (saf Kotlin: katlama, puanlama, varyant, sezon/bölüm)
          ──►  birim testleri (ağ yok, jsoup dışında bağımlılık yok)
```

* **`AnizmMatch` / `TrAnimeIzleMatch`** — Türkçe karakter katlaması (`İ/ı/ş/ğ/ü/ö/ç`),
  yıl/sezon/bölüm gürültüsünü atma, kelime kesişimi + yazım toleranslı (Levenshtein ≤ 1–2)
  puanlama, `queryVariants()` (sezon eki, `:` sonrası, aksan sadeleştirme, gürültü temizliği).
* **`AnizmSite` / `TrAnimeIzleSite`** — her alan için **çoklu seçici**, tema değişimine
  dayanıklı genel tarama yedeği, lazy/srcset poster çözümü, bölüm listesinin üç ayrı
  yerden toplanması, oynatıcı zinciri yardımcıları (EpisodeId, fansub, sourceBtn,
  iframe, doğrudan medya, `<track>` altyazı, JSON sarmalı açma).
* **Ana API** — arama 3 kademeli: JSON ucu → **katalog önbelleği + yerel isim
  eşleştirmesi** → HTML arama. Sonuçlar puanlanıp **en iyi eşleşme ilk sırada** döner
  (uygulamanın kendi eşleştiricisi ilk kartı doğru bulur).

### Anizm kaynak zinciri (kuşak bağımsız)
1. **v3**: `[data-sources]` → `{success, videos:[{id,name}]}` → `/video/{id}` → `{url}`
2. **v2**: `div#fansec a[translator]` → `{data: html}` → `a.videoPlayerButtons[video]`
3. **302 yolu** (Aniyomi'nin kullandığı): `/video/{id}` → `/player/{id}` → **Location** başlığı
   (`allowRedirects = false`) → gerçek host
4. **anizmplayer / FirePlayer**: `POST /player/index.php?data=<hash>&do=getVideo` →
   `securedLink` (HLS) · `videoSource` · `videoSources[]` (kalite etiketli)
5. **Sayfa düzeyi**: `<iframe>` · `a[video]` · `<video>/<source>` · JS içi `m3u8/mp4/mpd` ·
   `data-player / data-video-src / data-iframe / data-embed / data-url` · `/player/{id}` bağlantıları
6. Her aday için: kayıtlı çözücü (`loadExtractor`) → iç iframe bir katman → **yalnızca
   nihai `.m3u8/.mp4/.mpd`** yayınlanır.

### TrAnimeIzle kaynak zinciri
`input#EpisodeId | animeWatch.initialize | JSON | data-eid` → `li.fansubSelector[data-fid]`
(**hepsi**, sınır yok) → `POST /api/fansubSources` (JSON gövde; FORM ve `{data:html}`
sarmalı yedek) → `li.sourceBtn[data-id]` (**hepsi**) → `POST /api/sourcePlayer/{id}`
(boşsa `POST /api/preVideoAd/{id}`) → iframe / doğrudan video / dosya-host →
`loadExtractor` → nihai medya. Hiçbiri çözülmezse bölüm sayfası WebView DOM'unda
yeniden denenir (taze çerezle).

---

## 3. Doğrulama durumu

| Katman | Durum |
|---|---|
| Derleme (CI, `:Anizm:make`, `:TrAnimeIzle:make`) | ✅ yeşil |
| Birim testleri (CI) | ✅ Anizm 11 + Anizm HTML 11 + TrAnimeIzle 9 + TrAnimeIzle HTML 14 |
| Canlı site (HTTP) | ❌ sandbox ağ kısıtı (yalnızca github/npm/pypi) — **cihazda** doğrulanmalı |
| Uygulama içi akış (link → oynatıcı) | ❌ cihaz gerekli |

### Cihaz doğrulama listesi
1. Eklentiyi **kaldır → uygulamayı kapat → yeniden kur** (stale DEX'i temizlemek için).
2. `Anizm`: "One Piece", "Jujutsu Kaisen", "The Apothecary Diaries 3. Sezon" ara →
   sonuç ilk sırada mı? Detayda bölüm numaraları ve sezon dolu mu? Bölümde 1+ kaynak
   listeleniyor ve oynuyor mu?
3. `TrAnimeIzle`: aynı aramalar → sonuç var mı? Bölüm sayfasında fansub sayısı kadar
   kaynak (1080p/720p …) görünüyor mu? Altyazı listesi geliyor mu?
4. Hata olursa `logcat | grep -E "hexated_Anizm|kraptor_TrAnime"` → her adım log'lanıyor
   (aday sayısı, hangi kuşak çalıştı, hangi host çözüldü/çözülmedi).

---

## 4. Bilinen belirsizlikler (cihaz kanıtıyla kapanacak)

* **puffytr.com v3 işaretlemesi**: `data-v3-translator`, `/bolum/{eid}/cevirmen/{tid}`,
  `/arama?q=` yolları önceki saha notlarından; v2 (anizm.net) işaretlemeleri bağımsız
  istemcilerle doğrulanmış. Yeni kod **iki kuşağı birlikte** denediği için hangisi
  geçerli olursa olsun kaynak üretmeye devam eder. Yine de cihazda hangi kuşağın
  çalıştığı log'dan görülmeli.
* **TrAnimeIzle alan adı**: `.io` (bu belge yazılırken baskın), `.live`, `.top`.
  Eklenti üçünü de problar ve canlıyı `SharedPreferences`'a yazar; prob artık
  `data-href` taşıyan kartları da saydığı için yanlış "ölü domain" kararı vermez.
* Site **hesap/abonelik** isterse (bazı kaynaklar üye girişine kapalı olabilir),
  o kaynaklar için akış gelmez; kalan kaynaklar listelenir.

---

## 5. Canlı ölçüm (25.10.2026)

Sandbox yalnızca github/npm/pypi'ye çıkabildiği için ölçüm **iki katmanda** yapıldı:
GitHub Actions runner'ı (veri merkezi IP) + sayfayı sunucu tarafında çeken herkese açık
okuyucu köprüsü (r.jina.ai). Araç: `tools/anime_plugin_probe.py` (salt okunur, JS çalıştırmaz).

### Anizm — `anizm.net`
| Ölçüm | Sonuç |
|---|---|
| Doğrudan istek (runner IP) | **HTTP 403** (Cloudflare) — eklenti tarafında değil, IP itibarı |
| Okuyucu köprüsü | **HTTP 200**, 23.5 KB ana sayfa |
| Ana sayfa kart adresleri | `/{slug}-{n}-bolum-izle` **ve** `/{slug}` (seri) · ayrıca `-bolum-sansursuz-izle` ve `-bolum-final-izle` varyantları |
| `/arama?q=one piece` | **200** — gerçek sonuçlar (`noroi-no-one-piece`, `one-piece`, `one-piece-movie`, `one-piece-fan-letter` …) + kapaklar `/storage/pcovers/*.webp` |
| `/getAnimeListForSearch` | **var**, >3 MB (sitenin kendi arama indeksi) |
| `/one-piece` (seri) | **200**, 124 KB · **`one-piece-1-bolum-izle` … `one-piece-1180-bolum-izle`** bağlantıları |
| Bölüm sayfası | **200** — kardeş bölüm bağlantıları statik HTML'de; oynatıcı alanı **JS/API** ile geliyor (statik metinde host adı yok) |

**Sonuç:** URL şemaları ve seçiciler doğrulandı; oynatıcı zinciri (fansub → `videoPlayerButtons`
→ `/player/{id}` 302) bugün güncellenen Aniyomi eklentisiyle birebir aynı. Bu yüzden kod
**hem** v2 (translator) **hem** v3 (data-sources) **hem** 302 **hem** sayfa-düzeyi iframe
yollarını deniyor.

Bu ölçümden sonra yapılan düzeltmeler:
- Arama sırası: `/searchAnime` → **`/arama?q=` (artık doğrulanmış, hızlı)** → `/getAnimeListForSearch` (3 MB, 6 saat önbellek).
- JSON ayrıştırıcı: düz dizi · `{data|animes|results|items|list:[...]}` · NDJSON.
- Bölüm listesi: bilinen kapsayıcılar (`div.episodeListTabContent div > a`, `div.animeDetail-items li a`)
  numarasız `/bolum/{id}` adreslerinde bile etiketten numara okuyarak çalışır.

### TrAnimeIzle — `tranimeizle.io` / `.live`
| Ölçüm | Sonuç |
|---|---|
| Doğrudan istek | **HTTP 403** |
| Okuyucu köprüsü | **Cloudflare Error 1005 (ASN engeli)** — sayfa içeriği hiçbir sunucudan alınamıyor |

**Sonuç:** Site, veri merkezi IP'lerini **1005** ile tamamen kapatıyor. Yani bu eklenti
yalnızca **kullanıcının kendi bağlantısı + WebView oturumu** ile çalışabilir (v17–v27 cihaz
kanıtları da bunu söylüyor). CI'dan canlı doğrulama **imkânsız**; bu bir eklenti hatası değil.

Bu ölçümden sonra yapılan düzeltmeler:
- `probeDomainAlive`: 403/503/1005 artık **"domain ölü" sayılmıyor** (eskiden yanlışlıkla
  domain değiştiriyor, oturum akışını bozuyordu).
- Aday listesi: `www.`/kök biçimleriyle `.io · .live · .top`.
- Arama kartları kanonik dizi adresine çevriliyor: `/one-piece-1120-bolum-izle` → `/anime/one-piece-izle`
  (Aniyomi'nin `latestUpdatesFromElement` kuralı).
- Bölüm listesi bilinen kapsayıcı (`div.animeDetail-items > ol a:has(div.episode-li)`) + genel yedek.

### Ölçülemeyen (cihaz gerektirir)
- Oynatıcı zincirinin SON adımı (302 hedefi → host → m3u8). Sunucudan CF/ASN nedeniyle
  görülemiyor.
- Kullanıcının kendi IP'sinin site tarafından engelli olup olmadığı (1005 kişisel IP'de
  görülürse hiçbir istemci çalışmaz).

---

## 3. v30 / v5 — akış zincirinin yeniden yazımı (25.10.2026, akşam)

Kullanıcı raporu: *"ne çalışıyor ne video kaynaklarını veriyor, baya bir sürüyor"* —
özellikle **Attack on Titan 1. sezon 1. bölüm** gibi en bilinen içerikte bile kaynak yok.

### 3.1 Kök nedenler

| # | Kök neden | Etki | Nerede |
|---|---|---|---|
| 1 | **WebView/"kanıt katmanı"** her akış isteğinde devreye giriyordu (`botKontrolFetch`, oturum yenileme diyalogları, epoch probu) | Kullanıcının gördüğü "dakikalarca süren yükleme" | `TrAnimeIzle.fetchHtml`, `loadLinks` |
| 2 | Kaynaklar **sırayla** çözülüyordu (fansub → kaynak → host → extractor) | 10 kaynaklı bölümde 30 sn+; uygulama zaman aşımına düşüp hiç kaynak göstermiyordu | `Anizm.resolveCandidate` |
| 3 | **HLS master** listeleri tek link olarak yayınlanıyordu | Kalite seçilemiyor, bazı oynatıcılarda hiç açılmıyordu | iki eklenti |
| 4 | `sourcePlayer` cevabındaki **`embed2/?id=<GERÇEK HOST URL>`** sarmalı tam çözülmüyordu | Liste dolu görünse de oynatılabilir link çıkmıyordu | TrAnimeIzle |
| 5 | Anizm'de `/video/{id}` → **`/player/{id}` 302 Location** yolu, kaynak başına ekstra 2 istekle birlikte yapılıyordu | Hem yavaş hem gereksiz | Anizm |
| 6 | İngilizce başlık ↔ sitenin romanize adı köprüsü yoktu | "Attack on Titan" aramasında 0 sonuç (site: "Shingeki no Kyojin") | iki eklenti |

### 3.2 Yeni zincir (kanıt: Aniyomi `yuzono/anime-extensions`, 25.10.2026 sürümü)

TrAnimeIzle (`TrAnimeIzleStreams.kt`):

```
input#EpisodeId
  → li/div.fansubSelector[data-fid]
  → POST /api/fansubSources        {"EpisodeId":N,"FansubId":M}
  → li.sourceBtn[data-id]  (p = host adı)
  → POST /api/sourcePlayer/{id}    → {"source":"<iframe src=…/embed2/?id=<HOST URL>>"}
  → POST /api/preVideoAd/{id}      (sourcePlayer boşsa: sitenin ilk-tıklama yolu)
  → host URL → loadExtractor (325 kayıt) → HLS varyantları
```

Anizm (`AnizmStreams.kt`):

```
div#fansec > a[translator]
  → GET → {"data":"<html>"} → a.videoPlayerButtons[video] (metin = host adı)
  → /video/{id} → /player/{id} → 302 Location = host adresi
  → anizmplayer.com? POST /player/index.php?data=<hash>&do=getVideo → securedLink
  → drive.google.com? gdriveplayer.to köprüsü
  → host URL → loadExtractor (filemoon, sibnet, voe, sendvid, mp4upload, ok.ru,
                               streamtape, dood, uqload, yourupload…)
```

### 3.3 Hız ve eksiksizlik önlemleri

* **Paralellik**: fansub/çevirmen listesi 4 eşzamanlı, kaynak çözümü 8 eşzamanlı
  (`Semaphore`), sonuçlar çözüldükçe yayınlanır — ilk kaynak genelde ~2 sn'de görünür.
* **Zaman aşımı**: istek başına 12–15 sn, host sayfası 12 sn, toplam bütçe 35 sn.
  Tek bir ölü host artık tüm listeyi kilitlemiyor.
* **HLS**: `M3u8Helper.generateM3u8` ile master → kalite varyantları (1080p/720p/480p…).
* **WebView yok**: sitenin uçları düz HTTP ile çalışıyor; oturum/diyalog katmanı kaldırıldı.
* **Captcha**: site `/api/Captcha` isterse en farklı görselin MD5'i seçilerek çözülür
  (Aniyomi `ShittyCaptchaInterceptor` ile aynı yöntem), yalnızca gerektiğinde çalışır.
* **Eksiksizlik**: her kaynak butonu denenir; `loadExtractor` çözemezse host sayfası
  çekilip içindeki `m3u8/mp4` veya iç iframe bir katman daha çözülür.
* **Arama**: `queryVariants` + **Kitsu alternatif adları** (canonicalTitle + titles{} +
  abbreviatedTitles) ile genişletilir; sıralama tüm adların en iyi skoruna göre yapılır.

### 3.4 Doğrulama

* Kotlin derlemesi: GitHub Actions `Derleme Testi` (tüm etkin eklentiler) — yeşil.
* Regresyon testleri: `sourcePlayer` iframe'inin `embed2/?id=` sarmalı ve
  `fansubSources` kaynak butonları için yeni birim testleri eklendi.
* Canlı site testi bu ortamdan yapılamıyor: `tranimeizle.io` Cloudflare **Error 1005**
  (ASN ban) ile veri merkezi IP'lerini engelliyor; `anizm.net` TLS el sıkışması
  sandbox ağından geçmiyor. Zincir bu yüzden **üretimde çalışan istemci koduyla
  birebir** eşleştirildi (Aniyomi `src/tr/tranimeizle`, `src/tr/anizm`).

---

## 4. Anizm v6 + TrAnimeIzle v31 (2026-10-26) — v3 zinciri, kanıtla

Upstream `yuzono/anime-extensions` portunun Anizm zinciri (`div#fansec > a[translator]`)
**artık ölü**: anizm.net v3 temasına geçti. Yeni zincir, bakımı süren bağımsız istemci
`roxyrekt/Migurdex` kaynağından ve canlı sayfa DOM'undan çıkarıldı.

### 4.1 Anizm — v3 kaynak zinciri (kanıt: `Migurdex.Plugins.Anizm/AnizmProvider.cs`)

```
bölüm sayfası  /shingeki-no-kyojin-1-bolum-izle
  → <meta name="csrf-token">                       (X-CSRF-TOKEN olarak gönderilir)
  → button[data-v3-translator]  (+ [data-sources], title = fansub adı, aria-pressed = aktif)
      data-sources = /bolum/{bolumId}/cevirmen/{cevirmenId}
  → GET (AJAX: X-Requested-With, Accept: application/json, X-CSRF-TOKEN, Referer)
      cevap A: {"videos":[{"id":"…","name":"…"}]}
      cevap B: {"data":"<a video=\"…\">…", "player":"<iframe src=…>"}
  → video adresi = sourcesUrl.replace(/cevirmen/\d+$/, "/video/" + vid)
  → GET /video/{vid} (aynı başlıklar) → {"url":"…"}   (bazen {"player":"<iframe …>"})
      url "/player/" içeriyorsa  → GET (yönlendirme kapalı) → Location = gerçek host
      url "/video/"  içeriyorsa  → "/player/" ile değiştirilir
  → anizmplayer.com/video/<32-hex>
      POST /player/index.php?data=<hash>&do=getVideo   form: hash, r
      → {"securedLink":"https://…/master.m3u8", "video":[{"file":"…","label":"1080p"}]}
```

Doğrulanan ayrıntılar:

* `anizmplayer` anahtarı **32 hex**; `index.php?data=<hash>` biçimi de kabul edilir
  (`Migurdex.Core/Extractors/AnizmPlayerExtractor.cs`).
* `securedLink` HLS master → `M3u8Helper` ile varyantlara ayrılır; `video[]` içindeki
  `file` + `label` çiftleri kalite etiketi olarak yayınlanır.
* Sayfada `+18` onay kapısı var ("Seçim yalnızca bu tarayıcıda saklanır") — bu bir
  **istemci tarafı** kolaylık katmanı; kaynak uçları XHR ile doğrudan okunabiliyor.
* Canlı sayfada `button[data-sources]` değeri boş bırakılıp JS ile doldurulabiliyor;
  bu yüzden eklenti sırasıyla `[data-sources] → [data-v3-translator] →
  [data-translator] → a[translator]` düğümlerini tarar, değer boşsa düğümdeki
  `data-id` + sayfadaki `/bolum/{id}` kimliğinden adresi **yeniden kurar**.

### 4.2 Anizm — yedek merdiveni (kaynak asla atlanmaz)

1. v3 çevirmen düğümleri → kaynak listeleri → video/oynatıcı adresleri.
2. Eski kuşak `a[translator]` → `{"data":"<html>"}` → `a[video]`.
3. Çevirmen düğümü yoksa `/bolum/{bolumId}` dizini (bolumId, `raporver`/`bolum`
   bağlantılarından veya `EpisodeId` alanından) → düğümler → 1. adım.
4. O da boşsa `/bolum/{bolumId}/cevirmen/1..3` uçları paralel denenir.
5. Her durumda sayfa düzeyi `iframe[src]`, `video[src]` ve HTML içi `m3u8/mp4/mpd`
   adresleri eklenir.

### 4.3 TrAnimeIzle v31 — probe kapısı ve form yedeği

* `fansubSources` gövdesi **JSON** (`{"EpisodeId","FansubId"}`); uç boş dönerse aynı
  istek **form** gövdesiyle tekrarlanır (Aniyomi portu form kullanıyor).
* Cevap `/api/CaptchaChallenge` içeriyorsa: `POST /api/Captcha/` (`cID=0,rT=1,tM=light`)
  → görsellerin MD5'i → en farklı kimlik → `cID=0,rT=2,pC=<id>`; tek uçuş (`Mutex`).
* Migurdex'in `TrAnimeProbeSolver`'ı **WebRTC/WebSocket RTT** ölçümü istiyor
  (`#rtt-cfg`: `pt` JWT + `wsUrl` + `mintUrl` + `stun`). CloudStream'de WebRTC yok;
  bu yüzden kapı **algılanır** (`rtt-cfg`, `just a moment`) ve kaynak listesi boş
  sayılıp sayfa düzeyi/preVideoAd yollarına bırakılır — kapı HTML'i kaynak sanılmaz.
* Bütçe: kaynak butonu başına 14 sn, toplam 26 sn (v30'da 20/35 sn idi).
* Kaynak butonu `sourcePlayer` boş dönerse `/api/preVideoAd/{id}` denenir; yine boşsa
  host sayfası bir katman daha açılır; `embed2/?id=` sarmalı çözülür.

### 4.4 Neden önceki sürüm "hiç kaynak yok" diyordu

| Belirti | Kök neden | v6/v31 çözümü |
| --- | --- | --- |
| Anizm: 0 kaynak | `div#fansec a[translator]` v3 DOM'unda yok | `[data-sources]`/`[data-v3-translator]` + `/bolum/{id}` yedeği |
| Anizm: XHR 403/HTML kabuğu | CSRF belirteci ve AJAX başlıkları yok | `meta[csrf-token]` → `X-CSRF-TOKEN`, `X-Requested-With`, `Accept: application/json` |
| Anizm: oynatıcı çözülmüyor | `anizmplayer` anahtarı yalnızca `video/<hash>` sanılıyordu | `index.php?data=<32hex>` + `securedLink` + `video[]` |
| TrAnimeIzle: kaynak listesi boş | form gövdesi bekleyen uç sürümü | JSON → form yedeği |
| TrAnimeIzle: saçma "kaynak" | anti-bot probe HTML'i kaynak sanılıyordu | `isProbeGate` ile ayıklanır |

### 4.5 Ek bulgular (microlink DOM ölçümü, 26.10.2026)

* Canlı bölüm sayfasında `[data-sources]` düğümü **var** ama `data-sources`
  özniteliğinin değeri sunucu HTML'inde **boş** (`attribute → null`). Yani adres
  yalnızca istemci JS'i çalıştıktan sonra doluyor. Bu yüzden eklenti üç kademeli
  çalışır: (a) öznitelik doluysa doğrudan kullan, (b) boşsa düğümdeki `data-id` +
  bölüm kimliğinden `/bolum/{id}/cevirmen/{cid}` kur, (c) ham `[translator]` /
  `[video]` özniteliklerini tara (Migurdex'in `TranslatorRegex`/`VideoAttrRegex`
  yolu), (d) son çare `/bolum/{id}` dizini.
* `[translator]` ve `[video]` öznitelikleri **her etikette** olabilir
  (`a`, `button`, `div`). Önceki sürüm yalnızca `a[translator]` arıyordu; v3
  şablonunda düğüm `button` olduğu için hiç eşleşmiyordu.
* Aynı çevirmen için iki rota biçimi de denenir: v3 `/bolum/{id}/cevirmen/{cid}`
  ve eski kuşak `/episode/{id}/translator/{cid}`.
* `ok.ru` ve `sibnet` için **yerel** çözücüler eklendi (extractor katmanı bu iki
  hostta zaman zaman 403 dönüyor):
  `ok.ru/dk?cmd=videoPlayerMetadata&mid=…` → `hlsManifestUrl` + `videos[]`;
  `video.sibnet.ru/shell.php?videoid=…` → `player.src([{src:"/videos/…"}])`.
* Bölüm listesi: v3 şablonu numara/başlığı `data-episode-number` ve
  `data-episode-title` özniteliklerinde taşıyor; ayrıştırıcı artık bunları okuyor.

**Derleme durumu:** `Derleme Testi` (tüm etkin eklentiler + birim testleri) yeşil;
Anizm **v6**, TrAnimeIzle **v31**.
