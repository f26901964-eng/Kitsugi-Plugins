# PLAN & TASK — AniList Kota Kapısı (Kitsugi-Beta)

**Tarih:** 2026-10-24
**Hedef sürüm:** v2.4.341+
**Bağlı rapor:** `ANILIST_KOTA_RAPORU_2026-10-24.md`
**Bağlı yama:** `kitsugi-beta-anilist-quota-gate.patch`
**Etkilenen modül:** `:app` (Kotlin, Android)

---

## 1. Problem özeti

Bildirim ekranı `KTS-ANL-2002 / AniList çok fazla istek (GraphQL 429)` yüzünden açılmıyor ve
"AniList bildirim isteği yanıtsız kaldı (ağ/sunucu hatası)" gösteriyor.

Kök nedenler:

| # | Neden | Kanıt |
|---|---|---|
| N1 | Metadata trafiği 350 ms aralıkla gidiyor (≈171 istek/dk), degraded modda bile 1 500 ms (≈40/dk) | `KitsugiApiBase.ANILIST_MIN_INTERVAL_NORMAL_MS / _DEGRADED_MS` |
| N2 | Degraded algısı gecikmeli (`remaining <= 12` → 18 istek harcandıktan sonra) | `updateAniListRateHeaders` |
| N3 | 5 yol kotayı tamamen atlıyor (doğrudan POST) | `AniListSyncManager:865`, `AniListImportManager:319`, `KitsugiProfileViewModel:1244`, `KitsugiUserProfileViewModel:660`, `NotificationDiagnostics:135` |
| N4 | 429 sonrası cooldown 6 sn'den uzunsa tüm çağıranlar fail-fast `null` alıyor | `executeAniListQuery` adım 3 / `isAniListUnavailableNow` |
| N5 | Cooldown 30 sn'de kesiliyor, AniList penceresi 60 sn | `ANILIST_RATE_LIMIT_COOLDOWN_CAP_MS` |
| N6 | Bildirim açılışı 2 istek, her sekme +1 istek, önbellek yok | `KitsugiAniListNotificationClient`, `KitsugiNotificationsViewModel` |
| N7 | Katalog "Otomatik yeniden denenecek" diyor, bildirim akışında retry yok | `pf_err_retryable_yes` vs VM |

---

## 2. TASK listesi

### FAZ 0 — Yamayı uygula (bu paket)

| ID | Task | Dosya | Kabul kriteri | Durum |
|---|---|---|---|---|
| T01 | `AniListQuotaGate` ekle | `data/remote/AniListQuotaGate.kt` (yeni) | Sınıf derlenir; 5 garanti simülasyonda geçer | ✅ yamada |
| T02 | `executeAniListQuery`'ye `priority` + kapı | `data/remote/KitsugiApiBase.kt` | 429 cooldown mantığı kapıya devredildi; `isAniListUnavailableNow` kapıyı da okur | ✅ yamada |
| T03 | Başlıkları kapıya bildir | `KitsugiApiBase.updateAniListRateHeaders` | `X-RateLimit-Limit` 30 öğrenilir, 90 varsayılmaz | ✅ yamada |
| T04 | 429 / servis kesintisi → kapıyı kapat | `KitsugiApiBase` | `Retry-After` / `X-RateLimit-Reset` boyunca istek çıkmaz | ✅ yamada |
| T05 | Önbellek + tek-uçuş | `KitsugiApiBase.executeAniListQueryCached` | Aynı anahtar 2 kez ağa çıkmaz | ✅ yamada |
| T06 | Bildirim: sayfa + sayaç tek belge | `KitsugiAniListNotificationClient` | 1 açılış = 1 HTTP isteği | ✅ yamada |
| T07 | Bildirim: 60 sn önbellek + istemci tarafı sekme filtresi | aynı dosya | Sekme değişimi 0 istek | ✅ yamada |
| T08 | Bildirim: geri sayımlı kota mesajı (`retryInMs`) | aynı dosya | Mesajda "~N sn sonra" görünür | ✅ yamada |
| T09 | 5 kaçak yolu kapıya bağla | sync / import / 2 profil VM / teşhis | Hepsi `aniListGate*` çağırır | ✅ yamada |

### FAZ 1 — Talebi azalt (yüksek kazanç, düşük risk)

