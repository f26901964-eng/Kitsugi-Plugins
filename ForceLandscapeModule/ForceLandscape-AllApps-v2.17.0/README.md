# Force Landscape - All Apps  |  v2.17.0

Xiaomi Pad 6 (pipa) · MIUI 14 / Android 13 · SukiSU Ultra

Dikey çalışan uygulamaları **tam ekran yataya** zorlar, kendi kendine yatay
çalışan oyunlara/video uygulamalarına **dokunmaz**. Arayüz **WebUI**.

## WebUI nerede?

**SukiSU Ultra → Modüller → Force Landscape → WebUI**

| Sekme | Ne var |
|---|---|
| **Durum** | Büyük sayaçlar (letterbox çözülen / kalan / hatalı), yön modu, ekran, katman testi. Uyarı bandı: katman çalışmıyorsa sebebi söyler. Hızlı işlemler: *Şimdi uygula · Letterbox'ı onar · Katmanı test et · Rapor+log indir · Duraklat* |
| **Uygulamalar** | Arama + filtre (Hepsi / Yatay / Otomatik / **Dikey** / **Bekleyen**). Her uygulamada **Yatay · Dokunma · Oto**, ayrıca **⚡ hemen uygula** ve **⏹ uygulamayı kapat**. Toplu: “filtredekileri yatay yap”. |
| **Letterbox** | Katmanın canlı durumu: uygulandı/kalan/hata, **Onar** düğmesi, **paket bazlı sonuç listesi** ve **son hatalar** kutusu. |
| **Log** | 200/500/2000 satır, **canlı takip** (3 sn), kopyala, `/sdcard/Download`'a kaydet, temizle; altta katman testinin ham çıktısı. |
| **Ayarlar** | Mod (Akıllı / Sadece seçtiklerim / Hepsi / Kapalı), yön (Sensör / Kilit / Kapalı), uygula, onar, rapor, seçimleri kopyala, duraklat/devam, geri al, yeniden başlat. |
| **Yardım** | Katlanır bölümler: “hâlâ dar sütun”, “oyun bozuldu”, “test ÇALIŞMIYOR”, dosya yolları, geri dönüş, dürüst sınırlar. |

Action düğmesi de duruyor: kısa özet + katman testi yazar (WebUI açılmadan bakmak için).

## v2.8.1 — logolar

Uygulama simgeleri gelmiyordu; üç hata üst üste binmişti:
(1) `ksu://icon/<paket>` isteği hiç yapılmıyordu — kod önce `ksu.getPackagesInfo`
varlığına bakıp "destekleniyor mu" kararı veriyordu ve SukiSU Ultra'nın WebUI
köprüsünde bu fonksiyon yok; (2) `loading="lazy"` yüzünden liste yeniden çizilirken
resim istekleri başlamıyordu; (3) hangi şemanın çalıştığı hiç ölçülmüyordu.
Artık açılışta `ksu://icon` ve `mui://icon` görünmez birer prob ile ölçülür,
çalışan kullanılır, olmazsa harf kutusu (kırık resim asla görünmez). Durum
sekmesinde **logo motoru** satırı + **🔍 logo testi** düğmesi var.
Ayrıca onay penceresi dışarı tıklanınca asılı kalan promise hatası düzeltildi.

## v2.8'de ne düzeldi (cihaz logu + bugreport kanıtlı)

Sahadaki `force_landscape_20261029-194456.log` ve `bugreport` tek tek tarandı.
Bulunan **6 gerçek hata** ve yapılan düzeltmeler:

