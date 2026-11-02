# Dizipal — canlı sözleşme ölçümü (03.11.2026)

Bu klasör, `DiziPal` eklentisinin **gerçek site sözleşmesinin** ölçüldüğü kayıtları
tutar. Seçiciler tahminle değil ölçümle yazıldı; aynı ölçümler
`tools/dizipal_dump.py` ve `tools/dizipal_player_probe.py` ile yeniden üretilebilir.
Doğrulama örnekleri `DiziPal/src/test/resources/` altındadır ve
`./gradlew :DiziPal:test` ile çalışır.

## Neden yeniden yazıldı

Eklenti, sitenin **eski PHP temasını** hedefliyordu:

| eski varsayım | gerçek (03.11.2026) |
|---|---|
| `div.episode-item` | yok — Next.js/Turbopack, RSC akışı |
| `article.type2 ul li` | yok — kartlar `a.tc` |
| `POST /api/search-autocomplete` | yok — arama `/arama-yap?s=` + `POST /api/mj` (`action: searchContent`) |
| dizi sayfası `/dizi/<slug>` | `/series/<slug>` (film: `/movies/<slug>`) |
| bölüm `/dizi/<slug>/<s>-sezon-<n>-bolum` | `/bolum/<slug>-<s>x<e>` |

Sonuç: ana sayfa, arama, detay ve oynatıcı **tamamen boş** dönüyordu.

## Rotalar (ölçüldü)

| rota | ne verir |
|---|---|
| `/` | ana sayfa blokları (trend, güncel bölümler, popüler) |
| `/trendler` | 64 dizi kartı, tek sayfa (`hasMore:false`) |
| `/yeni-eklenen-dizi-bolumler` | bölüm akışı (`a.ecl`/`a.trk` → `/bolum/…`), RSC'de `items[].sHref` = dizi adresi |
| `/yabanci-dizi-izle`, `/film-izle` | liste (kart: `a.tc`) |
| `/platform-bolumler` | platform çipleri + kartlar |
| `/kategori/<slug>` | `aksiyon-macera`, `bilim-kurgu-fantazi`, `dram`, `gizem`, `komedi`, `suc` |
| `/arama-yap?s=<q>&tip=<hepsi\|dizi\|film\|oyuncu>` | SSR arama sonuçları (`h2` "Diziler · n" + `div.g6` kart ızgarası) |
| `/series/<slug>` | künye + `seasons[].episodes[]` |
| `/movies/<slug>` | film künyesi |
| `/bolum/<slug>-<s>x<e>` | oynatıcı (şifreli) |

Sayfalama: liste sayfalarının RSC'sinde `"page":n`, `"hasMore":bool`,
`"pageHref":["/…","/…?sayfa=2",…]` bulunur — eklenti `pageHref` dizisini izler.

## Kart biçimi (SSR DOM)

```html
<a class="tc" aria-label="Nocturne, 2026, Suç" href="/series/nocturne">
  <span class="fr">
    <span>SUÇ</span>                          <!-- tür şeridi -->
    <span><img src="https://images.cdnhipter.xyz/…jpg"></span>
  </span>
  <span>
    <span class="tt">Nocturne</span>          <!-- başlık -->
    <span>2026 · Suç</span>                   <!-- yıl · tür -->
  </span>
</a>
```

Bölüm akışı kartı: `a.ecl` (tam kart) ve `a.trk` (altyazı/dublaj çipi), ikisi de
`/bolum/…` adresine gider; dizi adresi RSC'deki `items[].sHref` alanındadır.

## Dizi künyesi (RSC)

```json
{"id":8405,"title":"Gassal","altTitle":"","path":"/series/gassal-c11",
 "slots":{"mjTitle":"Gassal","mjYear":"2024","mjPoster":"…","mjOverview":"…",
          "mjGenres":[{"label":"Komedi","href":"/kategori/komedi"}],"mjPlatform":"tabii"},
 "seasons":[{"id":3057,"no":1,"label":"1. sezon","episodes":[
    {"id":386373,"n":1,"title":"Ben, Gassal","date":"27 Ara 2024",
     "href":"/bolum/gassal-1x1-c09","min":34,"dub":true}]}]}
```

## Oynatıcı zinciri (kritik)

Bölüm sayfasındaki `<iframe data-player-frame>` **src'siz** gelir; adres istemcide
şifre çözülerek yazılır:

```json
"player":{"kind":"episode","contentId":400473,"title":"Gassal 4. Sezon 1. Bölüm",
  "sources":[{"label":"PUB+ · Altyazısız","quality":"1080P","href":"/bolum/gassal-4x1","on":true,"dub":false}],
  "language":"Altyazısız",
  "enc":{"ciphertext":"<b64>","iv":"<hex32>","salt":"<hex512>"},
  "k":"<b64 200>"}
```

Site tarafındaki çözme (`/_next/static/chunks/2vttktfk_mjfx.js`):

```js
key = PBKDF2(SHA-512, password = k, salt = hex(enc.salt), iterations = 999, dkLen = 32)
url = AES-256-CBC(iv = hex(enc.iv)).decrypt(base64(enc.ciphertext))
```

Örnek (ölçüldü): `//sn.dplayer82.site/iframe.php?v=e7ca258235a732527eb60a47639601dc`.
Eklenti aynı işlemi `DiziPalParser.decryptPlayer` ile Kotlin'de yapıyor; birim testi
bu adresi birebir doğruluyor.

## Erişim notları

* Site **veri merkezi IP'lerine** Cloudflare 403 döndürüyor; bu yüzden CI'dan canlı
  ölçüm yalnızca `translate.goog` vekili üzerinden yapılabildi (HTML aynı).
* Kullanıcı dökümleri (kendi tarayıcısından) Drive'dan alınıp `tools/dizipal_dump.py`
  ile döküm dalına itildi; RSC gövdesi vekilden de birebir okunuyor.
* Oynatıcı alan adı (`*.dplayer82.site`) de veri merkezi IP'lerine 403 veriyor;
  bu yüzden m3u8 seçimi **genel** kurallarla yapılıyor: `file/src/source/url` +
  `.m3u8`, `<source>`, iç `<iframe>` (bir seviye), `atob()` ile gömülü yapılandırma
  ve son çare `loadExtractor`. Cihazda (residans IP) oynatıcı normal açılıyor.

## Bilinen sınırlar

* Alan adı sık değişiyor (`dizipal1593.com` ölçüm tarihinde geçerliydi); değişince
  `DiziPal.kt` içindeki `mainUrl` güncellenmeli.
* `iframe.php` sayfasının ham HTML'i veri merkezi IP'sinden alınamadığı için m3u8
  biçimi **genel** kalıplarla çıkarılıyor; kalıp dışı bir oynatıcıya geçilirse
  `tools/dizipal_player_probe.py` yeniden çalıştırılıp kalıp güncellenmeli.
