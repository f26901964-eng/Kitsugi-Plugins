# Dump Doğrulama Raporu — DiziBox / DiziPal / AnimeciX
**Tarih:** 2026-11-04 · **Kaynak:** kullanıcının Google Drive klasörleri (12 dosya)
**Yöntem:** her iddia, indirilen gerçek sayfa kaynağı üzerinde Python (BeautifulSoup / regex / PyCryptodome) ile
eklentinin **kendi algoritması taklit edilerek** yeniden üretildi. Tahmin yok.

---

## 0. Dump envanteri (dosya → sayfa eşlemesi)

| # | Dosya | Boyut | `<title>` / canonical | Ne olduğu |
|---|-------|-------|----------------------|-----------|
| 24 | `message (24).txt` | 986 KB | `DiziBOX - Yabancı Dizi izle` · `/` | **DiziBox ana sayfa** (`class="home blog"`) |
| 25 | `message (25).txt` | 863 KB | `the mentalist - DiziBOX` · `/` | **DiziBox arama** (`class="search search-results"`, `?s=the mentalist`) |
| 26 | `message (26).txt` | 915 KB | `The Mentalist izle` · `/diziler/the-mentalist-izle-hd/` | **DiziBox dizi sayfası** (`single-diziler`) |
| 27 | `message (27).txt` | 887 KB | `The Mentalist 1.Sezon 1.Bölüm` · `/the-mentalist-1-sezon-1-bolum-izle/` | **DiziBox bölüm sayfası** (`single-post`) |
| 28 | `message (28).txt` | 235 KB | `Dizipal \| Dizipal Orijinal` · `dizipal1594.com` | **DiziPal ana sayfa** |
| 29 | `message (29).txt` | 89 KB | `"the mentalist" arama sonuçları` · `/arama-yap` | **DiziPal arama** |
| 30 | `message (30).txt` | 296 KB | `Dizipal The Mentalist 1080P…` · `/series/the-mentalist` | **DiziPal dizi sayfası** |
| 31 | `message (31).txt` | 155 KB | `Dizipal The Mentalist 1.Sezon 1.Bölüm` · `/bolum/the-mentalist-1x1` | **DiziPal bölüm sayfası** |
| 32 | `message (32).txt` | 68 KB | `AnimeciX - Türkçe Anime…` | **AnimeciX ana sayfa** — SPA kabuğu |
| 33 | `message (33).txt` | 68 KB | aynı | **AnimeciX arama** — SPA kabuğu |
| 34 | `message (34).txt` | 68 KB | aynı | **AnimeciX dizi** — SPA kabuğu |
| 35 | `message (35).txt` | 68 KB | aynı | **AnimeciX bölüm** — SPA kabuğu |

> **Uyarı 1:** Dump'lar ham HTML'dir; "message (N).txt" sarmalayıcısı yoktur. Yalnızca CRLF satır sonu vardır.
> **Uyarı 2:** `32`–`35` dosyalarının **md5'i birebir aynıdır** (`2b9b3ec5bee2bc926a42e46f265258e6`).
> Yani 4 farklı sayfa değil, **tek bir boş SPA kabuğu** 4 kez kaydedilmiş. Bkz. §3.

---

## 1. DiziBox — domain ve tel seviyesi doğrulaması

### 1.1 Domain: `www.dizibox.live` **DOĞRU** — değişiklik gerekmiyor ✅

Önceki raporda (DBX-1) "tek domain" riski işaretlenmişti. Dump bunu **çürütüyor**: `www.dizibox.live`
hâlâ canlı ve tek otorite. RSS/atom/pingback/xmlrpc/canonical hepsi aynı hostu gösteriyor.

### 1.2 `dbxu` çerezi **UYDURMA** — kaldırılmalı ❌

`DiziBoxWire.diziboxCookies()` üç çerez gönderiyor. Dump'taki gerçek JS:

```js
function setCookie(e,t,o){...}
"true"!=getCookie("isTrustedUser") && (
    setCookie("LockUser","true",1),
    setCookie("isTrustedUser","true",30)
);
```

- `LockUser=true`, `isTrustedUser=true` → **gerçek**, site kendi head'inde set ediyor. ✅
- `dbxu=<nowMillis>` → **sitede böyle bir çerez yok** (0 eşleşme). Gövdede uydurulmuş. ❌

