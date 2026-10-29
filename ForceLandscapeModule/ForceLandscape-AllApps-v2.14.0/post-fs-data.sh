#!/system/bin/sh
# ============================================================
#  Force Landscape - All Apps  v2.8  |  post-fs-data.sh
#  SADECE am-compat yolu calismadiginda devreye girer.
#  (/data/adb/force_landscape/xml_fallback.on varsa)
#
#  system_server acilmadan once, uygulama bazli FORCE_RESIZE_APP
#  override'ini dogrudan
#     /data/misc/appcompat/compat_framework_overrides.xml
#  dosyasina yazar. Bu dosya CompatConfig tarafindan ACILISTA
#  okunur; yani etkisi bir sonraki yeniden baslatmada baslar.
#
#  Geri donus: dosyanin ilk hali <dosya>.fl.bak olarak saklanir,
#  uninstall.sh bunu geri koyar.
# ============================================================

FL_DIR=/data/adb/force_landscape
FLAG=$FL_DIR/xml_fallback.on
WANT=$FL_DIR/compat_wanted.txt
LOGF=$FL_DIR/landscape.log
XMLDIR=/data/misc/appcompat
XML=$XMLDIR/compat_framework_overrides.xml
BASE=$XML.fl.bak
BLK=$FL_DIR/.xmlblock.tmp
TMPF=$XML.fl.new
CHANGE=174042936

log() {
    echo "[$(date '+%Y-%m-%d %H:%M:%S')] post-fs-data: $*" >> "$LOGF" 2>/dev/null
}

# ============================================================
#  v2.8 — MIUI ORTA-PENCERE KAPATMA (system_server'dan ONCE)
#  Bugreport kaniti: MiuiEmbeddingWindow MID_BOUND=((719,0,2162,1800))
#  -> oyunlar ekranin ortasinda 1443 px'lik pencerede kaliyordu.
#  Cozum: ro.config.miui_activity_embedding_enable=false
#  NOT: "ro." prop'u ancak resetprop ile yazilabilir; setprop boot
#  sirasinda kilitlenme yapar, bu yuzden ASLA setprop kullanilmaz.
# ============================================================
EMB_PROP=ro.config.miui_activity_embedding_enable
EMB_WANT=$(sed -n 's/^MIUI_NO_EMBEDDING=//p' "$FL_DIR/config.conf" 2>/dev/null | head -n 1)
[ -n "$EMB_WANT" ] || EMB_WANT=true
RP=""
for b in /data/adb/ksu/bin/resetprop /data/adb/magisk/resetprop /data/adb/ap/bin/resetprop; do
    [ -x "$b" ] && { RP=$b; break; }
done
if [ -z "$RP" ]; then RP=$(command -v resetprop 2>/dev/null); fi
if [ "$EMB_WANT" = "true" ]; then
    if [ -n "$RP" ]; then
        "$RP" -n "$EMB_PROP" false > /dev/null 2>&1
        log "MIUI orta-pencere: $EMB_PROP -> $(getprop $EMB_PROP 2>/dev/null) (resetprop=$RP)"
    else
        log "MIUI orta-pencere: resetprop bulunamadi -> system.prop yolu kullanilacak"
    fi
else
    [ -n "$RP" ] && "$RP" -n "$EMB_PROP" true > /dev/null 2>&1
    log "MIUI orta-pencere: kullanici kapatti (config MIUI_NO_EMBEDDING=false)"
fi

[ -f "$FLAG" ] || exit 0
[ -f "$WANT" ] || { log "xml yedegi: istenen paket listesi yok"; exit 0; }

N=$(grep -c . "$WANT" 2>/dev/null)
[ "${N:-0}" -gt 0 ] || { log "xml yedegi: liste bos"; exit 0; }

mkdir -p "$XMLDIR" 2>/dev/null || { log "xml yedegi: $XMLDIR olusturulamadi"; exit 0; }

# bizim blogumuz: tek change, altinda paketler (tekrarsiz)
: > "$BLK" 2>/dev/null
echo "  <change id=\"$CHANGE\">" >> "$BLK"
sort -u "$WANT" 2>/dev/null | while IFS= read -r p; do
    case "$p" in ''|'#'*) continue ;; esac
    echo "    <package name=\"$p\" enabled=\"true\"/>" >> "$BLK"
done
echo "  </change>" >> "$BLK"

# v2.1: HER ZAMAN ilk halinden (BASE) uret -> kac kez calisirsa calissin
# ayni sonucu verir (v2.0'da ikinci calistirmada paketler cogaliyordu).
if [ ! -f "$BASE" ]; then
    if [ -f "$XML" ]; then
        cp -f "$XML" "$BASE" 2>/dev/null
    else
        {
            echo "<?xml version='1.0' encoding='utf-8' standalone='yes' ?>"
            echo "<overrides>"
            echo "</overrides>"
        } > "$BASE" 2>/dev/null
    fi
fi

awk 'NR==FNR { blk = blk $0 "\n"; next }
     /<\/overrides>/ && !done { printf "%s", blk; done = 1 }
     { print }' "$BLK" "$BASE" > "$TMPF" 2>/dev/null
if [ ! -s "$TMPF" ]; then
    log "xml yedegi: birlestirme basarisiz, yeni dosya yaziliyor"
    {
        echo "<?xml version='1.0' encoding='utf-8' standalone='yes' ?>"
        echo "<overrides>"
        cat "$BLK"
        echo "</overrides>"
    } > "$TMPF" 2>/dev/null
fi

if [ ! -s "$TMPF" ]; then
    log "xml yedegi: gecici dosya bos, vazgecildi"
    rm -f "$BLK" "$TMPF" 2>/dev/null
    exit 0
fi

# system_server (uid 1000) okuyabilmeli
chown 1000:1000 "$TMPF" 2>/dev/null
chmod 0640 "$TMPF" 2>/dev/null
chcon u:object_r:appcompat_data_file:s0 "$TMPF" 2>/dev/null

mv -f "$TMPF" "$XML" 2>/dev/null || { log "xml yedegi: dosya yazilamadi"; rm -f "$BLK" "$TMPF" 2>/dev/null; exit 0; }
chown 1000:1000 "$XML" 2>/dev/null
chmod 0640 "$XML" 2>/dev/null

rm -f "$BLK" 2>/dev/null
log "xml yedegi yazildi: $N paket -> $XML (yedek: $BASE, sonraki acilista etkili)"
exit 0
