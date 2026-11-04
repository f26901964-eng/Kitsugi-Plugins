# DiziBox · DiziPal — "sorunlu" teşhisi

**Tarih:** 04.11.2026 · **Dal:** `arena/03f7617a-kitsugi-plugins`
**Yöntem:** gerçek site dökümleri (`DiziBox/site-dumps/*.html` — 3 dosya, 2.6 MB;
`DiziPal/src/test/resources/*.rsc.html` — 6 fixture) + Kotlin kaynağı +
Cloudstream çekirdek bütçeleri. Canlı istek yok.

**Sonuç önden:** İkisi de `AnimeciX`'ten **belirgin şekilde daha iyi** durumda.
DiziBox'ta gerçek bir **alan adı / kırılganlık** sorunu var; DiziPal'de ise
**paralel çözüm yokluğu** ve **tek alan adı** ana riskler. İkisinde de
"video gelmiyor"un en sık nedeni **bir önceki eklentiyle aynı**: sessiz sıfır
dönüş (kullanıcıya sebep gösterilmiyor).

---

## DiziBox

### Mimari (ölçülen)
Kod bu depodaki **en olgun** `loadLinks` yapısına sahip:

```
loadLinks
 ├ fetchDiziBoxDocument(data)                      → 840 KB bölüm sayfası
 ├ playerTabs(doc).take(10)                        → 3 sekme: DBX Pro · Moly+ · Odnok
 ├ Semaphore(4) + withTimeoutOrNull(TAB_TIMEOUT=28s) → sekmeler PARALEL
 ├ her sekme: tab dokümanı (14s) → iframe/option adayları
 └ her aday: resolvePlayerChain (depth≤4)
      ├ DiziBox PHP sarmalayıcıları (king.php / moly.php / haydi.php / spidy.php)
      ├ MolyStream (www + dbx + molystream.net + ydx aynaları, BTK notu ile)
      ├ Vidmoly · OK.ru (Odnok) · Sibnet · Mail.ru · VK
      └ yerleşik CloudStream extractor + genel embed
```
**Olumlu:** zaman aşımı **öğe başına**, sekme sayısı **10 ile sınırlı**,
BTK engelli ayna (`dbx.molystream.org`) yerine **engelsiz ayna önce** deneniyor.
Bu, tr'de çalışan eklentiler arasında doğru yaklaşım.

---

### DBX-1 · Tek alan adı + sabit varsayım ⛔ *(en büyük risk)*
`DiziBox.kt:31` → `mainUrl = "https://www.dizibox.live"`

Alan adı **her yerde parametre** olarak geçiyor ama **tek aday** var:
```kotlin
fun interceptorFor(siteUrl: String = "https://www.dizibox.live")      // :1334
fun isDiziBoxHost(url: String, siteUrl: String = "https://www.dizibox.live")  // :1366
fun isDiziBoxHost(url: String, siteUrl: String = "https://www.dizibox.live")  // Wire:65
```
`domainCandidates` / `ensureBase` / `remapDomain` **yok** (`Anizm`,
`TrAnimeIzle`, `FilmMakinesi`, `HDFilmCehennemi`'de var).

Ayrıca `DiziBoxPlayerParser.kt:132` ölü domain listesinde **`dizibox.cc`** var:
```kotlin
"...|gravatar\\.com|dizibox\\.cc"   ← sadece .cc
```
Site `.live` → `.cc` (veya başka bir TLD) taşındığında:
`isDiziBoxHost` **false** döner → `DiziBoxWire` cookie'leri (`LockUser`,
`isTrustedUser`, `dbxu`) **gönderilmez** → DBX Pro oynatıcısı **boş** gelir.
Kullanıcı "DiziBox kaynak vermiyor" der, oysa site ayaktadır.

**Ayrıca:** `DiziBoxWire.diziboxCookies()` **sahte** cookie üretiyor
(`isTrustedUser=true`, `dbxu=<now>`). Site bunu bir gün doğrularsa
(imza/çerez çifti) **tüm kaynaklar** aynı anda düşer. Kırılganlık.

### DBX-2 · Yavaşlık: 840 KB HTML + 3 sekme + derin zincir
Ölçülen bütçe:
| Aşama | Süre |
| --- | --- |
| bölüm sayfası (840 KB) | 2-5 s |
| 3 sekme paralel (28 s tavan) | 0-28 s |
| her aday zinciri (12 + 8.5 + 14 s) | 0-35 s |