| # | Hata (kanıt) | v2.8'de |
|---|---|---|
| 1 | Uygulama ekranın **ortasında** kalıyordu: `MiuiEmbeddingWindow: MID_BOUND=SkRegion((719,0,2162,1800))`, `MiuiActivityEmbeddingController: ... in middle`, `ro.config.miui_activity_embedding_enable=true`. Bu, AOSP letterbox'ı **değil**; compat/oran ayarları bunu kaldırmıyor. | `resetprop -n ro.config.miui_activity_embedding_enable false` + modül `system.prop`; `post-fs-data.sh` açılışta yazıyor. WebUI **Durum** sekmesinde anahtar + gerçek durum rozeti. |
| 2 | "Ekrana yay" seçilse bile yaymıyordu: `webui: ekrana-yay AC ... -> MIN:am-nokill LARGE:am-nokill P-ONLY:am-nokill` yazıp **doğrulama yapmadan** başarı sayıyordu. | Her uygulamada `am compat state` ile **sistemden doğrulama**; tutmazsa `fail` yazar. Sağlayıcı patlarsa `cmd platform_compat`'a düşer. |
| 3 | Uygulamaya özgü zorlama **kapatılamıyordu**: `webui: ... -> never` yalnızca listeye yazıyor, uygulanmış katmanı geri almıyordu. | `never`/`auto` seçilince `fl_revert_pkg`: letterbox + ekrana-yay katmanı geri alınır, listelerden çıkarılır, uygulama durdurulur — **anında**. |
| 4 | `fl_embed_check` hep `?` dönüyordu (paket adı ile `MID_BOUND` **aynı satırda** aranıyordu; gerçek dökümde ayrı satırda). Bu yüzden "ortada kaldı" hiç tespit edilemiyordu. | Pencere **blok blok** okunur (awk). `pencere|orta|1443x1800` / `pencere|tam` / `pencere|?`. |
| 5 | Sahte başarı: `am compat ... -> Failed transaction (2147483646)` hata sayılmıyor, 87/87 "uygulandı" rakamı state dosyasından geliyordu. Ayrıca tur sonunda yanlış `DIKKAT - hedef var ama tek paket islenmedi` uyarısı çıkıyordu (kuyruk zaten boşken). | Hata desenleri genişletildi, `fl_change_state` ile gerçek durum okunuyor; uyarı yalnızca **gerçekten** kalan paket varsa yazılıyor. |
| 6 | Eski hatalar (11:45–11:54, 16:34) 19:44 raporunda **güncel** gibi görünüyordu. | Başarılı turda `compat_errors.txt` sıfırlanır; raporda yalnız o turun hataları. |

Yeni komutlar (WebUI arka ucu): `verify <paket>`, `revert <paket>`, `miui-embed off|on`.

## v2.1'de düzeltilen asıl hata

Sahadaki v1.9/v2.0 logunda **letterbox katmanı hiç uygulanmamıştı**
(`compat paket: 0`, `kalan: 94`) ve *neden* uygulanmadığı logda yazmıyordu:
test başarısız olunca katman sessizce atlanıyor, tek bir hata bile kaydedilmiyordu.

v2.1'de:

1. Her paket **3 yöntemle** denenir:
   `am compat enable --no-kill` → `am compat enable` → `cmd platform_compat enable`
2. İlk 3 pakette ve tur sonunda sonuç **sistemden doğrulanır**
   (`dumpsys platform_compat`); komut “başarılı” dese bile sistemde görünmüyorsa hata sayılır.
3. Her paketin sonucu `compat_status.txt`, son 20 hata `compat_errors.txt` dosyasına yazılır
   ve WebUI'de **görünür**.
4. Döngü hatada sessizce kesilmez: üst üste 8 hatada durur ve bunu yazar.
5. Süre bütçesi testten **sonra** başlar; tur başına en az 6 paket garanti.
6. Açılışta `system_server` kararlı değilse (sahadaki logda 22 saniyede 4 kez
   yeniden başlamıştı) not düşülür ve işlem yine de yapılır.

## Karar mantığı (tek nokta)

```
never.txt (Dokunma)        → dokunma
sistem paketleri           → dokunma
force.txt (Yatay)          → zorla          ← WebUI'de “Yatay”
yerleşik 22 oyun listesi   → dokunma        (zorla dersen yine zorlanır)
mod: akıllı     → yönü dikey/bilinmiyor olanlara zorla
     seçtiklerim→ sadece force.txt
     hepsi      → iki “dokunma” hariç herkes
     kapalı     → compat katmanı durur (yön katmanı çalışır)
```

