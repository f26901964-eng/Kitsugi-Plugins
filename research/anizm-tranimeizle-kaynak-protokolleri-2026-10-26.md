# Anizm & TrAnimeIzle — kaynak protokolleri (26.10.2026 ölçümü)

Bu dosya, iki eklentideki yerel çözücülerin **hangi kanıta dayandığını** kayda
geçirir. Her satır canlı HTTP ölçümüyle veya oynatıcının kendi JS paketinden
okunarak doğrulanmıştır; tahmin yoktur.

## Anizm (anizm.com.tr)

Bölüm zinciri (v3):

```
/arama?q=<terim>                       → arama kartları
/bolum/<ep>/cevirmen/<translator>      → {"videos":[{"id","name"}]}
/bolum/<ep>/video/<vid>                → {"url":"…/player/<id>","source":"GDrive"}
/player/<id>                           → 302 Location → gerçek oynatıcı
```

`source` alanı kaynağın türünü **kendisi söyler** (GDrive, Sibnet, Vidmoly,
Aincrad, Beta Player, Sistenn, FireStream, StreamCash, BYSE, FlyF …).

### Beta Player — `pl.puffytr.tr/watch/<hash>`

Oynatıcının script'i:

```js
const masterUrl       = "/stream/<hash>/master.txt";
const nativeMasterUrl = "/stream/<hash>/native.m3u8";
```

`native.m3u8` → `200 #EXTM3U` (12 satır, çoklu kalite). Referer: oynatıcı
sayfası. **Ölçüm: OYNAR.**

### Sistenn ailesi — `sistenn.uns.bio`, `anizm.rpmvid.com`, `anizm.strp2p.com`

Sayfa `https://<host>/#<kod>` biçimindedir. API:

```
GET /api/v1/video?id=<kod>&w=1920&h=1080&r=<anizm-host>
    → hex gövde = AES-128-CBC(JSON)
       key = "kiemtienmua911ca"
       IV  = "1234567890oiuytr"      (ikisi de sabit)
```

JSON anahtarları: `cfNative`, `cf`, `source`, `hlsVideoTiktok`,
`hlsVideoGoogle`, `streamingConfig:{order,adjust}`, `pk:{k,kx}`,
`delivery:{inHouse,capacityToken,retryAfter}`.

* `cfNative` → kendi token'ını taşıyan HLS master.
* `/v4/` yayınlarına `pk` alanından `k`/`kx` eklenir.
* `adjust.<tür>.domain` varsa `/hls/` → `/hlsmod/<domain>/` olur.
* Kapasite doluysa `delivery.inHouse == "saturated"` + `capacityToken` gelir;
  bir kez `&capacityToken=…&capacityTokenExpire=…` ile tekrar denenir.

İstek başlıkları `Origin`, `Sec-Fetch-Site: same-origin`,
`Sec-Fetch-Mode: cors`, `Sec-Fetch-Dest: empty`, `Accept: */*` olmalıdır —
bunlar olmadan uç 404/500 döndürüyor.

**Ölçüm (üç host):** 4 tür (cfNative, hlsVideoTiktok, cf, source) → HLS `200` → **OYNAR**.

Doğrulama: yakalanan `/api/v1/info` gövdesi aynı anahtar/IV ile
`{"title":false,"allowDownload":true,…}` JSON'una çözüldü.

### GDrive

```
/player/<id> → 302 → drive.google.com/file/d/<fileId>/preview
```

İki yol denenir:

1. **Oynatma API'si** (kota dostu):
   `preview` sayfasındaki `AIza[0-9A-Za-z_-]{35}` anahtarı +
   `https://content-workspacevideo-pa.googleapis.com/v1/drive/media/<fileId>/playback?key=…&auditContext=forDisplay`
   → `mediaStreamingData.formatStreamingData.progressiveTranscodes[].url`
   (+ `transcodeMetadata.height`).
2. **Uyarı sayfası formu:**
   `drive.usercontent.google.com/download?id=…&export=download` sayfasındaki
   `<form action>` + gizli `id/export/confirm/uuid` alanları.

`confirm=t` **tek başına yetmez** (HTML döner). Silinmiş dosyalar `404
Not Found` verir — bu bir çözücü hatası değildir.

**Ölçüm:** yaşayan dosyalar `206 video/mp4` → **OYNAR** (5 kaynak).

### FireStream — `firestream.to/e/<slug>`

```html
<script id="token-blob" type="text/plain">HuMM…==</script>
```

```js
fetch('/api/videos/<slug>/resolve', {
  method: 'POST',
  body: JSON.stringify({ blob: blob })
})
```

Yanıt `video.signedVideoUrl` (HLS/MP4) taşır. Token'sız istek
`400 {"error":"Missing token"}` döner. (Ölçüm günü sayfa Cloudflare'den 522
verdi; çözücü hazır, host erişilebilir olduğunda çalışır.)

### StreamCash — `streamcash.to/embed/<id>`

`/api/videos/<id>/resolve` → `{"status":403,"msg":"Missing API key"}`.
Anahtar, sitenin kendi istemcisinde üretiliyor; obfuscated `embed-player.js`
(18 KB) çözülmeden bu host **kapsanmıyor** olarak kalır.

## Ölü / erişilemeyen host'lar (26.10.2026)

| host | durum |
|---|---|
| `myvi.ru` | 200 ama **park sayfası** ("Домен продается") |
| `www.liiivideo.com` | 200 "File is no longer available as it expired" |
| `vadbam.net`, `sblona.com`, `myviid.com`, `supervideo.tv`, `www.myvi.tv`, `vids.st` | **DNS/TLS yok** (HTTP 0) |
| `streamlare.com` | HTTP 0 (alan adı ölü) |
| `vidmoly.me`, `vidmoly.biz`, `voe.sx` | 404 (dosya silinmiş) |
| `upbolt.to` | 403 Cloudflare |
| `anizmplayer.com` (Aincrad) | 403 — **datacenter IP engeli**; cihazda açılır |
| `video.sibnet.ru`, `dood.watch` | 403 — aynı sınıf IP engeli |

## TrAnimeIzle

Site, Cloudflare'in datacenter IP engeli yüzünden CI/sandbox'tan hiçbir
kanaldan okunamıyor (`403 Attention Required!`). İstemci sözleşmesi 20.07.2026
arşivindeki `js/site.js`'ten birebir alınmıştır:

```
POST /api/fansubSources  {EpisodeId, FansubId}   → #sourceList HTML
POST /api/sourcePlayer/<id>                      → {source, contributerNotes}
POST /api/preVideoAd/<id>                        → HTML → #videoPlayer
```

Eklenti bu sözleşmeyi uygular; kaynak host'ları (Gdrive, Mega, SendVid, Voe,
Ok.ru, Sibnet, MailRu, Yandex, Streamtape, Vudeo/Upstream, Hdvid, OpTraco,
Hexload, luffytra) için yerel çözücüler + uygulamanın extractor'ları kullanılır.