Kötü durumda **~60 s**'ye kadar çıkıyor. `APIRepository` varsayılan bütçesi
120 s olduğu için **kesilmiyor**; kullanıcı "dondu" görüyor.
**Belirti:** kaynak gelir ama *geç* gelir. "Bazen veriyor bazen vermiyor"
hissinin yarısı buradan.

### DBX-3 · Sessiz sıfır: 3 sekmenin hepsi düşerse açıklama yok
`processPlayerTab` içinde:
```kotlin
if (iframes.isEmpty() && options.isEmpty()) {
    if (isGeoBlockedVideoArea(...)) Log.d(TAG, "… Türkiye dışı IP …")
    return          // ← kullanıcıya hiçbir şey söylenmiyor
}
```
Coğrafi engel, Cloudflare basamağı, BTK engeli — hepsi **log'a** yazılıyor,
kullanıcıya **tek bir cümle** gitmiyor. `sources` (export) yoksa
`loadLinks` sadece `false` dönüyor.

### DBX-4 · `failOnChallenge` → arama tamamen çöküyor
`search()` içinde:
```kotlin
catch (e: ErrorLoadingException) { throw e }       // :122
catch (_: Exception) { emptyList() }
```
Cloudflare "Just a moment" sayfası gelirse **istisna yukarı fırlıyor**.
Aramada bu, eklentinin o sorgu için **tamamen kaybolması** demek.
`loadLinks`/`load` tarafında daha yumuşak davranmalı.

### DBX-5 · `playerTabs()` aslında sezon değiştirici
Gerçek dökümde (`episode-visionquest-1-1.html`) sözde "oynatıcı sekmeleri"
şu adresler:
```
option 1  (selected)  … /visionquest-1-sezon-1-bolum-izle/
option 2              … /visionquest-1-sezon-1-bolum-izle/2/
option 3              … /visionquest-1-sezon-1-bolum-izle/3/
```
Etiketler (`DBX Pro`, `Moly+`, `Odnok`) **`<!--baslik:-->`**'ten geliyor,
yani **aynı oynatıcı**. Adresler ise **sayfa numarası** (muhtemelen farklı
altyazı/dublaj sürümleri).

**Etki:** `take(10)` × 28 s bütçesi **aynı kaynağın kopyalarını** çözmeye
harcanıyor. `playerOptionUrls` düzeltmesi gereken yer burası:
`/2/`, `/3/` **sayfa numarasıysa** sekme değil, sayfalama olarak ele alınmalı.

### DBX-6 · Gerçek dökümde `archive-box` yok → fazladan istek
`series-visionquest.html` içinde `archive-box` = **0**, `archive-title` = **0**.
`DiziBox.load()` her seferinde `archiveBoxLink()` çağırıyor → **null**.
Zararsız ama gereksiz. Bölüm dökümünde de `archive-box` yok — yani bu kod yolu
artık **hiç tetiklenmiyor**, temizlenebilir.

### DBX-7 · Testler güçlü, ama tek "canlı" test bayat
`DiziBoxSiteDumpsContractTest` **15 test** ile gerçek dökümlere bağlı —
bu depoda **en iyi test kapsamı**. Ancak `LiveChainProbeTest` dış ağa
bağlıysa CI'da sessizce atlanıyor; canlı zincir **doğrulanmıyor**.

---

## DiziPal

### Mimari (ölçülen)
Next.js/Turbopack + RSC (`self.__next_f.push`) **SUNUCU taraflı** ayrıştırma.
Oynatıcı adresi **AES-256-CBC + PBKDF2-HMAC-SHA512(999)** ile şifreli;
`DiziPalParser.decryptPlayer` bunu **sitenin JS'i ile birebir** çözüyor
(13 test var, `decryptPlayer` gerçek fixture ile doğrulanıyor:
`//four.dplayer82.site/iframe.php?v=…`). Bu **doğru yapılmış** iş.

---

### DZP-1 · `loadLinks` TAMAMEN SIRALI ⛔ *(Ana şikâyet)*
```kotlin
for (source in sources) {              // :258
    val sourceHtml = fetch(sourceUrl)  // ← her kaynak için ayrı indirme
    val target     = decryptPlayer(payload)
    extractFromPlayer(target, …)       // ← m3u8 → iframe → extractor, HEPSİ SIRALI
}
```
Ölçülen kaynak sayısı (gerçek fixture): `player-movie-nocturnal` → **2 kaynak**
(`PUB+ · Türkçe Altyazı`, `Türkçe Dublaj`), `player-gassal-4x1` → **1**.

