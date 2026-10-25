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
