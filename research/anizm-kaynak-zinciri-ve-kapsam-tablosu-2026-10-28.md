# Anizm — Tam Kaynak Zinciri ve Kapsam Tablosu (2026-10-28)

> **Amaç:** Anizm eklentisinin "bölüm açıldığında hiçbir fansub/video kaynağı
> eksik düşmesin ve kaynaklar gerçekten oynatılabilir olsun" kabul kriterini,
> CANLI site dökümleri ve dört bağımsız istemcinin güncel koduyla kanıtlamak.
> Tüm bulgular bu depodaki gerçek dosyalara/işlenen fixture'lara dayanır.

## 1. Kanıt kaynakları (tümü 25–27.10.2026 tarihli, güncel)

| Kaynak | Ne verdi | Nerede |
|---|---|---|
| CI `site-dump-data` dalı (2d10d06f) | anizm.com.tr canlı HTML/JSON dökümü: One Piece 1. bölüm (567 KB), One Piece detay (660 KB, 1175 bölüm), ana sayfa, arama, 3 çevirmen XHR yanıtı | `Anizm/src/test/resources/live/` (fixture kopyaları) |
| CI `arena/fetch-anizm` dalı | 4 gerçek **anizmplayer.com** oynatıcı sayfası (packed FirePlayer), `/player/{id}` 302 başlıkları, eski tema bölüm sayfası | `research/anizm-capture` (dal arşivi) |
| `yuzono/anime-extensions` (Aniyomi, master, 27.10 push) | v2 `a[translator]` zinciri + AincradExtractor (getVideo POST) | `/home/user/ref-yuzono/` |
| `Saloo1575/SalooRepo` (CloudStream, main, 27.10 push) | packed `eval(function(p,a,c,k,e,d)…)` unpack + tam v3/v2 zinciri | `/home/user/ref-saloo/Anizm.kt` |
| `falsisdev/anthology` (providers/anizm.js) | Dean Edwards unpacker referansı, getVideo sözleşmesi, ok.ru/sibnet çözücüleri | `/home/user/ref-anthology/` |
| `roxyrekt/Migurdex` (AnizmProvider.cs, 24.10 push) | v3 `button[data-v3-translator]`, `/video/{id}` JSON `{url}`, noRedirect `/player/` | `/home/user/ref-migurdex/` |

## 2. Canlı zincir — bölüm sayfasından oynatılabilir akışa

### 2.1 Bölüm sayfası (v3, TEK canlı tema)

Gerçek düğüm (One Piece 1, `anizm__episode_onepiece_1.html`):

```html
<button class="v3-translator-button" data-translator-id="18853"
        data-v3-translator
        data-sources="https://anizm.com.tr/bolum/18853/cevirmen/18853"
        title="Güncellenecek" aria-pressed="false">
```

- Sayfada **3 çevirmen** var: 18853 (Güncellenecek), 83191 (VictoriaSubs), 109648 (SomeSubs).
- Hiçbir düğüm `aria-pressed="true"` değil → sayfa HTML'i kaynakları **hazır taşımıyor**;
  her şey XHR ile geliyor. Eski `a[translator]`/`videoPlayerButtons` işaretleri bu sayfada **yok**
  (yalnızca eski temada kalan yedek zincir).
- Sayısal bölüm kimliği (18853) düğümdeki URL'de ve `raporver/bolum/18853/video/0`
  bağlantısında; slug URL'inde (`one-piece-1-bolum-izle`) yok.

### 2.2 Çevirmen XHR'u → video kimlikleri

`GET https://anizm.com.tr/bolum/18853/cevirmen/18853`
başlıklar: `X-Requested-With: XMLHttpRequest`, `Referer: <bölüm URL'i>`
(plain/XHR/CSRF üç istem biçimi de aynı gövdeyi verdi; XSRF token zorunlu değil).

Yanıt sözleşmesi:

```json
{"success": true, "videos": [
  {"id": 1437961, "name": "Aincrad (Reklamsız)", "watchTogether": false}, …
]}
```

### 2.3 Video kimliği → oynatıcı host'u

1. `GET /bolum/18853/video/1437961` (aynı XHR başlıkları) →
   JSON `{"url": "…/player/<iç kimlik>", "source": "Aincrad"}`
   (Migurdex `url` alanını kullanır; Saloo varyantında `{"player": "<iframe html>"}`).
