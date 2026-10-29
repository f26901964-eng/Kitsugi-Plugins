# Force Landscape — v2.17.0 · Kurulum ve Doğrulama

Cihaz: Xiaomi Pad 6 (pipa) · MIUI V14.0.10.0.TMZCNXM · Android 13 · SukiSU Ultra
Tarih: 2026-10-29

---

## 0) Bu sürüm neyi düzeltiyor (ekran görüntünden çıkan 4 gerçek hata)

Gönderdiğin görüntüde panelin **“Gerçek durum (sistemden okundu)”** bölümünde
**4 satır “okunamadı”** yazıyordu:

| Satır | Görüntüde | Gerçek sebep | v2.13.0 |
|---|---|---|---|
| letterbox (resize) | okunamadı | Android 13’te **`am compat state` yok**; tek okuma yolu buydu | 3 kaynak sırayla denenir, **hangisinden okunduğu yazılır** |
| ekrana yay · kapıcı | okunamadı | aynı | aynı |
| ekrana yay · 16:9 | okunamadı | aynı | aynı |
| pencere | okunamadı | `dumpsys window windows`’ta pencere yoksa **tek yol** oydu; “uygulama kapalı” ile “ölçemedim” ayrılmıyordu | pencere → **görev (task) sınırları** yedeği; **“uygulama kapalı — açıp ölç”** der |

Ek olarak iki sessiz hata:

5. **“letterbox kaydı: ok”** yazıyordu ama sistemden hiçbir şey doğrulanmamıştı →
   artık **“kayıt yok”** yazıyor; “ok” demek için dosya/dumpsys kanıtı aranıyor.
6. MIUI kural dosyaları **0644** ile yazılıyordu. Açık kaynak (ybcq/MIUI-MagicWindow,
   sothx, achzehn/magic_window) MIUI’nin **system:system + 660** beklediğini söylüyor →
   artık **660 + 1000:1000 + SELinux etiketi** (`chcon u:object_r:system_data_file:s0`).

---

## 1) Yeni: “📄 Kural kanıtı” — dosya dosya gerçek hâl

Uygulama sayfasında **📄 Kural kanıtı** düğmesi. Sadece 4 dosyayı okur, hiçbir şey yazmaz:

```
dosya|/data/system/cloudFeature_fixed_orientation_list.xml | var|660|1000:1000|u:object_r:system_data_file:s0
satir|/data/system/cloudFeature_fixed_orientation_list.xml | <package name="com.cmplay.dancingline" disable="true" />
dosya|/data/system/cloudFeature_embedded_rules_list.xml     | var|660|1000:1000|...
satir|...                                                   | <package name="com.cmplay.dancingline" fullRule="nra:cr:rcr:nr" />
dosya|/product/etc/fixed_orientation_list.xml               | var|660|1000:1000|...
dosya|/product/etc/embedded_rules_list.xml                  | var|660|1000:1000|...
```

- Satır **yoksa** o dosyada bizim kuralımız yok — “uygulandı” demeyiz.
- İzin **660** değilse MIUI kuralı **sessizce yok sayabilir** — bunu artık görebiliyorsun.

---

## 2) Yeni: “♻️ Kuralları zorla yükle” — dürüst sınırın düğmesi

AOSP katmanı (`wm`, `am compat`, `settings`) **anında** etkilidir.
Ama **MIUI’nin kendi kural dosyaları** (mailbox / fullRule) platform_compat servisi
tarafından **açılışta** okunur. Xposed olmadan bunu “canlı” tetiklemenin yolu yok;
root ile tek yol **zygote’u tazelemek**:

- Düğme: **♻️ Kuralları zorla yükle** → `setprop ctl.restart zygote`
  (olmazsa `kill -9 zygote`). Ekran ~20 sn kararır, **verilerin silinmez**.
- Komut satırından: `sh webui.sh softreboot`

Bu, “👍 uygulandı” yazıp hiçbir şeyin değişmemesinden iyidir: ya **kural + tazeleme**
olur ya da **açıkça söyler**.

---

## 3) Kurulum (sırayla)

1. `ForceLandscape-AllApps-v2.13.0.zip` → KernelSU/SukiSU → Modules → **Install**
2. **Yeniden başlat**
3. KernelSU → WebUI aç → **Kontrol** sekmesi: her anahtarın **anında** değiştiğini gör
   (satırlar sistemden okunuyor)