Her kaynak için:
- `fetch(sourceUrl)` → **60 s timeout** (:287, :304, :412)
- oynatıcı sayfası → **60 s timeout**
- iç iframe → **60 s timeout**
- extractor → sınırsız

**Kötü durum: 2 kaynak × 3 istek × 60 s = 360 s.**
`APIRepository` varsayılan bütçe **120 s** → **kesiliyor** → kullanıcı
"takıldı / boş geldi" görür. `withTimeoutOrNull` **yalnızca 2 yerde** var,
`async`/`awaitAll` **0**.

> **Karşılaştırma:** DiziBox aynı işi `Semaphore(4)` + 28 s/öğe ile yapıyor.
> DiziPal'de **hiç** paralellik yok. "DiziPal sorunlu" hissinin **1 numaralı
> nedeni** bu.

### DZP-2 · Tek alan adı, sürüm numarası URL'de ⛔
`mainUrl = "https://dizipal1593.com"` → sürüm numarası **kodun içinde**.
Site 1594'e geçtiğinde eklenti **tamamen ölür** (arama + ana sayfa + link).
`domainCandidates` / `ensureBase` / `remapDomain` **yok**.

**Fark:** DiziBox'ta en azından `mainUrl` parametre olarak geçiyor; DiziPal'de
`mainUrl` **her yerde sabit** kullanılıyor.

### DZP-3 · `CloudflareInterceptor` → `CloudflareKiller` (WebView!)
`DiziPal.kt:44-47`:
```kotlin
private val cloudflareKiller by lazy { CloudflareKiller() }
class CloudflareInterceptor(...) : Interceptor {
    if (body.contains("Just a moment") || …) return cloudflareKiller.intercept(chain)
}
```
`CloudflareKiller` **WebView** tabanlı. Sonuç:
- `usesWebView` **bildirilmemiş** (`override val usesWebView = false` var sayılıyor).
  Uygulama WebView'siz cihaz/kısıtta bunu bilemez.
- WebView çözümü **~10-15 s** ekliyor, ve **her** Cloudflare sayfasında
  yeniden deneniyor (cache yok).
- Aynı interceptor `loadLinks` içinde **her istekte** kullanılıyor (:287, :304, :411).

### DZP-4 · `searchRpc` sadece sayfa boşsa deneniyor
```kotlin
val hits = parseListing(fetch(url))
if (hits.isNotEmpty()) return hits
return searchRpc(query)          // :160
```
`parseListing` ⇒ `a.tc` kartları veya RSC feed. Site arama sayfasını
**tamamen istemci tarafında** render ediyorsa HTML'de kart olmaz → `searchRpc`
devreye girer (iyi). Ama `searchRpc` `POST /api/mj` **tek** uç; o da düşerse
**boş liste**. İki yol da **sıralı**, paralel değil.

### DZP-5 · `mainPage` adresleri kurulum anında sabitleniyor
```kotlin
override val mainPage = mainPageOf("${mainUrl}/trendler" to "Trend Diziler", …)
```
`mainUrl` sonradan tanımlanırsa (şu an tanımlanmıyor ama DZP-2 düzeltilirse)
tüm ana sayfa adresleri **eski domaine** gider. `Anizm` bunu `remapBase()`
ile çözüyor, DiziPal'de böyle bir mekanizma **yok**.

### DZP-6 · `hasQuickSearch = true` ama `quickSearch` = `search`
```kotlin
override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)
```
`search()` her çağrıda **tam sayfa indirir** (RSC ayrıştırma + gerekirse
`POST /api/mj`). Hızlı aramada **her tuş vuruşunda** bu çalışıyor.
`Anizm`/`TrAnimeIzle` `hasQuickSearch = true` ama **hafif** yolları var;
DiziPal'de yok. Yazarken **kasma** hissi buradan.

### DZP-7 · `decryptPlayer` doğru — ama tek "k" anahtarı
Fixture'lardan ölçüm: **iki farklı oynatıcı** (dizi + film) **aynı** `"k"`
değerini kullanıyor (`3hPn4uCjTVtfYWcj…`). Yani `k` **site genelinde paylaşılan**
bir anahtar. Site bunu **rotasyona** sokarsa `decryptPlayer` **null** döner →
`loadLinks` sessizce `false` (DZP-1 ile birleşince **tamamen boş**).