Risk: sunucu bilinmeyen çerezi oturum bozucu sayarsa (bot/parmak izi) tam tersi etki yapar.
**Düzeltme:** `diziboxCookies()` içinden `dbxu` çıkarılmalı; `LockUser`+`isTrustedUser` kalsın.

---

## 2. DiziBox — ana sayfa / arama / dizi / bölüm davranışı (ölçüm)

### 2.1 Ana sayfa: `getMainPage` — **tam sağlıklı** ✅

`mainPageOf` 9 vitrin tanımlıyor. Dump (24) üzerinde eklentinin **kendi seçicileriyle** üretilen kart sayıları:

| mainPage satırı | seçici kökü | kart |
|---|---|---|
| Beklenen / Eklenen Diziler | `#recommended_series` | **10** |
| Dikkat Çeken Yeni Diziler | `section#new-series` | **6** |
| Popüler Dizilerden Son Bölümler | `section:has(h2:contains(Popüler…))` | **10** |
| Yeni Eklenen Bölümler | `section:has(h2:contains(Yeni…))` | **30** |
| Efsane Diziler | `section#best-series` | **12** |

Beşi de dolu; hiçbiri boş satır döndürmüyor. `hasNextPage()` `/page/N+1` bağlantısı arıyor — `tum-bolumler`,
`efsane-diziler`, `arsiv` şablonlarında sayfalama var. **Keşfet kriteri DiziBox'ta karşılanıyor.**

### 2.2 Ana sayfada **kullanılmayan** iki blok ⚠️

Dump'ta var, `mainPageOf`'ta yok:

| Blok | id | İçerik |
|---|---|---|
| Çeviri Durumu | `#translation-status` | 10 bölüm linki (subtitle çeviri durumu) |
| Önerilen Diziler | `#recommended-series` | **40** dizi linki |

`#recommended-series` ayrıca `CHROME_SELECTOR` ile **siliniyor** (satır 138) — yani bilinçli olarak dışlanmış,
ama 40 dizilik gerçek bir vitrin. Kullanıcının "Keşfet eksiksiz olsun" kriteri için **eklenmesi** gerekir.
Not: `#recommended-series` id'si `#recommended_series` (alt çizgi) ile **karıştırılmamalı** — ikisi ayrı blok.

### 2.3 Arama: **çalışıyor ama %97'si atılıyor** ❌

Dump (25) — `?s=the mentalist`. Eklentinin `CARD_SELECTOR` + `parseCardElement` + `parseSearchCards` akışı
birebir taklit edildi:

```
eşleşen kart        : 30
  ├─ article.detailed-article (dizi) :  1
  └─ article.article-episode-card    : 29
isEpisode sınıflandırma hatası : 0   (seçici mantığı doğru)
parseSearchCards çıktısı      : 1   ← diziler varsa bölümler TAMAMEN atılıyor
```

`parseSearchCards()` (satır 373-377):
```kotlin
val seriesCards = allCards.filterNot { it.isEpisode }
return if (seriesCards.isNotEmpty()) seriesCards else allCards
```

**Sonuç:** "the mentalist" aramasında kullanıcı **tek** sonuç görüyor; sitenin listelediği 29 bölüm
erişilemez. Bu, kullanıcının *"arama sonuçları eksiksiz ve doğru"* kriterinin **doğrudan ihlali**.

Bölüm kartları doğru ayrıştırılıyor (kanıt):
```
href  : /the-mentalist-7-sezon-12-bolum-final-izle/
title : "The Mentalist 7.Sezon 12.Bölüm Final"
poster: …/the-mentalist-220x140.jpg
```
Yani tek sorun `parseSearchCards`'ın agresif filtresi. **Düzeltme:** dizi + bölüm karışık dön
(bölümler `newEpisodeSearchResponse` ya da diziye yönlendirilmiş başlıkla), ya da en azından dizi yoksa
bölümleri döndürme şartını kaldır.

### 2.4 Dizi sayfası: **tam sağlıklı** ✅

Dump (26) üzerinde `parseSeriesDetail`:

```
div.tv-overview          : var
başlık                   : "The Mentalist izle"  → temizleniyor
poster (figure#main-cover): …/the-mentalist-200x290.jpg
konu (div.tv-story)      : var
yıl (a[href*='/yil/'])   : var
imdb (span.label-imdb b) : 8.1
türler (a[href*='/tur/']) : var
oyuncular                : var
#seasons-list a          : 7 sezon   → 1..7
section#category-posts article.grid-box : 23 bölüm (aktif sezon = 1)
hover sınıfı             : 1. Sezon  → inactiveSeasonUrls = 2..7 (6 sezon)
```

