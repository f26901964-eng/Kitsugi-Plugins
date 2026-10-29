#!/system/bin/sh
# Yoneticideki (Magisk / KernelSU / SukiSU) modul "Action" dugmesi.
# v2.0: asil arayuz WebUI. Bu betik sadece HIZLI bir ozet verir:
# ayarlari uygular, katman testini calistirir ve kisa durum yazar.
# Ayrintili isler (uygulama secimi, log, rapor indirme) WebUI'de.

MODDIR=${0%/*}
[ -f "$MODDIR/landscape.sh" ] || MODDIR=/data/adb/modules/force_landscape_all
. "$MODDIR/landscape.sh"

fl_prepare
fl_log "action.sh calisti (elle)"
FL_FIRST=1
fl_apply
fl_status

echo " "
echo " ---------- katman testi ----------"
sed -n '1,6p' "$FL_CTEST" 2>/dev/null | sed 's/^/  /'

echo " "
echo " ---------- AYRINTILI ARAYUZ ----------"
echo "  Yoneticide (SukiSU Ultra) -> Moduller -> Force Landscape -> WebUI"
echo "  Orada: uygulama secimi (Yatay/Dokunma), mod, canli durum,"
echo "         katman testi, log goruntuleme ve rapor indirme var."
echo " "
echo " Ekrana yay   : $(fl_appmode_count) paket (WebUI > uygulama satiri > ⤢ yay)"
echo " Rapor dosyasi: $FL_REPORT"
echo " Log dosyasi  : $FL_LOG"