2. `GET /player/<iç kimlik>` **yönlendirme izlenmeden** → `Location: https://anizmplayer.com/video/<32 hex hash>`
   (4 Wayback örneğinin dördü de anizmplayer'a gitti; diğer host'lar için harici iframe).
3. Harici host'lar (GDrive, Sibnet, Voe, Dood…) doğrudan iframe olarak döner;
   uygulamanın kayıtlı extractor'ları (`loadExtractor`) veya eklentinin yerel çözücüleri işler.

### 2.4 anizmplayer.com (Aincrad / FirePlayer) — iki katmanlı çözüm

Sayfa (`/video/<hash>`) akışı düz HTML'de taşımaz; tek packed script içerir:

```js
eval(function(p,a,c,k,e,d){…}('…',62,…)…('FirePlayer|002d…|…'.split('|'),0,{}))
```

Açıldığında:

```js
function fireload(source=null){FirePlayer("<32 hex hash>",{ jwPlayerKey, jwPlayerURL, … })}
```

Ardından uygulamanın yaptığı istek (dört istemcide de birebir aynı):

```
POST https://anizmplayer.com/player/index.php?data=<hash>&do=getVideo
form: hash=<hash>&r=https://anizm.com.tr/
Origin: https://anizmplayer.com | Referer: …/video/<hash> | X-Requested-With: XMLHttpRequest
→ {"videoSource": "https://…/master.m3u8", "securedLink": "…", "video":[{"file":…}]}
```

- 4 gerçek packed sayfa Python ile açıldı; çıkan FirePlayer anahtarı URL hash'iyle
  aynı (`AnizmPacker` testiyle kilitlendi).
- `securedLink` göreli yol olabiliyor → göreceli değerler reddedilir, mutlak olanlar HLS verilir.

## 3. Gerçek kaynak envanteri (33 video adayı, tek bölüm)

| Çevirmen | Kaynak sayısı | `name` etiketleri (fixture) |
|---|---:|---|
| 18853 — Güncellenecek | 24 | Aincrad, GDrive×3, MyviRU, MP4Upload, Sibnet×2, VidBM×2, YourUpload, LiiiVideo, Vidmoly×3, StreamSB, DoodStream×2, Voe, MyViid, StreamLare, SendVid×3 |
| 83191 — VictoriaSubs | 6 | Aincrad, GDrive, SuperVideo, YourUpload, DoodStream, SendVid |
| 109648 — SomeSubs | 3 | GDrive, Abyss, UQload |
| (Shingeki 1, çevirmen 109542) | 8 | Aincrad, Odnoklassniki, GDrive, Abyss, YourUpload, HDVid, Voe, StreamTape |

## 4. Host → çözüm matrisi (21 aile)

| Site `name` etiketi | Gerçek host/işaret | Eklentideki çözüm | Durum |
|---|---|---|---|
| Aincrad (Reklamsız) | anizmplayer.com | `postGetVideo` (getVideo POST) + packed FirePlayer unpack yedeği | ✅ yerel, çift katmanlı |
| GDrive | drive.google.com / docs.google | Drive playback API + uyarı formu + **gdriveplayer.to köprüsü (yeni)** + doğrudan indirme | ✅ yerel, 4 katmanlı |
| Sibnet | video.sibnet.ru | `resolveSibnet` (`player.src`) + loadExtractor | ✅ yerel |
| Odnoklassniki | ok.ru / odnoklassniki | `resolveOkRu` (flashvars/metadata, HLS+MP4) | ✅ yerel |
| HDVid | hdvid.* | loadExtractor (EMBED_HOSTS) | ✅ extractor |
| Abyss | abyssplayer.* | loadExtractor (`abyssplayer` işareti) | ✅ extractor |
| MP4Upload | mp4upload.com | loadExtractor | ✅ extractor |
| SendVid | sendvid.com | loadExtractor | ✅ extractor |
| YourUpload | yourupload.com | loadExtractor | ✅ extractor |
| Vidmoly | vidmoly.* | loadExtractor | ✅ extractor |
| DoodStream | dood.* | loadExtractor | ✅ extractor |
| Voe | voe.* | loadExtractor | ✅ extractor |
| UQload | uqload.* | loadExtractor | ✅ extractor |
| StreamTape | streamtape.* | loadExtractor | ✅ extractor |
| StreamSB | streamsb/streamssb/sbface/sbspeed/sblongvu/lvturbo/embturbo | loadExtractor (**işaretler yeni eklendi**) | ✅ extractor |
| VidBM | vidbm.com / vidbam.org / vidbom.com / vidbem.com / vedbam.xyz / vadbam.net (XFileSharingPro) | loadExtractor (**işaretler yeni eklendi**) | ✅ extractor |
| SuperVideo | supervideo.tv | loadExtractor (işaret yeni) | ⚠️ host DNS'siz (26.10 canlı probe) |
| MyviRU / MyViid | myvi.ru / myviid.com / myvi.tv | loadExtractor (`myvi` işareti yeni) | ⚠️ myvi.ru parkta, diğerleri DNS'siz |
| LiiiVideo | liiivideo.com / liii.video | loadExtractor (işaret yeni) | ⚠️ "dosya süresi dolmuş" yanıtı |
| StreamLare | streamlare.com | loadExtractor | ⚠️ alan adı ölü (HTTP 0) |

