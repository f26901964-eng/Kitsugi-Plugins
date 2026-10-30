#!/system/bin/sh
# ============================================================
#  Force Landscape v2.10 — tam ekran izleyicisi
#  One plana gelen uygulama "yatay zorla" listesindeyse gorevini
#  tam ekran olcusune getirir (MIUI orta-pencereyi calisma aninda
#  ezer). Dusuk maliyet: 3 sn'de bir tek dumpsys, islem yoksa cikis.
#  Kapatmak icin: /data/adb/force_landscape/config.conf icinde
#  WATCH_FULLSCREEN=false yap veya WebUI > Ayarlar > izleyiciyi kapat.
# ============================================================
MODDIR=${0%/*}
[ -f "$MODDIR/landscape.sh" ] || MODDIR=/data/adb/modules/force_landscape_all
. "$MODDIR/landscape.sh"

fl_prepare
echo $$ > "$FL_DIR/watch.pid" 2>/dev/null
LOG="$FL_LOG"
say() { echo "[$(date '+%H:%M:%S')] izleyici: $*" >> "$LOG" 2>/dev/null; }

_r_cut() { printf '%s' "$1" | cut -d'|' -f2-; }
_want=$(sed -n 's/^WATCH_FULLSCREEN=//p' "$FL_CFG" 2>/dev/null | head -n 1)
[ -n "$_want" ] || _want=true
if [ "$_want" != "true" ]; then
    say "kapali (WATCH_FULLSCREEN=false)"
    rm -f "$FL_DIR/watch.pid" 2>/dev/null
    exit 0
fi
say "basladi (pid $$)"

_last=""
_last_t=0
_n=0
while :; do
    # modul devre disi birakildiysa cik
    [ -f "$MODDIR/disable" ] && { say "modul devre disi -> cikiliyor"; break; }
    # v2.10: ActivityRecord{1a2b u0 com.pkg/.Act} -> com.pkg
    _dd=$(dumpsys activity activities 2>/dev/null | tr -d '\r')
    _fg=$(printf '%s\n' "$_dd" | grep -m1 -E 'topResumedActivity|mResumedActivity' | \
          grep -oE '[a-zA-Z][a-zA-Z0-9_.]*/[a-zA-Z0-9_.$]+' | head -n 1 | cut -d/ -f1)
    [ -n "$_fg" ] || _fg=$(printf '%s\n' "$_dd" | \
          grep -oE '[a-zA-Z][a-zA-Z0-9_.]*/[a-zA-Z0-9_.$]+' | head -n 1 | cut -d/ -f1)

    if [ -n "$_fg" ]; then
        fl_maps_load 2>/dev/null
        _need=no
        case "$FL_FORCE_MAP" in *" $_fg "*) _need=yes ;; esac
        if [ "$_need" = "no" ] && fl_appmode_has "$_fg" 2>/dev/null; then _need=yes; fi
        if [ "$_need" = "yes" ]; then
            _now=$(date +%s)
            if [ "$_fg" != "$_last" ] || [ $((_now - _last_t)) -ge 15 ]; then
                _last="$_fg"; _last_t=$_now
                _r=$(fl_task_fullscreen "$_fg")
                case "$_r" in
                    OK*)   _n=$((_n + 1))
                           say "$_fg -> $(_r_cut "$_r")" ;;
                    HATA*) say "$_fg -> $(_r_cut "$_r")" ;;
                esac
            fi
        fi
    fi
    sleep 3
done
rm -f "$FL_DIR/watch.pid" 2>/dev/null
say "bitti (toplam $_n tam ekran)"
exit 0