Her kararın gerekçesi `webui.sh apps` çıktısının 5. sütununda döner
(“sen seçtin”, “oyun listesinde”, “kendi dikey çalışıyor” …).

## Dosyalar

| yol | ne |
|---|---|
| `/data/adb/force_landscape/config.conf` | ayarlar (WebUI okur/yazar) |
| `/data/adb/force_landscape/force.txt` | “Yatay” listesi |
| `/data/adb/force_landscape/never.txt` | “Dokunma” listesi |
| `/data/adb/force_landscape/compat_applied.txt` | uygulandığı doğrulanmış paketler |
| `/data/adb/force_landscape/compat_status.txt` | paket → ok/hata + yöntem (v2.1) |
| `/data/adb/force_landscape/compat_errors.txt` | son 20 hata (v2.1) |
| `/data/adb/force_landscape/landscape.log` | modül logu |
| `/data/adb/force_landscape/state.txt` | son uygulama özeti |
| `/sdcard/force_landscape_rapor.txt` | tanı raporu |
| `/sdcard/Download/force_landscape_<tarih>.log` | paylaşılabilir rapor+log |

## Geri dönüş

- **Duraklat**: WebUI → Ayarlar → Duraklat (veya `touch /data/adb/modules/force_landscape_all/disable` + reboot)
- **Tümünü geri al**: WebUI → Ayarlar → Tümünü geri al (compat overrides `am compat reset` ile silinir)
- **Kaldır**: yöneticiden kaldır; XML yedeği `.fl.bak`'tan geri konur

## Sınırlar (dürüst)

- Android 13'te uygulama bazlı **yön** override'ı yok; “Yatay” seçimi resize izni verir,
  yönü ekran katmanı belirler.
- Compat katmanı ROM'a bağlı. `am compat` engellenirse XML yedeği kullanılır ve
  **bir sonraki açılışta** etkili olur.
- Tam ekrana yayılmayı desteklemeyen oyunlara zorlanmaz (yerleşik 22 oyun + “Dokunma”).

## v2.2'de WebUI için yapılan düzeltmeler (saha bulgularına göre)

Saha logu şunu gösterdi: `compat paket: 0 · kalan: 0` ve `compat_applied.txt` 0 bayt.
Yani letterbox katmanı cihazda hiç uygulanmamış; ayrıca uygulama listesi WebUI'de
boş geliyordu. Nedenleri ve çözümleri:

| Belirti | Kök neden | v2.2 çözümü |
|---|---|---|
| Hiçbir uygulama görünmüyor | Liste her açılışta 100+ paket için tek tek `pm`/`dumpsys` çağırıyordu; yavaş cihazda köprü zaman aşımına takılıp boş dönüyordu | Liste `apps_cache.txt` içinde önceden hesaplanır; WebUI sadece okur (~0.05 s) |
| Aşırı geç açılıyor | Açılışta `status` (wm+dumpsys+logcat) ve tam paket taraması seri çalışıyordu (sim: 2,5 s + 2,5 s) | `status` artık `state.txt`'ten okur; canlı ölçüm yalnız "Yenile"de (`status-full`) |
| Log sayfası çalışmıyor | Log sekmesi açılırken ağır `test-ham` çıktısı aynı çağrıda alınıyor, hata olursa tüm sayfa düşüyordu | Log ayrı, ham test ayrı düğme; hata yakalanıp kutu içinde gösterilir |
| `force: 0 / never: 0` satırı bozuk | Sayaç biçimlendirmesi | Düzeltildi |
| `state.txt.tmp` artık kalıyor | Yeniden adlandırma yarıda kalıyordu | `state.txt.tmp` her yazımdan sonra silinir |

Ek olarak: uygulama listesi hata verirse "Yeniden oluştur" düğmesi (`apps-refresh`)
ve sebep metni gösterilir; mod değişince liste kararları otomatik tazelenir.

## v2.3 — WebUI baştan yazıldı