`load()` 6 sezonu `Semaphore(6)` + `SEASON_FETCH_TIMEOUT_MS=10s` ile paralel çekiyor.
**7 sezonun tamamı** (149 bölüm) toplanabilir durumda. Bu kriter **karşılanıyor**.

### 2.5 Bölüm sayfası: oynatıcı **sağlıklı**, altyazı **eksik** ⚠️

Dump (27) üzerinde:

```
select.woca-linkpages-dd option : 3
   DBX Pro  (value yok, selected, class=woca-current-page)
   Moly+    → /the-mentalist-1-sezon-1-bolum-izle/2/
   Odnok    → /the-mentalist-1-sezon-1-bolum-izle/3/
#video-area iframe             : 1  → /player/king/king.php?v=c4fead…
<!--baslik:DBX Pro-->          : aktif etiket okunuyor ✅
archive-box → series           : /diziler/the-mentalist-izle-hd/  ✅
related-posts                  : 22 bölüm (yalnız 1. sezon)
```

`playerTabs()` / `playerIframes()` / `activePlayerLabel()` / `archiveBoxLink()` **hepsi dump'ta doğrulandı**.

### 2.6 Altyazı: sitede **dublaj yok, altyazı var** — eklenti hiçbirini okumuyor ❌

Tüm DiziBox dump'larında:

| Belirteç | (24) ana | (25) arama | (26) dizi | (27) bölüm |
|---|---|---|---|---|
| `altyazi.png` | 40 | 29 | 0 | **1** |
| `dublaj.png` | **0** | **0** | **0** | **0** |
| `language-flag` / `subtitle-flag` | 40 | 29 | — | 1 |
| `alt='Dil Seçeneği'` | 40 | 29 | — | 1 |

Bölüm sayfasındaki bayrak:
```html
<img src='https://www.dizibox.live/altyazi.png' alt='Dil Seçeneği' class='subtitle-flag noselect'>
<span itemprop="name">The Mentalist</span>
<span class='tv-title-episode'>1.Sezon 1.Bölüm</span>
```