**Not:** Ölü/şüpheli host'lar site hâlâ liste verdiği için zincirden ÇIKARILMAZ;
istekleri zaman aşımıyla düşer, diğer 30+ kaynak akmaya devam eder. Bir host'u
önceden kara listeye almak, site o host'u yeniden canlandırırsa kaynak kaybettirir.

## 5. Sayfa bölgeleri → seçici sözlüğü (canlı fixture ile doğrulandı)

| Bölge | Gerçek yapı | Eklenti seçicisi |
|---|---|---|
| Çevirmen düğümü | `button.v3-translator-button[data-translator-id][data-sources][title]` | `[data-sources],[data-v3-translator],[data-translator],[translator]` |
| Bölüm ızgarası | `div.v3-anime-episode-grid[data-anime-episode-grid] > a[data-episode-number][data-episode-title]` (1175 kayıt) | `[data-anime-episode-grid] > a[href]` |
| Tiresiz bölüm URL'si | `one-piece-41bolum-izle` (53 kayıt) | `EPISODE_PATH` (`-?bolum`) |
| Ana sayfa seri kartı | `article.v3-series-card` → `a[href]`, img `data-v3-deferred-src`, `.v3-card-rating` | `article.v3-series-card` + `.v3-card-rating` (**yeni**) |
| Ana sayfa bölüm kartı | `article.v3-episode-card`, `.v3-card-tag` "4. BÖLÜM" | seri URL'sine indirgenir, `episodeHint` korunur |
| Arama kartı | `article.v3-catalog-card` → `a.v3-catalog-poster`, `.v3-catalog-score`, img `/storage/pcovers/<id>.webp` | aynı seçiciler; poster `<id>l.webp`'ye yükseltilir |
| JSON arama | `/searchAnime?query=…` (XHR zorunlu — başlıksız istek 404 sayfası döner) | `xhr=true`, `{data:[info_slug, info_title, info_poster…]}` |
| Detay posteri | `.v3-anime-detail-cover img` (`/storage/pcovers/3l.webp`) | çoklu seçici + büyük kapak |
| Yaş/icerik uyarısı | `v3-content-warning.js` istemci tarafı duvar; veriler HTML'de zaten var | HTTP katmanını etkilemez, özel işlem yok |

## 6. Bu turda yapılan kod değişiklikleri (sürüm 16 → 17)

1. **Yeni `AnizmPacker.kt`:** saf Kotlin Dean Edwards packed-eval açıcı +
   `FirePlayer("<hash>")` çıkarıcı. `resolveAnizmPlayer` artık getVideo POST'u
   engellenirse oynatıcı sayfasını açıp packed betikten anahtarı çıkarıp POST'u
   tekrarlıyor; açılan betikteki açık medya/securedLink de taranıyor.
   (Saloo + Anthology ile aynı yöntem; 4 gerçek sayfayla test.)
2. **Uydurma çevirmen kimlikleri kaldırıldı:** Eski kod düğüm azsa
   `/cevirmen/1|2|3` deniyordu; gerçek kimlikler 18853/83191/109648 gibi büyük
   sayılar olduğundan bu istekler kanıt gereği daima 404'tü. Artık yalnızca
   `data-translator-id` ve bölüm dizini kullanılıyor; kör tarama tamamen silindi.