---

## Karşılaştırma tablosu

| Kriter | DiziBox | DiziPal | (referans) AnimeciX |
| --- | --- | --- | --- |
| Alan adı yedeği | ✖ (tek, `.cc` ölü) | ✖ (sürüm no. kodda) | ✖ |
| `loadLinks` paralel | **✔** 4 eşzamanlı, 28 s | ✖ **tamamen sıralı** | ✖ tamamen sıralı |
| Öğe başına timeout | **✔** | ✖ (60 s global) | ✖ **hiç yok** |
| Sessiz sıfır | kısmen (log var) | ✖ | ✖ |
| CAPTCHA | ✖ (CloudflareKiller yok) | ✔ WebView (ağır) | ✖ |
| Şifre çözme | CryptoJS uyumlu | **✔ AES+PBKDF2** | — |
| Gerçek döküm testi | **✔ 15 test** | ✔ 6 fixture | ✖ |
| Site dökümü var mı | **✔ 2.6 MB** | ✔ 6 fixture | ✖ |
| Çoklu kaynak | **✔** 3 sekme | ✔ 2 (film) / 1 (dizi) | ✖ tek yol |

**Sıralama (iyiden kötüye):** DiziBox **>** DiziPal **≳** AnimeciX
(DiziPal'in şifre çözme işi AnimeciX'ten iyi, ama paralellik ve alan adı
konusunda daha kırılgan).

---

## Öncelik sırası

| # | Eklenti | Sorun | Etki | Zorluk |
| --- | --- | --- | --- | --- |
| 1 | DiziPal | DZP-1 `loadLinks` sıralı → 120 s bütçe kesiliyor | **yüksek** | düşük |
| 2 | DiziPal | DZP-2 alan adı sürüm numarası (1593) | **yüksek** | düşük |
| 3 | DiziBox | DBX-1 alan adı yedeği + `dizibox.cc` ölü kaydı | yüksek | düşük |
| 4 | DiziBox | DBX-5 `playerTabs` sayfa numaralarını sekme sanıyor | orta | orta |
| 5 | DiziPal | DZP-6 `quickSearch` = tam arama | orta | düşük |
| 6 | DiziPal | DZP-3 `CloudflareKiller` + `usesWebView` bildirimi | orta | orta |
| 7 | DiziBox | DBX-3/DBX-4 sessiz sıfır + aramada istisna | orta | düşük |
| 8 | DiziBox | DBX-2 toplam süre sıkıştırma | düşük-orta | orta |
| 9 | DiziBox | DBX-6 ölü `archive-box` yolu temizliği | düşük | düşük |

---

## Döküm ihtiyacı

Her iki eklentide de depoda **gerçek döküm var**, yani yeni dosya **zorunlu değil**:

| Eklenti | Elimizde | Yine de işe yarar |
| --- | --- | --- |
| DiziBox | `homepage.html`, `series-visionquest.html`, `episode-visionquest-1-1.html` | **çok sezonlu** bir dizi sayfası (2. sezon düğmesi görünen) + `/player/king/king.php?v=…` çıktısı |
| DiziPal | 6 RSC fixture (dizi + film + player + seasons + cards) | **2+ kaynaklı** bir bölüm sayfasının tam HTML'i (şu an sadece 1 kaynaklı örnek var) |

**DiziBox için özellikle:** `king.php` sarmalayıcısının **ham çıktısı**
(`?v=…` parametreli). DBX-1 ve DBX-5 düzeltmesi buna bakılarak doğrulanır.

---

## Not: bu iki eklentide "hızlı düzeltme" adayları

Döküm beklemeden yapılabilecekler (kod düzeltmesi):

1. **DZP-1** → `loadLinks`'i `Semaphore(4)` + `withTimeoutOrNull(20_000)`
   ile paralelleştir (DiziBox desenini kopyala).
2. **DZP-2** → `domainCandidates` + `resolveDomain()` (FilmMakinesi deseni),
   `mainPage` adreslerini `remapBase()` ile üret (Anizm deseni).
3. **DBX-1** → `domainCandidates` (`dizibox.live`, `.cc`, `.com`, …) +
   `dizibox.cc` ölü kaydını canlı listeye çevir.
4. **DBX-3** → sessiz `return` yerine `ErrorLoadingException` (teşhis edilebilir).