**Bu, bölümün altyazılı olduğunu gösteren tek sinyal.** Eklenti bunu **hiçbir yerde** okumuyor:
`grep -c "altyazi\|subtitle-flag\|language-flag"` → `DiziBox.kt` **0**, `DiziBoxPlayerParser.kt` **0**
(yalnız `selectPosterImage`'da poster *dışlama* filtresi olarak geçiyor).

**DiziBox dublaj sunmuyor** (site Türkçe altyazılı yabancı dizi arşivi). Bu yüzden kriter 3'ün DiziBox
karşılığı: **"bu bölüm altyazılıdır" bilgisini taşımak** + ekstra altyazı varsa `SubtitleFile` emit etmek.
Şu an ikisi de yok.

### 2.7 Oynatıcı seçenekleri ≈ sayfa numarası (DBX-5) — dump'ta **doğrulandı** ⚠️

Üç sekmeye de aynı `king.php?v=<64 hex>` iframe'i hizmet ediyor; farklı olan yalnız yol sonu (`/`, `/2/`, `/3/`).
Bu, "DBX Pro / Moly+ / Odnok"un **ayrı oynatıcı** olduğu anlamına gelmiyor — aynı oynatıcının sayfa
numaraları olabilir. Etiketler `<!--baslik:…-->` yorumundan alınıyor, yani gerçek isimler.
**Aksiyon:** 3 sekmeyi de dene (zaten öyle), ama etiketlerin kaynak adı olarak gösterilmesi yanıltıcıysa
`"DBX Pro (1/3)"` gibi numaralandır. Kullanıcı yanlış kaynak adı görmesin.

---

## 3. DiziPal — en ağır bulgular

### 3.1 Domain **değişmiş**: `1593` → **`1594`** ❌

```
plugin  : mainUrl = "https://dizipal1593.com"
dump    : canonical = https://dizipal1594.com   (4 dump'ın 4'ünde de)
```

`mainUrl` tek parça string; `domainCandidates` **yok**. Site 1594'e geçtiği anda eklenti **tamamen ölü**.
Bu, kullanıcının "sadece belirli bir yerden açılınca çalışıyor" şikâyetinin DiziPal ayağının
**en olası kök nedeni**: 1593 hâlâ yanıt veriyorsa eski/önbellek içerik döner, vermiyorsa hiçbir şey.

### 3.2 Arama: sayfa **DOM kartı veriyor**, eklenti **kısmen** okuyor ⚠️

Dump (29) `?s=the mentalist` → **`a.tc` DOM kartı: 1** (The Mentalist). Eklentinin `parseListing`
önce `a.tc` deniyor → **1 sonuç dönüyor**. Yani arama *tamamen* kör değil.

Ama sayfa RSC'de çok daha zengin bir yapı taşıyor ve eklenti bunu kullanmıyor:

```json
"seg":"d","data":{"q":"the mentalist","tab":"hepsi",
  "results":{
    "q":"the mentalist",
    "dizi":[{"id":"dizi-8797","kind":"dizi","t":"The Mentalist","year":2008,"g":"Suç",
             "r":"8.2","hasR":true,"meta":"2008 · Suç","href":"/series/the-mentalist", …}],
    "film":[], "oyuncu":[]},
  "trending":"$30:props:children:props:popular"}
```

`results.dizi` / `results.film` / `results.oyuncu` **ayrı diziler**; her kartta `t` (ad), `year`, `g` (tür),
`r` (puan), `img`, `thumb`, `href`, `sub` var. Test:

```
eklentinin parseCards() kalıbı : "t":"…","href":"/series/…"
dump (29) eşleşme             : 0     ← alan sırası farklı (t, kind, year, g, img…, href)
dump (28) ana sayfa eşleşme   : 53
dump (30) dizi sayfası        : 6     (benzer diziler)
```

**Sonuç:** arama çalışıyor ama sitede *film* ve *oyuncu* sonuçları da varken onlar hiç görünmüyor;
`r`/`g`/`year` zenginliği kullanılmıyor. `results.*` ayrıştırıcısı yazılmalı.

### 3.3 `pageHref` **hiçbir dump'ta yok** — sayfalama kırık ❌

`DiziPalParser.pageHrefs()` `"pageHref":[` arıyor:

```
dump (28) ana sayfa  : -1
dump (29) arama      : -1
dump (30) dizi       : -1
dump (31) bölüm      : -1
```

Site `pageHref` yerine başka bir sayfalama sözleşmesi kullanıyor. Sonuç: her ana sayfa satırı
**`hasNext=false`** dönüyor → **ilk sayfadan öteye geçilemiyor**. Kullanıcının "Keşfet sayfaları sorunsuz
yüklensin" kriteri DiziPal'de **ilk sayfa** ile sınırlı. Bu, doğrulanmış bir kırık.

### 3.4 Bölüm listesi **mükemmel** ✅

`parseSeasons` dump (30) ve (31)'de **149 bölüm / 7 sezon** çıkardı; `title` alanı gerçek bölüm adı:

```
(1, 1, '/bolum/the-mentalist-1x1', 'Pilot')
(1, 2, '/bolum/the-mentalist-1x2', 'Red Hair And Silver Tape')
…
seasons: [1,2,3,4,5,6,7]
```

`parseSlots` → `mjTitle=The Mentalist`, `mjYear=2008`, `mjOverview`, `mjPoster`, `mjPlatform=CBS`,
`mjLanguage=Türkçe Altyazı`, `mjGenreText=Suç, Dram` — hepsi dolu.
`parseGenres` → `['Suç','Dram','Gizem']` ✅

### 3.5 Şifre çözme **hâlâ çalışıyor** ✅ (kripto doğrulandı)

Dump (31) gerçek `enc`/`k` değerleriyle Python'da:

```python
key = PBKDF2-HMAC-SHA512(k, salt, 999, 32)   # e59ac09a498ba638f249267ea6fe2667…
plain = AES-256-CBC(key, iv).decrypt(ciphertext)
# → '//four.dplayer82.site/iframe.php?v=911c0b8df04207b4dd145f0cb7b28031\r\r\r…'
```

`PBKDF2_ITERATIONS=999`, `PBKDF2_LENGTH=32`, AES-256-CBC, PKCS5 padding — **birebir uyuşuyor**.
Dikkat: düz metin **`\r` ile dolgulu**; Kotlin tarafındaki `.trim()` bunu temizliyor. ✅

### 3.6 `sources[]` = **altyazı + dublaj** — yapısal olarak var, eklenti yanlış kullanıyor ⚠️

Dump (31) gerçek veri:

```json
"sources":[
  {"label":"PUB+ · Türkçe Altyazı","quality":"1080P","href":"/bolum/the-mentalist-1x1","on":true,"dub":false},
  {"label":"PUB+ · Türkçe Dublaj","quality":"1080P",
   "href":"/bolum/the-mentalist-1x1?player=c9d269c6068df46ced5c81ba04b793fa","on":false,"dub":true}
],
"language":"Türkçe Altyazı","dub":false,
"enc":{…},"k":"…"
```

Test:

```
parseCards/parsePlayer ile sources[] ayrıştırma:
   sources bulundu            : 2
   ana sayfa genelinde dub:true: 32 (28), 149 (30), 150 (31)
   yalnız TEK "enc"/"k" çifti  : var (2 kaynak için 1 şifreli yük)
```

**İyi:** `DiziPalParser.parsePlayer` `sources` dizisini, `label`/`quality`/`href`/`dub` alanlarıyla
ayrıştırıyor — yapı **güncel**. `DiziPal.kt` her kaynağı dolaşıp `?player=` hash'li href'i ayrıca çekiyor.

**Kötü / eksik:** Etiket üretimi (satır 267):

```kotlin
val label = listOfNotNull(source.quality, source.label ?: payload.language).joinToString(" ")
```

→ `"1080P PUB+ · Türkçe Dublaj"` / `"1080P PUB+ · Türkçe Altyazı"`. Dublaj bilgisi **yalnızca serbest metin
etikette**; `ExtractorLink`'te yapısal `dub` alanı yok. Kullanıcı kriter 3'ü ancak etiket okuyarak karşılar.
**Düzeltme:** `dub` bayrağını `newExtractorLink`'in `name`'ine **başta** koy (`Dublaj · 1080P` / `Altyazı · 1080P`)
ve kaynak başına ayrı `ExtractorLink` üret. Ek olarak "Türkçe Dublaj" kaynakları için ayrı `source` adı kullan.

### 3.7 `loadLinks` **tamamen sıralı** — 120 s bütçesini aşan asıl neden ❌

```kotlin
for (source in sources) {          // 2 kaynak, sıralı
    fetch(sourceUrl)               // 60 s timeout
    extractFromPlayer(target)      // app.get(…) 60 s
}
```

En kötü durum: 2 kaynak × 2 istek × 60 s = **240 s** (önceki raporda 360 s hesaplanmıştı; `sources`
gerçekte 2 eleman olduğu için **240 s**). CloudStream `loadLinks` bütçesi **120 s**.
`async/awaitAll` kullanımı: **0**. → `sources` **paralel** çekilmeli (`coroutineScope` + `async`),
her istek için ayrı `withTimeoutOrNull` (öneri: 25 s).

### 3.8 `usesWebView` **bildirilmemiş** ❌

`CloudflareKiller()` kullanılıyor ama `MainAPI.usesWebView` override edilmemiş. CloudStream,
WebView gerektiren eklentiyi bu bayrakla tanır (izin/uyarı akışı). `CloudflareInterceptor` 512 KB
`peekBody` ile her yanıtta gövde okuyor — mobilde maliyetli. **Düzeltme:** `override val usesWebView = true`.

---

## 4. AnimeciX — dump yetersiz; 5 somut düzeltme yapıldı, API iddiası geri çekildi ⚠️

### 4.1 Dump'lar boş Angular kabuğu

```html
<body id="theme">
  <app-root></app-root>
  <link rel="modulepreload" href="chunk-P5V6K3CS.js"> … (10 chunk)
  <script src="polyfills-G4MZWJGN.js" type="module"></script>
  <script src="main-ZZ232L5W.js" type="module"></script>
</body>
```

Ölçüm:

| Aranan | Sonuç |
|---|---|
| `__NEXT_DATA__` / `__NUXT__` | yok / yok |
| `x-e-h` | **0** |
| `/secure` | **0** |
| `best-video` / `tau-video` | 0 / 0 |
| `api.` alt alanı | 0 |
| `<body>` uzunluğu | **676 bayt** |

Site artık **Angular 18+ SPA** (`<app-root>`, `main-ZZ232L5W.js`, `modulepreload` chunk'ları).
Sunucu tarafında içerik render edilmiyor; HTML'de **hiçbir veri yok**.

### 4.2 Eklentinin dayandığı sözleşme — **doğrulanamadı** (kalkmış DEĞİL)

`AnimeciX.kt` şunlara dayanıyor:

```
mainUrl               = https://animecix.tv
/secure/last-episodes
/secure/titles?type=series&onlyStreamable=true
/secure/search/<q>?limit=20
/secure/titles/<id>?titleId=<id>
/secure/related-videos?episode=1&season=<n>&videoId=0&titleId=<id>
/secure/best-video
header: x-e-h = <STATİK 60 karakterlik token>
```

2026-10 itibarıyla sitenin gerçek URL yapısı (dış doğrulama, GitHub issue #4290):

```
https://animecix.tv/titles/11218/shangri-la-frontier/season/1/episode/50
```

→ `/secure/titles/<id>?titleId=<id>` yok; **slug + season/episode yol segmentleri** var.
→ `X_E_H` **sabit string** (dosya satır 13). Hiçbir ana sayfa dump'ında bulunmuyor. Token üretimi
   (imza/şifreleme) istemci JS'inde; repo'daki `AnimeSiteleri` kopyasında **devre dışı** bir
   `cachedToken` + regex (`['"]x-e-h['"]\s*:\s*['"]([^'"]+)['"]`) düzeltmesi duruyor.

**Sonuç:** AnimeciX eklentisi **eski bir API sürümüne** yazılmış. Dump'lar bunu çürütüyor (boş kabuk),
dış kaynak URL değişikliğini doğruluyor. Bu eklentiyi "düzeltmek" için **yeni API'nin keşfi** gerekir;
dump'lar bu keşif için **yeterli veri içermiyor** (JS bundle'ları indirilmedi).

### 4.3 DÜZELTİLDİ — `hasQuickSearch` artık `true` ✅

`SearchViewModel.kt:233` → `(!isQuickSearch || a.hasQuickSearch)`. AnimeciX `false` iken
**hızlı aramada hiç sorgulanmıyordu**. Bu oturumda `true` yapıldı (`AnimeciX.kt:41`).

### 4.4 DÜZELTİLDİ — film içeriği `newMovieLoadResponse` + `TvType.Movie` ✅

Eski kod tüm içeriği `newTvSeriesLoadResponse(..., TvType.Anime, ...)` ile döndürüyordu.
Artık `isMovie` ayrımı yapılıyor ve filmler `AnimeciX.kt:242`'de `newMovieLoadResponse` +
`TvType.Movie` ile dönüyor.

### 4.5 DÜZELTİLDİ — sabit token, canlı tokena çevrildi ✅

Eski: `X_E_H` sabit string. Yeni: `resolveToken()` (`AnimeciX.kt:60`) önce siteden regex ile
canlı token okur, bulamazsa bilinen değere düşer (`cachedToken`, `@Volatile`).
`getSecure()` (`:94`) 403 alırsa `refreshToken()` ile bir kez yeniden dener.

### 4.6 DÜZELTİLDİ — sayfalama `hasNext` + paralel sezon ✅

- `hasNext` hiç verilmiyordu → Keşfet tek sayfada kalıyordu. Artık
  `pagination.currentPage < pagination.lastPage` (`AnimeciX.kt:150`).
- Sezonlar sıralı çekiliyordu (N sezon × N istek gecikmesi) → `async`/`awaitAll` ile paralel
  (`AnimeciX.kt:202`).
- Puan alanı hiç doldurulmuyordu → `toScore()` (`:178`) eklendi.

### 4.7 GERİ ÇEKİLDİ — "eklenti tamamen farklı bir API'ye yazılmış" iddiası kanıtlanamadı ⚠️

Önceki teşhis şuydu: *"`/secure/*` uç noktaları kalkmış, eklenti ölü bir API'ye yazılmış."*

**Bu iddiayı geri çekiyorum.** Dayanağı yoktu:

| Kanıt olarak sunulan | Gerçekte gösterdiği |
|---|---|
| 4 dump'ta `/secure` = 0 | SPA kabuğunda **hiçbir** API yolu yok (JS bundle indirilmedi) — `/secure`'ün kalktığını **göstermez** |
| GitHub issue #4290'da `/titles/<id>/<slug>/…` | Yalnızca **sayfa** URL'i; XHR uç noktasını göstermez |

Angular SPA'lar tipik olarak `/secure/*` gibi JSON uç noktalarını **arka planda** çağırır ve
bu yollar HTML'e hiç yazılmaz. Elimizdeki dump'lar (`main-ZZ232L5W.js` indirilmedi) bu soruyu
**cevaplayamaz**. Kesin cevap için JS bundle'ı veya tarayıcı ağ kaydı (HAR) gerekir.

Bu yüzden AnimeciX'te **tür (`mainPage`) satırı eklenmedi**: hangi rotanın yaşadığı ölçülmeden
eklemek, tam olarak bu raporun kaçındığı "varsayıma dayalı düzeltme" olurdu.

### 4.8 Açık kalan tek gerçek risk

`x-e-h` token üretimi istemci JS'inde. `resolveToken()` site ana sayfasında regex arıyor;
SPA'da bu değer HTML'de bulunmuyor (dump'ta 0 eşleşme) ⇒ **her zaman** bilinen sabit değere
düşecek. Token sunucuda döndürülürse eklenti 403 alır ve `getSecure()` tek retry ile de kurtaramaz.
Bu, dump olmadan çözülemez; HAR kaydı gerekiyor.

---

## 10. SONUÇ — her iki eklenti CI'da yeşil (2026-11-04)

| Eklenti | Commit | Run | Sonuç |
|---|---|---|---|
| **DiziBox** | `7f75bfb` | `41872459142` | ✅ **BUILD SUCCESSFUL**, hata annotasyonu yok |
| **DiziPal** | `98995b7` | — | ✅ **BUILD SUCCESSFUL**, **60 PASSED / 0 FAILED** |
| **AnimeciX** | `8499ca2` | `41873588566` | ✅ **BUILD SUCCESSFUL**, `.cs3` üretildi |

**Üç eklenti de gerçek Gradle derlemesinden geçti.** Bundan önce AnimeciX hiç
derlenmemişti; ilk derleme **sözdizimi hatası** verdi (aşağıda §10.8).

Statik simülasyonun yerini gerçek derleme aldı. Bu oturumda **üç gerçek hata** yalnızca
derleyici/test tarafından yakalandı — hiçbiri elle yapılan dump analizinde görülmemişti:

### 10.1 Derleyici hatası — `suspend Mutex` (DiziPal)

```
DiziPal.kt:439 Suspension functions can only be called within coroutine body
DiziPal.kt:446 aynı
```

`Mutex.withLock` **suspend**'dir; ama CloudStream'in `loadExtractor` API'si **suspend olmayan**
`(ExtractorLink) -> Unit` / `(SubtitleFile) -> Unit` bekler. Lambda tipini suspend yapmak
API sözleşmesini bozar. Çözüm: kilitsiz `ConcurrentHashMap.newKeySet()` dedupe — amaç zaten
karşılıklı dışlama değil, paralel kaynaklardan gelen **aynı linkin** iki kez iletilmemesiydi.

### 10.2 Mantık hatası — `languageBadges` 0 rozet döndürüyordu (DiziBox)

`isUsableImageUrl()` bir **poster** filtresidir ve `/altyazi.png` / `/dublaj.png` adreslerini
**bilerek** reddeder (afiş yerine rozet ikonu seçilmesin diye). Ama `languageBadges()` tam da
bu dosyaları arıyor ve aynı filtreyi kullanıyordu:

```
dump: 1 eşleşen img (altyazi.png) → filtre sonrası 0 → 0 rozet
```

Düzeltme: rozet seçiminde poster filtresi kullanılmıyor, yalnız `data:` şeması eleniyor.
Simülasyon sonrası: **1 rozet, `Türkçe Altyazı`, `altyazi.png`** — testin beklediği değer.

### 10.3 Tip hatası — `assertEquals("2008", detail.year)`

`SeriesDetail.year` **`Int?`**. Kotlin'de `Int(2008) != String("2008")` ⇒ test **hiçbir zaman**
geçemezdi. `assertEquals("Yayın yılı", 2008, detail.year)` olarak düzeltildi.

### 10.4 Bayat test — uydurma `dbxu` çerezi

`cookieHeaderMerger...` testi hâlâ `dbxu=` çerezini **bekliyordu**; oysa §1.2'de bu çerezin
uydurma olduğu ölçülmüş ve koddan kaldırılmıştı. Test, çerezin **eklenmediğini** doğrulayan
regresyon bekçisine çevrildi.

### 10.5 `hasNextPage` yanlış pozitifi (DiziPal) — dump'tan yakalandı

`pageHref` yok (4 dump'ta 0 eşleşme) ⇒ yedek yol metin işaretlerine bakıyordu. Ölçüm:

| İşaret | Gerçekte nerede geçiyor | Sayfalama sinyali mi? |
|---|---|---|
| `Sonraki` | duyuru bandı: *"Sonraki adresimiz dizipal1595.com"* | ❌ **hayır** |
| `daha fazla` | bölüm özeti: *"...daha fazla bilgi edinmektir"* | ❌ **hayır** |
| `sayfa=`, `page=`, `rel="next"` | hiçbir dump'ta yok | — |

Sonuç: `hasNextPage(html, 2)` **4 dump'un hepsinde `true`** dönüyordu → sonsuz sayfalama.
Düzeltme: yalnız yapısal sinyaller (`sayfa=N`, `page=N`, `rel="next"`).
Regresyon testi eklendi (`announcementBannerTextIsNotAPaginationSignal`) ve **geçti**.

### 10.6 CI altyapı düzeltmeleri

| Sorun | Etki | Düzeltme |
|---|---|---|
| `\| tee` + `pipefail` yok (DiziPal) | Gradle başarısızken adım **success** görünüyordu; Maven 409 hatası maskelendi | `PIPESTATUS` ile gerçek exit code |
| Tek adımda `make` + `test` | hangi task'ın kırıldığı görünmüyordu | iki ayrı Gradle çağrısı, `MAKE_RC`/`TEST_RC` |
| Log artefaktı API'den indirilemiyor | test hataları okunamıyordu | `::error::` annotasyonu ile yayınlama |

### 10.7 Hâlâ doğrulanamayan

- **AnimeciX'in ÇALIŞMA ZAMANI davranışı**: derleme ✅, ama siteye canlı istek atılmadı.
  SPA dökümlerinde içerik yok (`<app-root>` boş), dolayısıyla `/secure/*` uç noktalarının
  hâlâ yaşadığını **doğrulayamıyorum**. §4.7'de bu iddiayı bu yüzden geri çekmiştim.
- `resolveToken()`: SPA'da `x-e-h` HTML'de **yok** (dökümde 0 eşleşme) ⇒ her zaman sabit
  değere düşecek. Sunucu token'ı döndürürse eklenti 403 alır ve tek retry kurtaramaz.
  **HAR kaydı veya JS bundle'ı olmadan çözülemez.**
- **Anizm / TrAnimeIzle**: kapsam dışı, yalnız teşhis. Kodları değiştirilmedi.

### 10.8 Derleyici hatası — KDoc içinde `/*` (AnimeciX)

```
AnimeciX.kt:383 Syntax error: Unclosed comment.
AnimeciX.kt:86  Syntax error: Missing '}'.
```

**Kotlin blok yorumları iç içe geçer** (Java/C'nin aksine). Şu KDoc satırı:

```
 * `/secure/*` isteği; 403/boş yanıtta token'ı bir kez tazeleyip tekrar dener.
         ^^ yeni bir blok yorum AÇIYOR
```

`/secure/*` dizisindeki `/*` ikinci bir yorum başlatıyor; dıştaki `/**` hiç kapanmıyor,
dosyanın geri kalanı yorum sayılıyor. Bu yüzden derleyici hem "unclosed comment"
hem de "missing }" veriyor.

Tüm repoda (`*/src/**/*.kt`, 200+ dosya) taranan **tek** riskli satır buydu; düzeltildi.
Bu hata **statik denge tarayıcımın kaçırdığı** türdendi: parantez/süslü sayıları
dengeliydi, sorun yorum sözdizimindeydi.

> **Ders:** "parantezler dengeli" ≠ "derlenir". Bu oturumda üç eklentinin üçünde de
> yalnız derleyicinin yakaladığı hatalar çıktı (suspend lambda, poster filtresi,
> iç içe yorum). Statik simülasyon **davranışı** doğrular, **derlenebilirliği** doğrulamaz.