4. Uygulama listesinde **DancingLine** → ℹ️
   - **🚀 HER ŞEYİ UYGULA** (mailbox + fullRule + AOSP katmanı + reload + ölçüm)
   - **📄 Kural kanıtı** → iki `satir|` görünmeli (görünmüyorsa kural yazılamamış)
   - **♻️ Kuralları zorla yükle** → ~20 sn bekle
   - **Uygulamayı aç** → **hâlâ dar sütun mu?**
   - **✔ kesin test** → `SONUC|` satırını bana yapıştır

---

## 4) Test: 788/788 (taklit ortam)

| Blok | Test | Sonuç |
|---|---|---|
| Kurulum (zip → modules_update) | — | `INSTALL_EXIT=0` |
| Klasik arayüz | 38 | ✓ |
| Hata yolu | 7 | ✓ |
| Uygulama arayüzü (async/sync/promise) | 108×3 | ✓ |
| v2.10 kabuk | 173 | ✓ |
| v2.9 arayüz + arka uç | 130 | ✓ |
| Kapsam denetimi | 27 | ✓ |
| Aç/kapa/seç denetimi | 68 | ✓ |
| **v2.13 gerçek kaynak / kanıt / izin** | **29** | ✓ |
| **Toplam** | **788** | **0 başarısız** |

v2.13’te ölçülen 29 yeni kontrol:

```
[1] kayıt yok        -> "uygulanmadi|kayit-yok"      (uydurmuyor)
[2] platform_compat  -> enabled/disabled + kaynak adı
[3] xml override     -> enabled/disabled + kaynak adı
[4] am compat        -> çökmüyor, varsa en yetkili kaynak
[5] pencere          -> "kapali" ile "?" ayrı; süreç yaşıyorsa "kapali" DEMEZ
[6] izin             -> 660 + 1000:1000 + SELinux
[7] kanıt            -> dosyadaki satırımız görünüyor (paket + fullRule)
[8] geri al          -> kanıtta hiç satır kalmıyor
[9] softreboot       -> çökmez, dürüst cevap
[10] verify          -> kaynak alanları + pencere
[11] arayüz          -> "kayıt yok", data-proof, data-softreboot, "uygulama kapalı"
```

---

## 5) Dürüst sınırlar (değişmedi)

1. **Cihazında test edilmiyor.** 788 test taklit ortamda. Hüküm senin cihazının
   `dumpsys`/`kesin test` çıktısına aittir.
2. **Kuralı yazmak ≠ MIUI’nin okuması.** Bu yüzden **♻️ zorla yükle** var.
   Xposed’siz canlı tetikleme mümkün değil; bunu “yapıldı” diye yazmıyoruz.
3. **Uygulama bazlı YÖN** Android 13’te Xposed/LSPosed olmadan garanti değil.
   Cihaz geneli yön (`wm set-ignore-orientation-request` + `user_rotation`) kesin;
   uygulama bazlı olan **boyut/mailbox** (`fixed_orientation_list` + `embedded_rules_list`).
4. `fullRule="nra:cr:rcr:nr"` **kendi kendine tablet uyarlaması yapan** uygulamalarda
   işlemez (açık kaynakta yazıyor: 酷安/微博/微信 gibi). O durumda kural **mailbox kapatır**,
   yön için cihaz geneli kilit gerekir.

---

## 6) Hâlâ dar sütunsa — 4 satırlık karar ağacı

`✔ kesin test` çıktısında:

| Satır | Anlamı | Yapılacak |
|---|---|---|
| `ADIM|1|HATA|mailbox|MIUI ortadaki sutun ACIK` | kural yazılamadı | **📄 Kural kanıtı** → izin 660 mı, satır var mı? |
| `ADIM|2|UYARI|yatay-kural|katmana yazildi` | `/product` salt-okunur | **♻️ Kuralları zorla yükle** |
| `ADIM|4|HATA|pencere|dar pencere: 1443x1800` | mailbox aktif | kural + tazeleme sonrası tekrar ölç |
| `SONUC|CALISIYOR|tam ekran dogrulandi` | doldu ✓ | — |

Bu 4 satırı olduğu gibi bana yapıştır; kalan tek adımı ona göre yazarım.
