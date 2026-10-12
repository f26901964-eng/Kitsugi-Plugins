# Kitsugi-Beta/domain_fixes.json — Güncel Kopya (12 Ekim 2026)

Bu dosya, uygulamanın açılışta çektiği **domain tablosunun elle düzeltilmiş halidir**
(36 eklenti; her biri GitHub Actions probe + web araştırması ile doğrulanmıştır).

## Neden elle?
`Kitsugi-Beta/scripts/check_domains.py` her gece çalışıyor ama son 3 gündür
`+0 -0 ~0` üretiyor — yani **hiçbir domaini düzeltemiyor**. Sebep: `blocked` listesine
düşen eklentilerin (kaynağı bulunamayan / AI'ın adres bulamadığı ~171 eklenti)
tabloda **hiç kaydı olmuyor**; uygulama bu eklentileri kendi gömülü (ölü) adresiyle
denemeye çalışıp zaman aşımına uğruyor.

## Nasıl uygulanır (Kitsugi-Beta deposunda)
1. `domain_fixes.json` dosyasını bu dosyayla değiştir:
   https://github.com/f26901964-eng/Kitsugi-Beta/blob/main/domain_fixes.json
2. Commit + push → uygulama kapatılıp açıldığında otomatik çeker
   (`https://raw.githubusercontent.com/f26901964-eng/Kitsugi-Beta/main/domain_fixes.json`).
3. Codeberg aynası varsa (`codeberg.org/BlackDamage/Kitsugi-Beta`) orayı da güncelle.

## Kalıcılık
`check_domains.py` "repoda olmayan ama canlı olan kayıtları korur" mantığına sahip
(`existing.items()` döngüsü) — bu yüzden buradaki canlı kayıtlar sonraki gece
çalışmalarında **silinmez** ve AI tarafından ezilmez. Ölürse otomatik düşer.

## Özet (12.10.2026)
- Kaydedilen/eklenen 36 kayıt: koreanturk.com, tafdi.net, turkish123.com, ugurfilm.com,
  wfilmizle.my, dizi77.life, dizimom.cafe, sinezy.si, dizibox.live, puhutv.com,
  netmirror.online, superfilmgeldi.net, watchwrestling.ae, filmizleilk.com, filmkovasi.com,
  fullhdfilmizlede.net, setfilmizle.com, exxen.com, tabii.com, kanal7.com, torrentfilm.com,
  asyafilmizlesene.com, dizigom.li, dizimag.org, dizipal3008.com, filmizlesene.com,
  hdfilmsitesi->dizifilmizle.to, hdfilmcehennemi2.biz, iwatchtheoffice.cc, jpfilms->kodasusaka.com,
  hdfilmcehennemi .nl -> .biz, fullhdfilmizlesene .mx -> .now, webteizle .xyz -> .info,
  recTV `/api` fazlalığı temizlendi, temel -> setfilmizle.com, fullraces/f1fullraces