Saha geri bildirimi: "cihaz bilgisi düzgün gelmiyor, uygulama bilgisi yok, log çalışmıyor".
Nedenleri ve çözümleri:

| Sorun | Kök neden | v2.3 |
|---|---|---|
| Cihaz bilgisi eksik/yanlış | Durum ekranı yalnız `state.txt`'ten okuyordu; o da sadece "Uygula" sonrası yazılıyordu | Yeni **Cihaz** sekmesi: model, ROM/MIUI, çekirdek, ABI, SELinux, RAM, /data, pil+sıcaklık, ekran, açılış süresi — hepsi canlı tek çağrıda |
| Uygulamalar sadece paket adı | Liste yalnız paket/karar içeriyordu | Her satırda okunabilir ad, sürüm, boyut, badge'ler; **ⓘ** ile sürüm/hedefSDK/kurulum tarihi/APK yolu/çalışan PID/başlatıcı |
| Aç/kapa yok | — | **Uygulamayı aç / Durdur / ↻ Yeniden başlat** (ayarın etkili olması için gerekli) |
| Log sayfası çalışmıyor | Tek `tail` çağrısı + sekme her yenilendiğinde içerik sıfırlanıyordu | 6 kaynak (modül/hatalar/paket durumu/sistem logcat/test/yön), satır sayısı, arama, kopyala, dosyaya kaydet, **log tanısı**; `tail` bağımlılığı kaldırıldı (saf awk) |
| "Uygula" ekranı kilitliyordu | 96 paket tek çağrıda, süre 1-2 dk | **Parçalı uygulama**: 12'lik dilimler + ilerleme çubuğu; sayfa donmaz, "kalan" canlı düşer |
| 8 hatada tüm tur iptal | `break` | Hata → yöntem değiştir (am-nokill → kalıcı enable → platform_compat) → 20 hatada dur, XML yedeği hazırla, hata metinlerini sakla |
| Otomatik yenileme odak/scroll bozuyordu | 8 sn'de tüm sekme yeniden çiziliyordu | Otomatik yenileme yalnız başlık rozetlerini günceller; sekme içeriğine dokunmaz |
| Dosya izinleri | `compat_errors.txt` gibi dosyalar 0600 idi | Tüm durum dosyaları 0644 (`fl_perms_fix`) |

Ek olarak: Tanı ekranı (13 kontrol, tek ekran), yedek al/yükle, toplu seçim
("görünenleri zorla/bırak"), sıralama, filtre çipleri, karanlık/aydınlık tema.

## v2.4 — WebUI'nin "görünmez" çalışma hatası düzeltildi

WebUI'nin kaynağını (KernelSU `WebViewInterface.kt`, `WebViewHelper.kt`) okuyup dört
gerçek uyumluluk hatası buldum ve kapattım:

| Bulgu | Neden önemliydi | v2.4 |
|---|---|---|
| `ksu.exec(cmd)` **senkron** ve JS iş parçacığını bloklar | "Uygula" 12 pakette 8–10 s duruyordu: dönen ikon, ilerleme çubuğu, tıklama **hiç çalışmıyordu** → "dondu" hissi | `exec(cmd, opts, callback)` asenkron imzası kullanılır; arayüz **hiç donmaz**, ilerleme canlı akar |
| Bazı yöneticiler string, bazıları `{errno,stdout,stderr}`, bazıları Promise döner | Tek tip kabul etmek "boş çıktı" demek | Açılışta **zararsız `ping`** ile köprü tipi tespit edilir; uygun yol seçilir (asenkron → senkron → promise). Desteklenmiyorsa ekranda açıkça yazar |
| `window.confirm()` WebView'de desteklenmeyebilir (sessizce `false` döner) | "Tümünü geri al" gibi düğmeler **hiçbir şey yapmıyormuş** gibi görünür | Kendi onay penceremiz yazıldı; kodda `confirm(` çağrısı yok |
| Sayfa `https://mui.kernelsu.org` üzerinden servis edilir, `location.pathname` gerçek yol değil | Sabit dizin yolu yanlış olursa her komut başarısız | Dizin `ksu.moduleInfo().moduleDir`'den alınır |
| `pm list packages -3` bazı ROM'larda boş | "Hiçbir uygulamayı görmüyor" | 4 kademeli yedek: `-3` → `-3 --user 0` → `cmd package list packages -3` → `pm list packages` |

