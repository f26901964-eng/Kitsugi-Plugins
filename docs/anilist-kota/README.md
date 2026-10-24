# AniList Kota Çalışması (`docs/anilist-kota`)

Bu klasör, AniList GraphQL API'sinin **30 istek/dk (IP başına)** kotasının Kitsugi uygulamasında
neden sürekli tükendiğini ve bunun nasıl kalıcı olarak çözüleceğini belgeleyen çalışmadır.

| Dosya | İçerik |
|---|---|
| **`ANILIST_KOTA_RAPORU_2026-10-24.md`** | Ana rapor: gerçek limitler, Kitsugi-Beta kodunun röntgeni, ekosistem araştırması, mimari, yol haritası, kaynaklar |
| `AniListQuotaGate.kt` | Tek kota kapısı — Kitsugi-Beta'ya doğrudan eklenebilir Kotlin dosyası |
| `kitsugi-beta-anilist-quota-gate.patch` | Entegrasyon yaması (8 dosya, +578/−33) |
| `anilist_kota_sim.py` | Algoritmanın Python portu: 5 garanti testi + 3 mimarinin karşılaştırması |

## Hızlı başlangıç

```bash
# 1) Algoritmayı doğrula (bağımlılık yok)
python3 docs/anilist-kota/anilist_kota_sim.py

# 2) Yamayı Kitsugi-Beta klonuna uygula
cd /yol/Kitsugi-Beta
git apply --check <bu-klasör>/kitsugi-beta-anilist-quota-gate.patch
git apply         <bu-klasör>/kitsugi-beta-anilist-quota-gate.patch
./gradlew :app:compileDebugKotlin
```

> Yama `main` üzerinde `git apply --check` ile doğrulandı; bu ortamda Android SDK olmadığı için
> **derlenmedi**. Uygulamadan önce derleyin.

## Tek cümlelik özet

Kota 30 istek/dk ve IP başına; çözüm daha fazla IP değil, **tek bir kota kapısı + daha az istek
(birleştirme) + önbellek + 429'da beklemek**.
