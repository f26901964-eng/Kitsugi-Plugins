#!/system/bin/sh
# Magisk 20+ / KernelSU / SukiSU / APatch kurulum betigi (customize.sh).
# Yonetici bu dosyayi MODPATH degiskeni ayarli halde "source" eder.

if ! type ui_print >/dev/null 2>&1; then
    ui_print() { echo "$1"; }
fi
if ! type abort >/dev/null 2>&1; then
    abort() { echo "! $1"; exit 1; }
fi
set_perm() {
    chown "$1:$2" "$4" 2>/dev/null
    chmod "$3" "$4" 2>/dev/null
}

ui_print " "
ui_print "*********************************************"
_ver=$(sed -n 's/^version=//p' "$MODPATH/module.prop" 2>/dev/null | head -n1)
[ -z "$_ver" ] && _ver="?"
ui_print "  Force Landscape - All Apps  $_ver"
ui_print "  Tum uygulamalar icin sistem geneli yatay mod"
ui_print "*********************************************"
ui_print " "

if [ "$KSU" = "true" ]; then
    ui_print "- Ortam: KernelSU/SukiSU (surum: ${KSU_VER:-?})"
elif [ "$APATCH" = "true" ]; then
    ui_print "- Ortam: APatch"
else
    ui_print "- Ortam: Magisk (surum: ${MAGISK_VER:-?})"
fi
SDK=$(getprop ro.build.version.sdk)
ui_print "- Android API: $SDK / $(getprop ro.build.version.release)"
ui_print "- Cihaz: $(getprop ro.product.model)"

if [ -n "$SDK" ] && [ "$SDK" -lt 32 ] 2>/dev/null; then
    ui_print "! UYARI: Android API $SDK < 32; sensor modu calismaz,"
    ui_print "!        kilit modu (settings katmani) kullanilir."
fi

# v2.14 DUZELTME: ZIP tum dosyalari 0644 saklar. Eskiden sadece 4 script
# 0755 yapiliyordu; watch.sh calistirilabilir degilse izleyici sessizce
# hic baslamiyor, webui.sh/post-fs-data.sh da ayni riske giriyordu.
for f in service.sh action.sh uninstall.sh landscape.sh webui.sh watch.sh post-fs-data.sh customize.sh; do
    [ -f "$MODPATH/$f" ] && set_perm 0 0 0755 "$MODPATH/$f"
done
[ -f "$MODPATH/module.prop" ] && set_perm 0 0 0644 "$MODPATH/module.prop"
[ -f "$MODPATH/config.default.conf" ] && set_perm 0 0 0644 "$MODPATH/config.default.conf"

FL_DIR=/data/adb/force_landscape
mkdir -p "$FL_DIR" 2>/dev/null
if [ -f "$FL_DIR/config.conf" ]; then
    ui_print "- Mevcut config korundu: $FL_DIR/config.conf"
    ui_print "  (yeni v1.6 ayarlari icin bu dosyayi silip yeniden kurabilirsiniz)"
else
    cp -f "$MODPATH/config.default.conf" "$FL_DIR/config.conf" 2>/dev/null
    ui_print "- Varsayilan config olusturuldu: $FL_DIR/config.conf"
fi
chmod 0755 "$FL_DIR" 2>/dev/null
chmod 0644 "$FL_DIR/config.conf" 2>/dev/null
# NOT: compat_applied.txt SILINMEZ; boylece guncellemede tum paketler
# bastan uygulanmaz, sadece yeni kurulan uygulamalar islenir.

ui_print " "
if [ -f "$FL_DIR/config.conf.bak" ]; then
    ui_print "- Config v1.7'ye goc ettirildi (yedek: config.conf.bak)"
fi
ui_print "- Kurulum tamamlandi. Yeniden baslatin."
ui_print "- Letterbox (ortada portre kutu) icin uygulama bazli resize ayari"
ui_print "  ilk acilista uygulanir; uygulamayi tamamen kapatip yeniden acin."
ui_print "- Akilli mod: yatay oyunlara dokunulmaz, dikey uygulamalar tam ekran olur."
ui_print "- v2.14: tespit edilen tum buglar duzeltildi; her WebUI secimi"
ui_print "        gercekten uygulanir ve sonucu geri okunarak dogrulanir."
ui_print "- v2.7.1: logo CSP korumasi, ekrana-yay hiz + tani satiri,"
ui_print "        yedekleme/geri yukleme artik ekrana-yay listesini de tasir."
ui_print "- v2.7 YENI: 'EKRANA YAY' - uygulama basina sag-sol bantlari kaldirir"
ui_print "        (OVERRIDE_MIN_ASPECT_RATIO + LARGE; Android 14+ ANY_ORIENTATION)."
ui_print "        Uygulama listesinde her satirda: yatay / yay / birak / bilgi."
ui_print "        Tek dugmeyle acilir-kapanir; geri almasi tam."
ui_print "- v2.7 DUZELTME: cihaz bilgisi acilista okunuyor (—/NaN/API — bitti),"
ui_print "        bos satirlar hic cizilmiyor; gercek uygulama logolari"
ui_print "        (ksu://icon -> mui://icon -> harf); tani raporunda unknown bitti."
ui_print "- v2.6: spawn koprusu (gercek asenkron), kilitlenmeyen arayuz."
ui_print "- v2.5: am/cmd komutlari stdin olarak /dev/null alir (SELinux reddi bitti)."
ui_print "  Hatalar WebUI > Letterbox sekmesinde listelenir (compat_errors.txt)."
ui_print "- SURUM: $_ver"
ui_print "- AYRINTILI ARAYUZ: SukiSU Ultra > Moduller > Force Landscape > WebUI"
ui_print "  Uygulama secimi, Letterbox onarimi, canli log, rapor indirme."
ui_print "- Action dugmesi sadece kisa ozet verir; asil is WebUI'de."
chmod -R 0755 "$MODPATH/webroot" 2>/dev/null
chmod 0644 "$MODPATH/webroot/index.html" 2>/dev/null
ui_print "- Yatay olmayan uygulama varsa: modulun Action dugmesine basin."
ui_print "- Tani raporu: /sdcard/force_landscape_rapor.txt"
ui_print " "

# NOT: bu dosya yonetici tarafindan "source" edilir.
# Burada ASLA "exit" kullanilmaz - kurulum akisini keser.