Ayrıca **gerçek uygulama adı ve ikonu**: yönetici `getPackagesInfo` veriyorsa
uygulamanın kendi adı ve ikonu (`ksu://icon/<paket>`) kullanılır; vermiyorsa
yerleşik isim sözlüğü + harf avatar devreye girer. Durum sekmesinde hangi köprünün
kullanıldığı yazar.

## v2.5 — "87 paket" saha hatasının KÖK NEDENİ bulundu ve kapatıldı

Cihazdan gelen bugreport (`logcat.txt`) incelendi. Kanıt:

```
avc: denied { read } for path="/data/adb/force_landscape/compat_queue.txt"
     scontext=u:r:system_server:s0 tclass=file permissive=0
cmd: Failure calling service activity:   Failed transaction (2147483646)
cmd: Failure calling service platform_compat: Failed transaction (2147483646)
```

**Ne oluyordu:** Paket listesi `while ... done < compat_queue.txt` ile okunuyordu.
Döngünün içinden çağrılan `am` / `cmd` / `dumpsys` süreçleri **stdin olarak bu
dosyayı devralıyordu**. `system_server` alanında çalışan bu süreçler `/data/adb`
altını okuyamadığı için SELinux okumayı reddediyor, binder çağrısı
"Failed transaction" ile düşüyordu. Sonuç: **88 paketten 1'i uygulanıyor,
87'si "kalan" kalıyordu.**

**Çözüm:** Tüm sistem komutları (`am`, `cmd`, `pm`, `dumpsys`, `settings`, `wm`,
`device_config`) artık `</dev/null` ile çalışıyor. Ayrıca kuyruk döngüleri
`for` döngüsüne çevrildi (ikinci savunma katmanı). `fl_restore` içindeki
`am compat reset` döngüsü de aynı hatadan etkileniyordu, o da düzeldi.

Aynı turda bulunan küçük hatalar:

| Hata | Etki | Düzeltme |
|---|---|---|
| Log satırı `cut -f2` ile paket adını basıyordu | "compat HATA com.x -> com.x" (hata metni kayıp) | `cut -f3-` → gerçek hata metni |
| Tur kesildiğinde tek satır ipucu | Sebep belirsiz kalıyordu | Ayrı satırda son hata metni + Tanı yönlendirmesi |
| İlk uygulamadan önce Durum sekmesi boştu | Ekran/ignore alanları boş görünüyordu | `state.txt` boşsa **canlı** değer okunur |

## v2.7 — "ekrana yay" modu + cihaz bilgisi ve logo düzeltmeleri

Sahadan gelen ekran görüntüleri (v2.5 kurulu) ve iki modül logu satır satır
incelendi. Bulunan ve kapatılan hatalar:

| Bulgu | Kök neden | v2.7 çözümü |
|---|---|---|
| Cihaz sekmesi tamamen `—`, `Android —(API —)`, `NaN °C`, `Ekran yönü ?` | `boot()` **`loadSys()` çağırmıyordu**; `SYS` boş nesneyle çiziliyordu | Açılışta `loadSys()`; ayrıca Cihaz sekmesi boşsa kendisi yükler |
| Eksik alanlar çöp değerle yazılıyordu | `g()` yardımcısı boş değeri `—` yapıp satırı yine çiziyordu | Değer yoksa **satır hiç çizilmez**; hiç değer yoksa uyarı + tek "oku" düğmesi |
| Sıcaklık `NaN °C` | `Number("")/10` | `parseInt` + `isNaN` kontrolü, `>250` ise /10 |
| Uygulama listesinde harf kutusu | Logo yalnız `ICONS && MG[pkg]` iken deneniyordu; WebUIX'te `listPackages` yok | Logo **koşulsuz** denenir: `ksu://icon/<pkg>` → `mui://icon/<pkg>` → harf |
| Satır düğmeleri okunaksız (`⚡ ⏹ ⓘ`) | Sadece emoji, etiket yok | İkon + alt etiket: `↔️ yatay`, `⤢ yay`, `🚫 bırak`, `ℹ️ bilgi` |
| Tanı raporunda `uygulanan mod: unknown`, `ignore-orientation: unknown` | Rapor `tani`/`report` ayrı kabukta üretiliyor, `FL_MODE` orada boş | Rapor `state.txt`'ten son bilinen değeri okur |
| Arayüzde `v2.3` / `v2.5` etiketleri | Eski sabit metinler | Hepsi `v2.7` |