| ID | Task | Dosya | Kabul kriteri | Durum |
|---|---|---|---|---|
| T10 | Detay sorgularını alias'la birleştir (medya + ilişkiler + karakter + ekip + öneriler) | `KitsugiDetailClient`, `KitsugiMediaRelationsClient`, `KitsugiCharacterClient`, `KitsugiStaffClient` | Detay açılışı ≤ 2 AniList isteği | ⬜ |
| T11 | Arama: 400 ms debounce + uçuştaki isteği iptal | `SearchViewModel`, `AniListSearchClient` | Yazarken ≤ 1 istek | ⬜ |
| T12 | Profil istatistikleri tek istek (`User.statistics`) | `KitsugiProfileViewModel` | 3 → 1 | ⬜ |
| T13 | Keşfet önden yükleme yalnızca bütçe varsa (`PREFETCH`) | `ExploreViewModel`, `AniListSearchClient` | Bütçe %60 doluyken prefetch yok | ⬜ |
| T14 | `id_in` ile toplu kimlik çözümü (8 → 1 istek) | `KitsugiIdResolver` | Çoklu kimlik tek istek | ⬜ |

### FAZ 2 — Kalıcı önbellek

| ID | Task | Dosya | Kabul kriteri | Durum |
|---|---|---|---|---|
| T15 | Room tabanlı AniList önbelleği (TTL + bayat-veri) | `data/local/*`, `KitsugiApiBase` | Uçak modunda liste/detay açılır | ⬜ |
| T16 | Soğuk açılışta 0 istek (önbellek-önce) | `AppRoot`, VM'ler | Açılışta AniList isteği 0 | ⬜ |

### FAZ 3 — Görünürlük ve doğrulama

| ID | Task | Dosya | Kabul kriteri | Durum |
|---|---|---|---|---|
| T17 | Bildirim VM'inde gerçek otomatik retry (`retryInMs`, 1 deneme) | `KitsugiNotificationsViewModel` | Katalog metniyle uyum | ⬜ |
| T18 | Kota paneli (limit/bütçe/pencere/cooldown) | `PlatformErrorCatalogScreen` | Teşhis edilebilir | ⬜ |
| T19 | Telemetri: 429 oranı, ekran başına istek, ort. bekleme | `PlatformFailureRegistry` | 429 oranı < %0,5 | ⬜ |

---

## 3. Uygulama adımları (Faz 0)

```bash
# 1) Yedek dal
git checkout -b fix/anilist-quota-gate

# 2) Yamayı uygula
git apply --check kitsugi-beta-anilist-quota-gate.patch
git apply         kitsugi-beta-anilist-quota-gate.patch

# 3) Derle
./gradlew :app:compileDebugKotlin

# 4) Kur + elle doğrula
./gradlew :app:assembleDebug
```

### Elle doğrulama senaryosu

1. Uygulamayı temiz kurulumla aç (AniList hesabı bağlı).
2. Keşfet → detay → geri → Bildirimler sırasını 3 kez tekrarla.
3. Logcat'te şunları gör:
   - `AniList kapısı isteği reddetti` satırları **yalnızca** PREFETCH/BACKGROUND için,
   - hiçbir yerde `HTTP 429`,
   - bildirim açılışında **tek** `graphql.anilist.co` isteği.
4. Uçak moduna al → bildirim ekranı boş ekran yerine önbellekteki son sayfayı gösteriyor.

---

## 4. Kabul kriterleri (Faz 0)

- [ ] 10 dakikalık tipik oturumda **HTTP 429 = 0**
- [ ] Bildirim açılışı toplam **1 istek** (sayfa + sayaç aynı belgede)
- [ ] Sekme değişimi **0 istek** (önbellek sıcakken)
- [ ] 60 sn'lik pencerede en yüksek istek **≤ 27**
- [ ] Kota dolduğunda kullanıcı **"~N sn sonra"** görüyor (ağ hatası değil)
- [ ] `./gradlew :app:compileDebugKotlin` hatasız

---

## 5. Riskler / geri alma

| Risk | Etki | Önlem |
|---|---|---|
| `AniListQuotaGate` derlenmezse | Build kırılır | Dosya bağımsız; T01'i tek başına revert et |
| CRITICAL istekler 25 sn bekler | Bildirim geç açılır | Önbellek + `allowStaleMs`; gerekirse `defaultMaxWaitMs` düşür |
| `Viewer` alanı beklenmedik hata dönerse | Bildirim açılmaz | `runCatching` zaten var; `Viewer` bloğunu kaldırmak 2 istekli eski davranışa döner |
| Kaçak yolların kapıya bağlanması | Eşitleme "sonraki tura" ertelenir | Sync zaten WorkManager ile periyodik; kabul edilebilir |

**Tam geri alma:** `git revert` veya `git apply -R kitsugi-beta-anilist-quota-gate.patch`.

---

## 6. Ölçüm notları

- Simülasyon: `python3 anilist_kota_sim.py` → 5/5 garanti testi.
- Referans çıktı (10 dk yoğun oturum): mevcut 122 istek / 6×429 → yeni 45 istek / 0×429.
- Sahada hedef: 429 oranı < %0,5, pencere tepesi ≤ 27, bildirim açılışı ≤ 1,2 sn.