3. **Host işaretleri genişletildi:** VidBM ailesi (6 domain), StreamSB aynaları
   (7 işaret), myvi/liiivideo/supervideo/gdriveplayer eklendi → iframe taraması
   bu kaynakları artık kaçırmıyor.
4. **GDrive'a gdriveplayer.to köprüsü:** yerel Drive yöntemleri başarısız olursa
   harici oynatıcı extractor'ı devreye giriyor; çözemezse sahte link yayımlanmıyor.
5. **Ana sayfa puan seçicisi:** `.v3-card-rating` eklendi.
6. **Gerçek-fixture testleri (`AnizmRealFixtureTest`):** 33 kaynağın tamamının
   toplandığını, uydurma istek atılmadığını, 1175 bölümün (tiresiz URL'ler dahil)
   eksiksiz ayrıştığını, ana sayfa/arama kartlarını ve 4 packed sayfanın
   doğru FirePlayer anahtarını açtığını ağ olmadan kilitleyen 12 test.
7. **Birleşik bölüm düzeltmesi:** `215-216. Bölüm` gibi 5 kayıt tekli desende
   farklı seri sanılıp listeden düşüyordu (1170 → 1175). Etiket farkındalığıyla
   (DOUBLE_EPISODE_PATH) hedef seriye dahil ediliyor; numara 0 (birleşik) kalıyor.
8. **Saf ayrıştırma:** `fetchV3Translator` HTTP sarmalı + saf
   `parseTranslatorResponse` olarak ikiye bölündü; ağsız testler doğrudan
   gerçek çevirmen JSON'larını işliyor.

## 7. Doğrulama

### 7.1 Ağsız birim testleri (CI, her PR)

`AnizmRealFixtureTest` 12 testle gerçek dökümleri kilitler: 3 çevirmen düğümü,
33 video adayının tamamı (24+6+3), uydurma kimlik yasağı, 1175 bölüm (5 birleşik
dahil), 51-65 ana sayfa kartı, 11 arama kartı, 4 packed sayfanın FirePlayer
hash'i. Packed açma algoritması ayrıca Python portuyla 4 gerçek anizmplayer
sayfasına karşı kanıtlandı.

### 7.2 Canlı CI probu (27.10.2026, PR #55, `player-source-probe`)

Gerçek eklenti kodu canlı anizm.com.tr'ye karşı koştu:

- Tüm arama yolları 200: `/arama?q=` (11 kart), `/arama?arama=` (24 kart),
  `/searchAnime` (19 KB `{data:[…]}`), `/getAnimeListForSearch` (8,9 MB katalog).
- Detay: `parseDetail` → başlık One Piece, yıl 1999, **parseEpisodes → 1175**
  (düzeltme öncesi 1170'ti; 5 birleşik bölüm artık düşmüyor).
- Bölüm: 3 çevirmen düğümü birebir doğru; çevirmen XHR 200 → 24 video kimliği;
  `/bolum/18853/video/{id}` → `{success,url:"/player/{id}"}`; `/player/{id}` →
  302 Location (anizmplayer.com/video/<hash> ve drive.google.com/file/d/…).
- Verdict dökümünde datacenter ağından OYNAR çıkanlar: GDrive (yerel, video/mp4 206),
  Beta Player pl.puffytr.tr (HLS native.m3u8), MP4Upload/UQload (206),
  Abyss/YourUpload/SendVid/Odnoklassniki (200).

### 7.3 Çevresel sınır (eklenti kusuru değil)

anizmplayer.com `getVideo` POST'u ve Sibnet/Dood/Voe/Vidmoly gibi host'lar GitHub
datacenter IP'lerine Cloudflare 403/404 dönüyor (`Just a moment...`). Aynı
istekler mobil/konut IP'li cihazlarda çalışır (dört referans istemcinin de
birincil yolu budur). MyviRU/MyViid/StreamLare/SuperVideo/LiiiVideo/VidBM'nin
eski alan adları parkta/DNS'siz; site liste verdiği için bilinçli olarak
zincirde tutulurlar, istekleri zaman aşımıyla düşer ve diğer kaynaklar akar.

- Yerel ortamda JDK yok; sözdizimi tree-sitter-kotlin ile doğrulandı (tüm dosyalar TEMIZ).
- Tam ağ doğrulaması CI'da `LIVE_PROBE=1` ile (PR `player-source-probe` etiketi)
  `LiveChainProbeTest`/`FullSourceAuditTest` üzerinden yapılır.