### Yeni: uygulama başına "EKRANA YAY" (komple yatay / tam ekran)

Her uygulama satırındaki **⤢ yay** düğmesi (ve ayrıntı penceresindeki aynı düğme)
o uygulamanın letterbox bantlarını kaldırır:

| Katman | Ne yapar |
|---|---|
| `OVERRIDE_MIN_ASPECT_RATIO` | Kapıcı — oran override'larının hepsi buna bağlı |
| `OVERRIDE_MIN_ASPECT_RATIO_LARGE` | Uygulamanın "en fazla 16:9" sınırını kaldırır → ekran boydan boya dolar |
| `OVERRIDE_MIN_ASPECT_RATIO_PORTRAIT_ONLY` | **kapatılır** (yatay tutarken override işlesin) |
| `OVERRIDE_ANY_ORIENTATION_TO_USER` | Android 14+ : yön + boyut + oran kısıtlarını komple kaldırır |

- Üç yöntem sırayla denenir: `am compat --no-kill` → `am compat --no-kill` (sondaki bayrak biçimi) → `cmd platform_compat`.
- Başarısız olursa **liste dışına alınır**, uygulama eski hâlinde kalır; hiçbir şey bozulmaz.
- Ayar anında geçerli olsun diye uygulama kapatılır (`am force-stop`) — bir sonraki açılışta tam ekran.
- Geri alma: her change id için `am compat reset` + `PORTRAIT_ONLY` varsayılana döner.
- Liste: `/data/adb/force_landscape/appmode.txt` · durum: `appmode_state.txt`
- Toplu geri alma: WebUI → Ayarlar → **Geri al** (`restore`) ekrana-yay katmanını da sıfırlar.

### Komutlar (webui.sh)

```sh
webui.sh appmode <paket> on|off     # tek uygulama aç/kapat (OK|paket|on|yöntem)
webui.sh appmode-list               # #appmode|<sayı> + paket|durum satırları
webui.sh apps                       # 10. sütun artık "ekrana yay" (0/1)
```

### Test

`node uitest/run27.js` → 66 yeni kontrol (cihaz bilgisi, logolar, düğmeler,
appmode aç/kapat, apply entegrasyonu, tanı raporu, geri alma, sözdizimi).
Eski paketler: `run24.js` (151), `run25.js` (432), `run25b.js` (7) → toplam **656 kontrol**.

## v2.7.1 — kendi denetimimde çıkan 6 düzeltme

v2.7'yi teslim ettikten sonra kodu baştan sona bir daha taradım. Bulunanlar:

| Bulgu | Neden önemliydi | Düzeltme |
|---|---|---|
| Logo `onerror` **satır içi** nitelikteydi | Bazı WebUI çalıştırıcıları CSP ile satır içi işleyicileri engeller → logo gelmezse **kırık resim simgesi** kalabilirdi | Belge düzeyinde `error` (yakalama fazı) ve `load` dinleyicisi eklendi; iki şema da tutmazsa **mutlaka** harfe düşer |
| Logoların gerçekten gelip gelmediği görülemiyordu | "Logo var mı?" sorusu tahmine kalıyordu | Durum sekmesine **"şu an: N logo yüklendi, M harfe düştü"** sayacı eklendi |
| `fl_appmode_count` boş dosyada `grep -c` → `"0\n0"` döndürüyordu | `state.txt`'ye fazladan satır, `restore-backup` çıktısında bozuk alan | `case` ile tek satıra sabitlendi |
| Yedekleme "ekrana yay" listesini taşımıyordu | Telefon değiştirince/geri yükleyince seçimler kaybolacaktı | `backup` / `restore-backup` artık `appmode|paket` satırlarını da taşıyor (çıktı: `OK|force|never|appmode`) |
| Her açılış kontrol noktasında katman baştan yazılıyordu | 20 seçili uygulama × 3 kontrol × 4 komut = gereksiz binder trafiği | Zaten açıksa tek sorgu ile geçiliyor (ölçüldü: appmode için 0 komut) |
| Yapılandırılmış `tani` ekrana-yay katmanını görmüyordu | "Katman gerçekten etkin mi?" cevabı yoktu | `KONTROL|OK|ekrana-yay|N/M paket etkin` satırı eklendi |


## v2.14.0 — dogrulanmis duzeltmeler

- **"Yatay zorla" artik gercekten uygular:** tek pakete tum katmanlar (letterbox + MIUI mailbox kapatma + yatay kurali + ekrana-yay) uygulanir, her katman ayri olculur ve sonuc ekranda gosterilir. Eskiden sadece listeye yaziliyordu.
- **Yon butonlari dogru calisir:** "Sensor" eskiden sensori kapatip ekrani dikeye kilitliyordu. Artik yon tek fonksiyondan uygulanir, geri okunur; "Otomatik" secenegi de calisir. `ROTATION_MODE=off` artik yeniden basinca kilide donmez.
- **MIUI kurallari dogru dosyaya yazilir:** Android 13'te yetkili dosya `/data/system/cloudFeature_*` kopyasidir; /product erofs salt-okunurdur. Kurallar cloud dosyasina yazilir, geri okunarak dogrulanir.
- **Katmanlar birbirinden bagimsiz:** "mailbox kapat" artik yatay kuralini silmez; "yay kapat" mailbox kuralini silmez.
- **Kaldirma/geri-al eksiksiz:** sadece mailbox'i degismis paketler de temizlenir; compat XML'i sifirdan yazilmaz, ilk yedek (.fl.bak) geri konur.
- **Paketleme:** `skip_mount` kaldirildi; ZIP artik tum scriptleri calistirilabilir (0755) icerir, izleyici (watch.sh) sessizce olmez, baslamazsa log'a yazar.
- **Yedekleme:** mailbox listesini de tasir; geri yukleme listeleri sadece yazmakla kalmaz, gercekten uygular.

## Dogrulama durumu (dürüst not)

Bu surum, gercek komut ciktilarini taklit eden bir test ortaminda (sahte `wm/settings/am/dumpsys/pm/getprop`) 37 uctan uca senaryo ile dogrulandi: yon modlari, tek-uygulama zorla, toplu secim, mailbox ac/kapat, yedekle/geri-yukle, hepsini-geri-al, kaldirma. Bu, cihaz testi DEGILDIR. Kesin kanit: kurulumdan sonra WebUI → uygulama detayinda **"kesin test"**; pencere tam ekransa modul calisiyor demektir. Kural dosyalarinin MIUI tarafindan okundugunun kaniti icin bir kez yeniden baslat gerekebilir (WebUI bunu acikca soyler).


## v2.15.0 — tum WebUI butonlari tek tek denetlendi

- WebUI'daki **47 dugme/komut** tek tek arka ucuyla eslestirildi, her biri test ortaminda calistirildi.
- "HER SEYI UYGULA" artik paketi listelere ekler ve letterbox katmanini da yazar (eskiden acilista yeniden uygulanmiyordu); "HER SEYI GERI AL" paketi Force listesinden de cikarir (eskiden sonraki Uygula geri zorluyordu).
- Genel yatay kilidi (Kontrol > kilit) artik sonucu dogrular; basarisizsa "oldu" demez.
- Izleyici WebUI'dan acilirken de calistirilabilirlik yedegi kullanir; baslamazsa loga yazar.
- Ekrana-yay basarisizliginda kismi compat degeri temizlenir.
- MIUI kural yenileme, alicisi olmayan yayini "basarili" saymaz; kilit modunda ekranin gercekten yatay oldugu olculur.
- Arayuz surum etiketi (UI_VER) guncellendi; Durum sekmesindeki sahte "modul farkli" uyarisi giderildi.

