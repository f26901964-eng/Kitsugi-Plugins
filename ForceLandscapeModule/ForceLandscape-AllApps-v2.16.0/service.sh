#!/system/bin/sh
# Magisk / KernelSU / SukiSU late_start servis betigi.
# boot tamamlaninca yatay modu uygular, sonra birkac kontrol noktasinda
# yeniden uygular (MIUI/SystemUI bazi ayarlari acilista sifirlayabiliyor)
# ve cikar. Kalici servis/CPU dongusu YOKTUR.

MODDIR=${0%/*}
[ -f "$MODDIR/landscape.sh" ] || MODDIR=/data/adb/modules/force_landscape_all
. "$MODDIR/landscape.sh"

fl_prepare
fl_log "service.sh basladi (pid $$, modul $MODDIR)"

fl_wait_boot || fl_log "boot_completed zaman asimi, yine de uygulaniyor"
fl_wait_wm
if fl_wait_stable; then
    fl_log "system_server kararli"
else
    fl_log "UYARI: system_server kararsiz/ yeniden baslamis olabilir - islem yine de yapilacak"
fi

_prev=0
for _t in $REAPPLY_AT; do
    _s=$((_t - _prev))
    [ "$_s" -gt 0 ] && sleep "$_s"
    _prev=$_t
    fl_log "--- kontrol noktasi ${_t}s ---"
    fl_apply
done

# v2.10: tam ekran izleyicisi (arka planda, hafif)
# v2.14: watch.sh calistirilabilir degilse sessizce gecmek yerine sh ile
# baslatilir ve log'a yazilir (eski davranis: izleyici hic baslamiyor,
# kullanici da bunu ancak elle fark ediyordu).
if [ ! -x "$MODDIR/watch.sh" ] && [ -f "$MODDIR/watch.sh" ]; then
    chmod 0755 "$MODDIR/watch.sh" 2>/dev/null
    [ -x "$MODDIR/watch.sh" ] || fl_log "UYARI: watch.sh 0755 yapilamadi, sh ile baslatilacak"
fi
if [ -f "$MODDIR/watch.sh" ]; then
    _wo=$(sed -n 's/^WATCH_FULLSCREEN=//p' "$FL_CFG" 2>/dev/null | head -n 1)
    [ -n "$_wo" ] || _wo=true
    if [ "$_wo" = "true" ]; then
        if [ -x "$MODDIR/watch.sh" ]; then _wch="$MODDIR/watch.sh"; else _wch="sh $MODDIR/watch.sh"; fi
        if command -v setsid >/dev/null 2>&1; then
            setsid $_wch >/dev/null 2>&1 < /dev/null &
        else
            ( trap "" HUP; $_wch >/dev/null 2>&1 < /dev/null & )
        fi
        fl_log "izleyici baslatildi (watch.sh)"
    else
        fl_log "izleyici kapali (WATCH_FULLSCREEN=false)"
    fi
fi

fl_log "service.sh bitti"
exit 0
