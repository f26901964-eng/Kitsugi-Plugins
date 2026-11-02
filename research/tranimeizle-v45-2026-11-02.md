# TrAnimeIzle v45 — sıfırdan çekirdek, doğrulanmış sözleşme (02.11.2026)

Bu sürüm, v17–v44 arasında biriken savunma katmanlarını kaldırıp eklentiyi
**sitenin ölçülmüş sözleşmesi** üzerine yeniden kurar. Aşağıdaki her satır bir
kanıta bağlıdır; kaynağı belirtilmeyen davranış yoktur.

## 0. Kanıt

| Kanıt | İçerik |
| --- | --- |
| Cihaz dökümleri (`home`, `cap`, `det`, `ep`) | Ana sayfa, Bot Kontrol, AoT detay, AoT 1. bölüm sayfalarının ham HTML'i |
| `/js/site.js` arşivi (22.09.2026 + 31.08.2026) | `fansubSources → sourcePlayer → preVideoAd` zinciri, `preVideo_<eid>` tekrarı |
| `/js/icon-captcha.min.js` (IconCaptcha **v2.5.0**) | `{cID, rT:1, tM}` → `validationPath?cid=&hash=` → `{cID, pC, rT:2}` |
| `research/tranimeizle-html-haritasi-2026-10-28.md` | Sayfa/uç/host tablosu |
| Aniyomi `yuzono/anime-extensions`, CloudStream `UmayTrade/MyTV` | Bağımsız istemcilerin aynı seçicileri kullanması |

> Site veri merkezi IP'lerine Cloudflare **Error 1005** döndürüyor; bu yüzden
> canlı HTML bu ortamdan okunamıyor. Aşağıdaki HTML satırları **cihazdan alınan
> dökümlerden** birebir alınmıştır.

## 1. Ölçülen HTML (v44'te yanlış varsayılanlar)

| Ne | Ölçüm | v44'ün varsayımı |
| --- | --- | --- |
| Detay başlığı | `div.playlist-title h1` = `Attack on Titan İzle` | doğruydu |
| Detay bilgi tablosu | **`dd` = etiket, sonraki `dt` = değer** (`Yapım yılı`, `Bölüm Süresi`, `Fansublar` …) | `Süre`, `Yapım Yılı`, `Durum`, `Puan` etiketleri **yok** |
| Puan | yalnızca 5 adet `i.fa-star` (sayısal değer yok) | `span.dt_rating_vgs` / `div.rating` |
| Toplam bölüm | `p.animeDetail-desc` = `Toplam 25 / 25 ( 24 dk )` | `dd Bölüm Sayısı` (var ama her sayfada değil) |
| Bölüm kartı | `li.episodeBtn[data-slug="attack-on-titan-1-bolum-izle"]`, içinde `p.title > span` + `small.author` (tarih) + `img.thumb` | `<a href>` bekleniyordu |
| İkinci bölüm listesi | aynı sayfada `<a href>…<div class="episode-li">` kopyası | — |
| Bölüm sayfası | `input#EpisodeId` = 566, `.fansubSelector[data-fid=302]` | doğruydu |
| `animeWatch.initialize` | taban adres **`.io`** (`https://www.tranimeizle.io/`) | adres buradan kurulmamalı |
| Çok sezonlu adres | `/beyond-the-timescape-2-sezon-20-bolum-izle` | `toSeriesUrl` yanlış slug üretiyordu |

## 2. Kod değişiklikleri

| Dosya | Değişiklik |
| --- | --- |
| `TrAnimeIzle.kt` | `load()`: bölüm sayfasından seri sayfasına geçişte üç aday (breadcrumb → slug kuralı → sezon eki atılmış adres), bölüm listesi veren ilk aday kullanılır. Tek katmanlı çekirdek: tarayıcı başlıkları (`user-agent` **küçük harf** → NiceHttp varsayılanını ezer), süreç düzeyinde paylaşılan çerez kavanozu (`TrCookieJar`), her istekte captcha kapısı tespiti + çözüm + **tek** tekrar, 20/15/10 sn zaman aşımları |
| `TrAnimeCaptcha.kt` | IconCaptcha v2 mantığı **saf Kotlin**: yanıt dizge/nesne/sarmal biçimlerinin tümü, MD5 ile "farklı olan" ikon, tema okuma, kapı işaretleri |
| `TrAnimeIzleSite.kt` | Bölüm adı `N. Bölüm`, `p.title > span`, `Toplam N` yedeği, `Yapım` etiketi |
| `TrAnimeIzleMatch.kt` | `SEASON_SUFFIX`: `-2-sezon` / `-sezon-2` ekleri `seriesKey` içinde atılır; `alternateSeriesUrl` sezon eki **atılmış** seri adresini yedek olarak üretir |
| `TrAnimeIzleStreams.kt` | `episodeId` yoksa `fansubSources` çağrısı **atlanır** (0 ile gidip boş dönüyordu); kaynak bütçeleri 75→30 sn / 25→15 sn |

## 3. Captcha akışı (sitenin kendi protokolü)

```
POST /api/Captcha/            form {cID:0, rT:1, tM:dark}      → 5 ikon kimliği
GET  /api/Captcha/?cid=0&hash=<id>   ×5                        → 5 PNG
      dördü aynı, biri farklı → MD5'i tek olan seçilir
POST /api/Captcha/            form {cID:0, pC:<hash>, rT:2}    → oturum doğrulanır
```

* `cID` widget sırasıdır (0, yedek 1), `tM` sayfadaki `captchaTheme` değeridir (`dark`).
* "Farklı olan" ikon **yalnızca MD5** ile bulunur; boyut/uzunluk gibi kaba ipuçları
  yanlış ikon seçip oturumu işaretleyebildiği için kullanılmaz (Aniyomi/MyTV de böyle).
  Gruplama ayrışmazsa yeni bir captcha istenir.
* Form gövdesi **`FormBody`** ile gönderilir: NiceHttp'in `data` haritası
  `addEncoded` kullanıyor (değerleri önceden kodlanmış sayar) ve hash'lerde
  bozulma riski taşır.
* Çerez kavanozu sınıf düzeyinde olduğu için captcha **bir kez** çözülür; sonraki
  istekler aynı oturumu kullanır.

## 4. +18 uyarısı

Site uyarıyı **istemci tarafında** (modal + `localStorage`) tutuyor; dört sayfa
dökümünde de `18+`/yaş kapısı markası yok (yalnızca `hataBildir` modalı var).
Eklenti HTML çalıştırmadığı için uyarı hiç görünmez. Sunucu taraflı bir yaş
kapısına düşülürse `TrAgeGate` formu otomatik onaylar.

## 5. Doğrulama

* `TrAnimeIzleRealMarkupTest` — **gerçek** kart/detay/bölüm/captcha HTML'i ile
  5 test (seçiciler bozulursa kırılır).
* `TrCaptchaTest` — yanıt biçimleri, "farklı olan" ikon, tema, kapı işaretleri.
* Derleme + JUnit: PR CI'ında (`Derleme Testi`) — v45 için **yeşil** (3 dk 48 sn).
* Canlı oynatma cihazda doğrulanmalı: `adb logcat | grep kraptor_TrAnime`.