## Dogrulama durumu (dürüst not)

Surum, gercek komut ciktilarini taklit eden test ortaminda **47 uctan uca senaryo** ile dogrulandi (yon modlari, tek/toplu zorla, HER SEYI UYGULA/GEMI AL, mailbox, kilit, izleyici ac/kapat, yedekle/geri-yukle, hepsini-geri-al, kaldirma). Bu cihaz testi DEGILDIR; kesin kanit WebUI > uygulama detayi > "kesin test"tir.


## v2.16.0 — ASIL DUZELTME: uygulamaya ozel katman artik CANLI uygulaniyor

Arastirma ile bulundu (sothx/coolapk; Xiaomi muhendislerinin hata ayiklama komutlari): MIUI'nin kendi servisi kurallari calisma aninda uygular:

```
cmd miui_embedding_window update-rule  <paket> fullRule::nra:cr:rcr:nr
cmd miui_embedding_window set-fixedOri <paket> disable::true
```

- v2.15 ve oncesi yalnizca kural **dosyasi** yazip "hemen gecerli" diyordu. MIUI bu dosyalari acilista okudugu icin, yeniden baslatmadan (ve bazen hic) uygulamada degisiklik gorunmuyordu. "Uygulamaya ozel hicbir sey calismiyor" sikayetinin sebebi buydu.
- Artik her per-app islemde **dosya (kalicilik) + canli servis komutu (aninda etki)** birlikte kullanilir; geri alma da canli yapilir (`set-fixedOri disable::false`). Sonuc satirinda `canli=ok|yok` acikca yazar; servis yoksa dosya yolu calismaya devam eder ve bu da acikca soylenir.
- `am compat` deneme sirasi belgeli sozdizimine cevrildi (`am compat enable <degisim> <paket>` once); ROM'un "Usage:" reddini basari sanma ihtimali kaldirildi.
- Kesin test artik "hemen gecerli" iddiasinda bulunmaz; kuralin dosyada oldugunu soyler, canli olcum ayridir.

## Dogrulama durumu (dürüst not)

57 uctan uca senaryo sahte-komut ortaminda gecti; yeni senaryolar canli servis komutlarinin dogru pakete, dogru bicimde gittigini ve servis yokken davranisin durust oldugunu kapsar. Cihaz testi DEGILDIR. Cihazdaki kesin kanit: WebUI > uygulama > "kesin test" + logdaki `miui-cmd:` satirlari (servisin cevabi orada birebir yazar).


## v2.17.0 — sahte basari avcisi

- `fl_force_apply` artik kritik katman basarisizsa hata kodu doner; **Yatay zorla / apply-one / toplu secim** bunu `HATA` olarak bildirir (eskiden basarisizlik ozet satirinin icinde gizleniyor, arayuz "oldu" diyordu).
- `mode` yazdigini geri okur; `restore` sonucu olcer; toplu secim ve yedek yukleme arka uc hatasini yutmaz; ekrana-yay basarisizligi loga gercek sebebiyle duser.
- Ilke: her dugme ya sonucu **olcer** ya da olmadigini **soyler**. Calismayan sey landscape.log'a sebebiyle yazilir (MIUI servis cevaplari `miui-cmd:` satirlarinda birebir gorulur).

## Dürüstlük notu

Bu modul cihazi elimde olmadan "%100 calisir" sozu veremez; kimse veremez. Verilebilecek soz sudur: **bir sey calismazsa arayuz onu basari gibi gostermez ve log gercek sebebi yazar.** Cihaz kaniti: WebUI > uygulama > kesin test; tutmazsa Kayıtlar > log dosyasini gonder, `miui-cmd:` ve `force-apply:` satirlari sorunu satiriyla gosterir.
