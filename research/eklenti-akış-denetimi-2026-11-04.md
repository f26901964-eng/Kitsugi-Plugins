# Aktif video eklentileri — "video verisi gelmiyor" denetimi

**Tarih:** 04.11.2026 · **Dal:** `arena/03f7617a-kitsugi-plugins`
**Kapsam:** `enabled_plugins.txt` içindeki 9 eklenti (Anizium, Anizm, AnimeciX,
TrAnimeIzle, DiziPal, FilmMakinesi, HDFilmCehennemi, DiziBox, HDFilmIzle)
**Yöntem:** yalnızca depodaki **gerçek sayfa dökümleri** (`*/src/test/resources/live`,
`DiziPal/src/test/resources`, `DiziBox/site-dumps`) + Cloudstream 3 çekirdek
kaynağı (`library/.../MainAPI.kt`, `app/.../ResultViewModel2.kt`,
`RepoLinkGenerator.kt`) + eklenti Kotlin kaynağı. **Canlı site isteği yapılmadı**
(bu ortamda dış ağ yok; ölçüm cihazda/CI'da yapılmalı).

---

## 0. Kısa cevap: "sorun uygulamada mı, eklentilerde mi?"

**İkisinde de — ama ayrı ayrı yerlerde.** Torrentio'nun "tak diye sonuç"
vermesi bir sihir değil; üç yapısal avantajın sonucu:

| Torrentio | Bu depodaki site eklentileri |
| --- | --- |
| IMDb/TMDB kimliğiyle **doğrudan** sorgu (`/stream/movie/tt….json`) | Siteye **metin araması** + HTML ayrıştırma |
| Tek tip, sürümlenmiş JSON sözleşmesi | Her site ayrı HTML/JS sözleşmesi, ayrı domain |
| İstemci tarafı **çoklu debrid/seed sağlayıcı** ile paralel çözüm | Oynatıcı → embed → şifreli JS → HLS zinciri, her biri ayrı |

Yani "her eklenti kafasına göre çalışıyor" gözlemi **doğru**: her eklenti kendi
**kimlik anahtarını** (URL mi, MAL/AniList ID mi, IMDb ID mi) ve kendi **kaynak
zincirini** kullanıyor. Uygulamanın çekirdeği bunları tek bir sözleşmeye
zorlamıyor; eklenti ne bildirirse o geçerli.

Uygulama tarafındaki iki gerçek kısıt (kaynak kodu doğrulandı):

1. **`quickSearch` filtresi** — `SearchViewModel.kt:233`:
   `(!isQuickSearch || a.hasQuickSearch)`. Hızlı arama kutusu yalnızca
   `hasQuickSearch = true` olan eklentilere sorar.
   → **AnimeciX `hasQuickSearch = false`** ⇒ hızlı aramada AnimeciX **hiç
   çağrılmıyor**. (FilmMakinesi de `false`.)
2. **Tür filtresi** — `AppContextUtils.kt:478`
   `api.supportedTypes.any { currentPrefMedia.contains(it.ordinal) }`.
   Kullanıcının tercih ettiği medya türü eklentinin bildirdiği türle
   kesişmezse eklenti **tamamen** listelerden düşer.
   → AnimeciX yalnızca `TvType.Anime` bildiriyor; Anizm `Anime/AnimeMovie/OVA`.
   Film aramasında bu iki eklenti hiç sorulmaz.

Ve **hiçbir eklentide** şunlar yok (repo genelinde `grep` ile doğrulandı):

- `addMalId` / `addAniListId` / `addImdbId` → **0 kullanım**.
  Sonuç: içerik, uygulamanın sync listesinden (AniList/MAL/Simkl) gelen
  **ID ile** açılamıyor; yalnızca metin aramasıyla bulunabiliyor.
- `getLoadUrl` + `supportedSyncNames` → yalnızca **FilmMakinesi** ve
  **HDFilmCehennemi** (ikisi de `SyncIdName.Imdb`). Diğer 7 eklenti yok.
- `instantLinkLoading` → **0 kullanım** (linkler önceden hazırlanamıyor).

---

## 1. Eklenti eklenti tablo

| Eklenti | Domain yedeği | Arama toleransı | `getLoadUrl` | Sync ID gömme | Paralel çözüm | Hızlı arama | "tak diye" potansiyeli |
| --- | --- | --- | --- | --- | --- | --- | --- |
| **Anizium** | dex (kapalı) | ? | ✖ | ✖ | ? | ? | orta (dex) |
| **Anizm** | ✔ 10 dk TTL prob | ✔ varyant + Kitsu eşadı | ✖ | ✖ | ✔ 8 eşzamanlı, 18 sn/öğe | ✔ | **yüksek** |
| **AnimeciX** | ✖ **tek domain** | ✖ | ✖ | ✖ | ✖ **sıralı** | ✖ **kapalı** | **düşük** |
| **TrAnimeIzle** | ✔ host remap | ✔ varyant | ✖ | ✖ | ✔ | ✔ | orta-yüksek |
| **DiziPal** | ✖ **tek domain** | ✖ | ✖ | ✖ | ✖ | ✔ | orta |
| **FilmMakinesi** | ✔ 7 aday | ✔ TMDB başlık skoru | **✔ IMDb** | ✖ | ✔ | ✖ | yüksek (film) |
| **HDFilmCehennemi** | ✔ | ✔ | **✔ IMDb** | ✖ | ✔ 20 sn | ✔ | **yüksek** |
| **DiziBox** | ✖ **tek domain** | ✖ | ✖ | ✖ | ✔ 4 eşzamanlı | ✔ | orta |
| **HDFilmIzle** | ✖ **tek domain** | ✖ | ✖ | ✖ | ✖ **sıralı** | ✔ | **düşük** |

---

## 2. Bulgu listesi (önem sırasıyla)

### B1 — FilmMakinesi *(GERİ ÇEKİLDİ — yanlış teşhis)*
İlk raporda "dizi sayfası film sanılıyor" yazmıştım. **Kendi dökümünle test ettim,
iddia yanlış.** `load()` içindeki `a[href*='bolum']` seçicisi
`fm_page_episode.html` dökümünde **eşleşiyor** (43 link) ⇒ `isSeries = true`,
bölüm listesi üretiliyor. Senin düzelttiğin hâli çalışıyor.

Yine de dökümden ölçülen **iki küçük kusur** duruyor (blocker değil, kozmetik):

| # | Kusur | Döküm kanıtı |
| --- | --- | --- |
| B1a | Bölüm adı `.ep-title` yerine tüm `<a>` metninden alınıyor → `"25 Ekim 2024 / 22 dk / 1. Sezon / 2. Bölüm Tapped Out"` | `class="ep-title"` ×42, `class="ep-details"` ×42 |
| B1b | "Sonraki" ve "Dizi Anasayfası" düğmeleri de bölüm sayılıyor → 43 link, 42 gerçek bölüm | `<a href="…bolum-2/" class="btn btn-default ">… Sonraki</a>` |

`parseSeriesEpisodes()` ayrıca ölü seçicilerle başlıyor
(`div.seasons-tab-content`, `card-list-item` → dökümde **0**), her çağrıda
boşuna iki `select()` harcıyor. Gerçek yapı:
`ul#sezonTab` → `div#tab-sezon-N` → `a.item-ep`.

### B2 — AnimeciX: hızlı aramada hiç çağrılmıyor + tek kırılgan token
**Kanıt:** `AnimeciX.kt:27` `hasQuickSearch = false`; `SearchViewModel.kt:233`.

- Hızlı arama (arama kutusuna yazarken çıkan sonuçlar) yalnızca
  `hasQuickSearch = true` eklentilere gider ⇒ **AnimeciX orada yok**.
  Kullanıcı yazdığı anda "AnimeciX sonuç vermiyor" görür; tam aramada gelir.
- Tüm `/secure/*` uçları **sabit** `x-e-h` başlığına bağlı
  (`X_E_H = "7Y2oz…"`). Site bu değeri döndürürse **arama + ana sayfa + bölüm
  listesi + loadLinks aynı anda** ölür. Dinamik yenileme yok.
- `loadLinks` **tamamen sıralı**: önce `best-video`, sonra `loadExtractor`,
  sonra iframe'ler, sonra düz m3u8 taraması. `async`/`awaitAll` **0**.
  Yavaşlık ve "bazen hiç gelmiyor" buradan.
- Tek domain (`animecix.tv`), yedek yok.

---

### B3 — HDFilmIzle: oynatıcılar sıralı çözülüyor, domain yedeği yok
**Kanıt:** `HDFilmIzle.kt:199-250` — `frames.forEach { … }` içinde her çerçeve
**bekleyerek** çözülüyor; `async/awaitAll` **0**, `Semaphore` **0**.
`HDFilmIzlePlayerResolver` içinde iki `withTimeoutOrNull` var ama döngü sıralı.

Sonuç: 5 oynatıcı × ~8 sn = 40 sn+ bekleme, ilk oynatıcı yavaşsa hepsi
gecikir; Cloudstream varsayılan `loadLinks` timeout'u 120 sn olsa da kullanıcı
deneyimi "takıldı" olur. Domain yedeği de yok (`hdfilmizle.to` sabit).

---

### B4 — AnimeciX / DiziPal / DiziBox: tek domain, yedek yok
`grep` sonucu: `domainCandidates`, `ensureBase`, `remapDomain`, `KNOWN_HOSTS`
**yalnızca** Anizm, TrAnimeIzle, FilmMakinesi, HDFilmCehennemi'de var.

Bu dört eklenti (AnimeciX, DiziPal, DiziBox, HDFilmIzle) site adresi
değiştiğinde **tamamen ölür** — kullanıcının "bazı eklentiler takır takır
veriyor, bazıları hiç vermiyor" gözleminin en sık nedeni bu.

---

### B5 — Hiçbir eklenti ID ile açılmıyor (sync entegrasyonu eksik)
`addMalId/addAniListId/addImdbId` repo genelinde **0 kez** kullanılıyor.

Pratik etkisi: AniList'ten bir anime açtığınızda uygulama eklentiye
**kimlik** veremiyor, **başlık metni** veriyor. Anime siteleri Türkçe/romanize
ad kullandığı için ("Shingeki no Kyojin" ↔ "Attack on Titan") arama boş
dönebiliyor. Anizm bunu Kitsu eşadlarıyla yamalıyor (`titlesFromKitsu`), ama
bu bir **ağ isteği** ve yalnızca "sonuç < 3" olduğunda devreye giriyor.

---

### B6 — Anizium: kaynak kodu yok, yalnızca `classes.dex`
`Anizium/src/main/kotlin/` içinde tek dosya (`AniziumPlugin.kt`) var; mantık
`classes.dex` içinde. Dex'te `getLoadUrl`, `supportedSyncNames`,
`addAniListId` **yok**; `anilistId` yalnızca **okuma** tarafında
(`load$anilistId`) geçiyor. Yani Anizium da ID ile açılamıyor.
Bu eklenti **yalnızca dex ile** güncellenebilir; Kotlin'den düzeltilemez.

---

### B7 — TrAnimeIzle: CAPTCHA bir "sessiz zaman aşımı" kaynağı
`TrAnimeIzle.kt:450` `solveCaptcha()`: 2 cID × 2 tema × (1 liste + N ikon
indirme + 1 gönderim) ≈ **10-20 sn**. Çözüm serileştirilmiş
(`captchaLock`), yani **eşzamanlı 8 kaynak çözümü CAPTCHA'da sıraya girer**.
CAPTCHA kabul edilmezse `loadLinks` `ErrorLoadingException` atar ve kullanıcı
"kaynak bulunamadı" görür — oysa site çalışıyordur.

---

### B8 — Anizm: doğru mimari, tek zayıf nokta katalog indirmesi
`AnizmStreams.kt`: çevirmenler paralel (10 sn), kaynaklar 8 eşzamanlı
(18 sn/öğe), domain probu 10 dk TTL — **bu depodaki en olgun akış**.
Zayıf nokta: sonuç < 3 ise **>3 MB** katalog indirilir
(`loadCatalogue`, 6 saat önbellek). İlk aramada 3-8 sn ek gecikme.

---

## 3. "Hepsini ortak kümeye al" isteği — neden tek eklentiyle olmuyor

İstediğiniz şey şu: *hangi eklentiden açarsam açayım, aynı içerik için tüm
kaynaklar aynı anda, eksiksiz gelsin.* Cloudstream mimarisinde bunun **iki**
yolu var:

**(a) Uygulama tarafı (Kitsugi):** içerik açıldığında diğer eklentilere de
başlık/ID ile paralel sorulup sonuçların birleştirilmesi. Bunu **uygulama**
yapar, eklenti yapamaz — eklentiler birbirini çağıramaz (izole sınıf yükleyici).
Bu depo yalnızca eklenti üretir; (a) burada çözülemez.

**(b) Eklenti tarafı — yapılabilir kısım:** her eklentiyi **aynı sözleşmeye**
uydurmak:
1. `supportedTypes` doğru ve geniş (film/dizi/anime ayrımı yanlış olmasın),
2. `hasQuickSearch = true` + `quickSearch()` gerçekten hızlı,
3. `getLoadUrl(SyncIdName.Imdb / Anilist / MyAnimeList, id)` implementasyonu,
4. `load()` içinde `addImdbId/addAniListId/addMalId` ile ID gömme,
5. domain yedeği (en az 2 aday + TTL prob),
6. `loadLinks` **paralel** + öğe başına zaman aşımı,
7. `loadLinks` asla "sessizce boş" dönmesin — teşhis edilebilir hata versin.

7 maddeyi uygulayan eklenti, kullanıcı açısından "tak diye" davranır ve diğer
ekranlarda (AniList, kütüphane, öneriler) de çalışır.

---

## 4. Öncelik sırası (öneri)

| # | Eklenti | Sorun | Etki | Zorluk |
| --- | --- | --- | --- | --- |
| 1 | AnimeciX | B2 hasQuickSearch + token + paralel loadLinks | **yüksek** | orta |
| 2 | HDFilmIzle | B3 oynatıcılar sıralı | yüksek | düşük |
| 3 | AnimeciX / DiziPal / DiziBox | B4 domain yedeği yok | yüksek | orta |
| 4 | Anizm | B8 katalog + çözücü boşlukları | orta | orta |
| 5 | TrAnimeIzle | B7 captcha bütçesi + loadLinks fırlatma | orta | orta |
| 6 | Tümü | B5 ID gömme (sync) | orta | düşük |
| 7 | FilmMakinesi | B1a/B1b kozmetik | düşük | düşük |
| 8 | Anizium | B6 dex-only | — | (kaynak yok) |

**FilmMakinesi çalışıyor (doğrulandı).** Kalan şikâyetlerin karşılığı:
- "AnimeciX veri sunamıyor, hızlı aramada yok" → #1
- "HDFilmIzle takılıyor" → #2
- "Site adresi değişince eklenti tamamen ölüyor" → #3

Hangisinden başlamamı istersen söyle; kaynak dosyaları elimde, düzeltmeyi
bu dalda yapıp testleri de ekleyebilirim.
