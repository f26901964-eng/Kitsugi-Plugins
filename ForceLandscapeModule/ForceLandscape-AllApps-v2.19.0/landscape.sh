#!/system/bin/sh
# ============================================================
#  Force Landscape - All Apps   v2.0
#  Ortak uygulama mantigi. service.sh / action.sh / uninstall.sh
#  tarafindan "source" edilir. Kalici arka plan servisi YOKTUR.
#
#  v1.7 (v1.6 sahada yatay calisti ama dikey-only uygulamalar
#  ekranin ortasinda portre kutusunda kaliyordu):
#   - Eski (v1.5) config dosyasi korundugu icin COMPAT_FORCE_RESIZE
#     anahtari hic okunmuyordu -> config OTOMATIK goc ettirilir
#     (eksik anahtarlar varsayilanla eklenir).
#   - Uygulama bazli FORCE_RESIZE_APP artik SAYISAL id (174042936)
#     ile uygulanir (isim cozumu her ROM'da calismiyor), tum
#     kullanici uygulamalarina uygulanir ve tek tek dogrulanir.
#   - wm set-multi-window-config --supportsNonResizable 1 eklendi.
#   - force_resizable_activities'in SADECE ACI LISTA okundugu
#     tespit edildi -> degistiyse uyari + rapora not.
#
#  v2.0 (kullanici istegi): Action dugmesi yerine WEBUI.
#   - Uygulama bazli secim: "Yatay zorla" / "Dokunma" / "Otomatik"
#     (force.txt, never.txt). Mod: smart | whitelist | all | off.
#   - Tek karar fonksiyonu (fl_app_decision) hem am-compat hem XML
#     yolunda ayni sonucu verir.
#   - WebUI icin makine-okunur ciktilar (webui.sh).
#
#  v1.9 (sahadaki v1.7 logunda compat katmani HIC uygulanmamis
#  goruldu: "compat paket: 0 / kalan: 96"):
#   - Artik tek bir "am compat" denemesi basarisiz olunca TUM
#     letterbox cozumu sessizce iptal edilmiyor: 4 farkli yontem
#     sirayla denenir (am compat --no-kill / am compat / isimle /
#     cmd platform_compat), her denemenin ham ciktisi saklanir.
#   - Basari, komutun "Enabled change" demesiyle degil, sistemin
#     gercek durumuyla DOGRULANIR (dumpsys platform_compat).
#   - Hicbiri calismazsa bir sonraki acilista post-fs-data ile
#     /data/misc/appcompat/compat_framework_overrides.xml dosyasina
#     dogrudan yazilir (opt-in: COMPAT_XML_FALLBACK).
#   - Sure butcesi artik testten SONRA baslar ve tur basina en az
#     COMPAT_MIN_PER_TURN paket garanti edilir (v1.7'de butce ilk
#     pakette dolabiliyordu -> 0 paket).
#   - Test sonucu ekranda ve raporda gorunur (artik korlemesine
#     "calismiyor" demek yok).
# ============================================================

# ---------- v2.5: stdin koruma kalkani (SAHADAKI 87 PAKET HATASI) --------
# KANIT (SukiSU Ultra + MIUI 14, 2026-10-29 bugreport, logcat):
#   avc: denied { read } for path="/data/adb/force_landscape/compat_queue.txt"
#        scontext=u:r:system_server:s0  tclass=file permissive=0
#   cmd: Failure calling service activity: Failed transaction (2147483646)
# SEBEP: paket kuyrugu "while ... done < compat_queue.txt" ile okunuyordu.
# Dongunun icinden cagrilan am / cmd / dumpsys alt surecleri stdin olarak
# BU DOSYAYI devraliyor. system_server domaininde calisan "cmd" sureci
# /data/adb altini okuyamadigi icin SELinux okumayi reddediyor, binder
# cagrisi "Failed transaction" ile dusuyor. Sonuc: 88 paketten 1'i
# uygulaniyor, 87'si "kalan" kaliyordu (action log: kalan: 87).
# COZUM: tum sistem komutlari stdin olarak /dev/null alir.
am()           { command am "$@" </dev/null; }
cmd()          { command cmd "$@" </dev/null; }
pm()           { command pm "$@" </dev/null; }
dumpsys()      { command dumpsys "$@" </dev/null; }
settings()     { command settings "$@" </dev/null; }
wm()           { command wm "$@" </dev/null; }
device_config(){ command device_config "$@" </dev/null; }

FL_DIR=/data/adb/force_landscape
FL_CFG=$FL_DIR/config.conf
FL_LOG=$FL_DIR/landscape.log
FL_DEFAULT_CFG=/data/adb/modules/force_landscape_all/config.default.conf
FL_DONE=$FL_DIR/compat_applied.txt
FL_QUEUE=$FL_DIR/compat_queue.txt
FL_APPS=$FL_DIR/apps_cache.txt        # WebUI uygulama listesi (v2.2, onceden hesaplanir)
FL_META=$FL_DIR/pkg_meta.txt          # paket<TAB>surumAdi<TAB>surumKodu<TAB>hedefSdk<TAB>kurulum<TAB>apk<TAB>sistem (v2.3)
FL_PROG=$FL_DIR/apply_progress.txt    # parcali uygulama ilerlemesi (v2.3)
FL_CSTAT=$FL_DIR/compat_status.txt    # paket<TAB>ok|fail<TAB>yontem (v2.1)
FL_CERR=$FL_DIR/compat_errors.txt     # son 20 compat hatasi (v2.1)
FL_SKIP=$FL_DIR/compat_skip.txt       # yatay calisan -> dokunulmayacak uygulamalar
FL_ORIENT=$FL_DIR/orient_cache.txt    # pkg<TAB>mOrientation (ogrenilmis)
FL_ACTDUMP=$FL_DIR/.actdump.txt
FL_CTEST=$FL_DIR/compat_test.txt      # son compat testinin ham ciktisi
FL_WANT=$FL_DIR/compat_wanted.txt     # XML yedek yolu icin istenen paketler
FL_XMLFLAG=$FL_DIR/xml_fallback.on
FL_XML_DIR=/data/misc/appcompat
FL_XML_FILE=$FL_XML_DIR/compat_framework_overrides.xml
FL_FORCE=$FL_DIR/force.txt            # "yatay zorla" listesi (elle)
FL_NEVER=$FL_DIR/never.txt            # "dokunma" listesi (elle)
FL_TARGETS=$FL_DIR/compat_targets.txt # bu turda hedeflenen paketler
FL_STATE=$FL_DIR/state.txt            # son uygulamanin ozeti (webui icin)
FL_APPMODE=$FL_DIR/appmode.txt        # "ekrana yay" listesi (v2.7)
FL_APPMODE_ST=$FL_DIR/appmode_state.txt   # pkg<TAB>ok|fail<TAB>yontem (v2.7)
# v2.8: MIUI "activity embedding" — uyumsuz uygulamayi EKRANIN ORTASINDA
# dar bir pencereye koyan MIUI ozelligi. Sahadan gelen bugreport kaniti:
#   MiuiEmbeddingWindow: window=... MID_BOUND=SkRegion((719,0,2162,1800))
#   MiuiActivityEmbeddingController: activityRecord ... in middle
#   ro.config.miui_activity_embedding_enable=true
# Bu yuzden "ekrana yay" yapilsa bile oyun ortada kaliyordu. Asagidaki
# prop false yapilinca MIUI gommesi tamamen kapanir.
FL_MIUI_EMB_PROP=ro.config.miui_activity_embedding_enable
FL_MODVER=v2.19.0
FL_TAB=$(printf '\t')            # gercek sekme karakteri (IFS icin)

PATH=/system/bin:/system/xbin:/sbin:/data/adb/ksu/bin:$PATH
export PATH

# ---------- varsayilanlar (config bunlari ezer) --------------
AUTO_LANDSCAPE=true
ROTATION_MODE=auto            # auto | sensor | lock
ROTATION=1
FORCE_RESIZABLE=true
COMPAT_FORCE_RESIZE=true       # uygulama bazli FORCE_RESIZE_APP (letterbox cozumu)
COMPAT_MODE=smart             # smart | all | off
LEARN_ORIENTATION=true
COMPAT_ID=174042936            # Android 13: FORCE_RESIZE_APP
COMPAT_BUDGET=120             # saniye (boot'u bekletmemek icin butce)
COMPAT_MIN_PER_TURN=8         # tur basina garanti edilen paket sayisi
COMPAT_XML_FALLBACK=auto      # auto | off | on  (am compat calismazsa XML)
COMPAT_INCLUDE_SYSTEM=false
COMPAT_EXCLUDE="android com.android.systemui com.android.permissioncontroller com.google.android.permissioncontroller com.android.shell"
EXTRA_PACKAGES=""
IGNORE_ORIENTATION=true
SETTINGS_FALLBACK=true
DRY_RUN=false
REAPPLY_AT="0 8 20 45"
DISPLAYS=0
LOG=1
MAKE_REPORT=true

# v2.11 — MIUI MAILBOX (asil "ortada dar sutun" nedeni)
# MIUI Pad, tablete uyarlanmamis uygulamayi ekranin ORTASINDA telefon
# oraninda bir sutuna koyar (信箱模式 / mailbox). AOSP compat anahtarlari
# bunu KALDIRMAZ. true = secilen uygulamada mailbox'i MIUI kural
# dosyasindan kapat + "强制横屏" kurali yaz.
MIUI_MAILBOX=true

# MIUI "强制横屏" kurali (embedded_rules_list.xml) icin kural dizesi.
# nra:cr:rcr:nr = "her zaman yatay" (MIUI_MagicWindow ile ayni).
FL_RULE_LANDSCAPE="nra:cr:rcr:nr"
MAKE_REPORT=true
RESTART_SYSTEMUI=false

FL_METHOD_START=1
COMPAT_TURN_ABORTED=0
FL_LOG_OK=0
FL_IGN_OK=unknown
FL_MODE=unknown
FL_FIRST=1
FL_COMPAT_LAST=""
FL_FR_PREV=""
FL_REPORT=/sdcard/force_landscape_rapor.txt
FL_TIMEOUT=$(command -v timeout 2>/dev/null)

# ---------- hazirlik + config gocu ---------------------------
fl_count() {   # $1 = dosya -> satir sayisi (dosya yoksa 0)
    _c=$(grep -c . "$1" 2>/dev/null)
    echo "${_c:-0}"
}

fl_prepare() {
    [ -d "$FL_DIR" ] || mkdir -p "$FL_DIR" 2>/dev/null
    if [ ! -f "$FL_CFG" ] && [ -f "$FL_DEFAULT_CFG" ]; then
        cp -f "$FL_DEFAULT_CFG" "$FL_CFG" 2>/dev/null
    fi
    # log dosyasi yazilabilir mi (config okunmadan once de gerekli)
    if [ "$LOG" = "1" ] && ( : >> "$FL_LOG" ) 2>/dev/null; then
        FL_LOG_OK=1
    else
        FL_LOG_OK=0
    fi
    [ -f "$FL_FORCE" ] || : > "$FL_FORCE" 2>/dev/null
    [ -f "$FL_NEVER" ] || : > "$FL_NEVER" 2>/dev/null
    [ -f "$FL_APPMODE" ] || : > "$FL_APPMODE" 2>/dev/null
    fl_config_migrate
    [ -f "$FL_CFG" ] && . "$FL_CFG"
    if [ "$LOG" = "1" ] && ( : >> "$FL_LOG" ) 2>/dev/null; then
        FL_LOG_OK=1
    else
        FL_LOG_OK=0
    fi
}

# v1.5/v1.6'dan gelen config dosyasi silinmez; sadece:
#  1) eksik anahtarlar varsayilan degerleriyle eklenir,
#  2) eski surumde ISLEVSIZ kalmis tek anahtar (COMPAT_FORCE_RESIZE)
#     yeni varsayilana cekilir (v1.5'te bu anahtar hicbir sey yapmiyordu),
#  3) dosyaya surum isareti yazilir (bir daha dokunulmaz).
fl_config_migrate() {
    [ -f "$FL_CFG" ] || return 0
    [ -f "$FL_DEFAULT_CFG" ] || return 0
    _tmp=$FL_DIR/.cfg.new
    cp -f "$FL_CFG" "$_tmp" 2>/dev/null || return 0
    _added=0
    _first=1
    while IFS= read -r _line; do
        case "$_line" in
            ''|'#'*) continue ;;
        esac
        _key=${_line%%=*}
        case "$_key" in ''|*[!A-Za-z0-9_]*) continue ;; esac
        if ! grep -q "^[[:space:]]*$_key=" "$FL_CFG" 2>/dev/null; then
            if [ "$_first" = "1" ]; then
                {
                    echo ""
                    echo "# ---- v1.7 ile eklenen varsayilanlar ----"
                } >> "$_tmp"
                _first=0
            fi
            echo "$_line" >> "$_tmp"
            _added=$((_added + 1))
        fi
    done < "$FL_DEFAULT_CFG"

    _fixed=0
    if ! grep -q "^# fl_config_version=" "$FL_CFG" 2>/dev/null; then
        # eski surum config'i: v1.5'te COMPAT_FORCE_RESIZE anahtari
        # okunuyordu ama hicbir islevi yoktu -> yeni varsayilana cek
        if grep -q "^COMPAT_FORCE_RESIZE=" "$_tmp" 2>/dev/null; then
            sed 's/^COMPAT_FORCE_RESIZE=.*/COMPAT_FORCE_RESIZE=true/' "$_tmp" > "$_tmp.2" 2>/dev/null \
                && mv -f "$_tmp.2" "$_tmp" 2>/dev/null
            _fixed=1
        fi
        echo "" >> "$_tmp"
        echo "# fl_config_version=1.7" >> "$_tmp"
    fi

    if [ "$_added" -gt 0 ] || [ "$_fixed" -gt 0 ]; then
        cp -f "$FL_CFG" "$FL_CFG.bak" 2>/dev/null
        mv -f "$_tmp" "$FL_CFG" 2>/dev/null
        chmod 0644 "$FL_CFG" 2>/dev/null
        fl_log "config gocu: $_added yeni anahtar eklendi, COMPAT_FORCE_RESIZE duzeltildi=$_fixed (yedek: config.conf.bak)"
    else
        rm -f "$_tmp" "$_tmp.2" 2>/dev/null
    fi
}

fl_log() {
    [ "$LOG" = "1" ] || return 0
    [ "$FL_LOG_OK" = "1" ] || return 0
    (
        _sz=$(wc -c < "$FL_LOG" 2>/dev/null)
        [ -n "$_sz" ] && [ "$_sz" -gt 196608 ] && : > "$FL_LOG"
        echo "[$(date '+%Y-%m-%d %H:%M:%S')] $*" >> "$FL_LOG"
    ) 2>/dev/null
}

# ---------- komut calistirici: rc + hata ciktisi loga ---------
fl_run() {
    if [ "$DRY_RUN" = "true" ]; then
        fl_log "   (deneme) $*"
        return 0
    fi
    if [ -n "$FL_TIMEOUT" ]; then
        _o=$("$FL_TIMEOUT" 25 "$@" 2>&1); _r=$?
    else
        _o=$("$@" 2>&1); _r=$?
    fi
    _o=$(printf '%s' "$_o" | tr '\n' ' ')
    if [ -n "$_o" ]; then
        fl_log "   \$ $* -> rc=$_r | $_o"
    else
        fl_log "   \$ $* -> rc=$_r"
    fi
    return "$_r"
}

# ---------- ekran durumu okuma -------------------------------
fl_cur_size() {
    _c=$(dumpsys window displays 2>/dev/null | tr '\n' ' ' \
         | grep -o 'cur=[0-9][0-9]*x[0-9][0-9]*' | head -n 1)
    [ -n "$_c" ] || return 1
    echo "${_c#cur=}"
}

# 0 = yatay, 1 = dikey, 2 = okunamadi
fl_is_landscape() {
    _cs=$(fl_cur_size) || return 2
    _cw=${_cs%x*}
    _ch=${_cs#*x}
    case "$_cw" in ''|*[!0-9]*) return 2 ;; esac
    case "$_ch" in ''|*[!0-9]*) return 2 ;; esac
    if [ "$_cw" -gt "$_ch" ]; then
        return 0
    fi
    return 1
}

fl_detect_landscape() {
    _ps=$(wm size 2>/dev/null | sed -n 's/.*Physical size: *//p' | head -n 1)
    _w=${_ps%x*}
    _h=${_ps#*x}
    case "$_w" in ''|*[!0-9]*) return 1 ;; esac
    case "$_h" in ''|*[!0-9]*) return 1 ;; esac
    if [ "$_w" -gt "$_h" ]; then echo 0; else echo 1; fi
}

# ---------- bekleme ------------------------------------------
fl_wait_boot() {
    _i=0
    while [ "$(getprop sys.boot_completed 2>/dev/null)" != "1" ]; do
        sleep 2
        _i=$((_i + 1))
        [ "$_i" -gt 90 ] && return 1
    done
    return 0
}

fl_wait_wm() {
    _i=0
    while [ "$_i" -lt 20 ]; do
        [ -n "$(wm size 2>/dev/null)" ] && return 0
        sleep 1
        _i=$((_i + 1))
    done
    fl_log "UYARI: wm 20 sn icinde cevap vermedi"
    return 1
}

fl_wait_stable() {
    # system_server kararli mi? (boot sirasinda cokme dongusu olursa
    # agir isleri erteleyelim - sahadaki logda 22 saniyede 4 system_server
    # baslatildi). Kararliysa 0 doner.
    _i=0
    _p1=$(pidof system_server 2>/dev/null)
    [ -n "$_p1" ] || return 1
    while [ "$_i" -lt 6 ]; do
        sleep 5
        _i=$((_i + 1))
        _p2=$(pidof system_server 2>/dev/null)
        [ -n "$_p2" ] && [ "$_p1" = "$_p2" ] && return 0
        _p1=$_p2
    done
    return 1
}

# ---------- mod 1: sensor (tercih edilen) --------------------
fl_sensor_mode() {
    fl_log "mod=SENSOR: uygulama kilidi yok sayilir + sensor serbest"
    fl_run wm fixed-to-user-rotation -d 0 default
    fl_run settings put system accelerometer_rotation 1
    fl_run wm user-rotation free
    fl_log "  user-rotation: $(wm user-rotation 2>&1 | tr '\n' ' ')"
    fl_log "  ignore-orientation: $(wm get-ignore-orientation-request -d 0 2>&1)"
}

# ---------- mod 0: kapali (v2.14) ----------
# Eskiden ROTATION_MODE=off fl_apply'da "*) -> lock" dalina dusuyordu:
# kullanici WebUI'dan kapatiyor, yeniden basinca ekran yataya KILITLENIYORDU.
fl_off_mode() {
    fl_log "mod=KAPALI: ignore-orientation kapatiliyor, sensor serbest"
    for _od in $DISPLAYS; do
        fl_run wm set-ignore-orientation-request -d "$_od" false
    done
    fl_run wm fixed-to-user-rotation -d 0 default
    fl_run settings put system accelerometer_rotation 1
    fl_run wm user-rotation free
}

# ---------- mod 2: kilit (garantili, sensor kapali) ----------
fl_lock_landscape() {
    fl_log "mod=KILIT: ekran $ROTATION acisina kilitleniyor (uygulama istekleri yok sayilir)"
    fl_run wm fixed-to-user-rotation -d 0 enabled
    fl_run wm user-rotation lock "$ROTATION"
    if [ "$SETTINGS_FALLBACK" = "true" ]; then
        fl_run settings put system accelerometer_rotation 0
        fl_run settings put system user_rotation "$ROTATION"
    fi

    # KAPALI DONGU DOGRULAMA: ekran gercekten yatay mi?
    _base=$ROTATION
    _i=0
    while [ "$_i" -lt 4 ]; do
        _ang=$(( (_base + _i) % 4 ))
        if [ "$_i" -gt 0 ]; then
            ROTATION=$_ang
            fl_run wm user-rotation lock "$ROTATION"
            if [ "$SETTINGS_FALLBACK" = "true" ]; then
                fl_run settings put system user_rotation "$ROTATION"
            fi
        fi
        sleep 1
        fl_is_landscape
        _rc=$?
        if [ "$_rc" = "0" ]; then
            fl_log "  dogrulama: ekran YATAY (aci=$ROTATION, cur=$(fl_cur_size))"
            return 0
        fi
        if [ "$_rc" = "2" ]; then
            fl_log "  dogrulama: ekran boyutu okunamadi, aci dogrulanamadi (aci=$ROTATION)"
            return 0
        fi
        fl_log "  dogrulama: aci=$ROTATION yatay degil (cur=$(fl_cur_size)) -> sonraki aci"
        _i=$((_i + 1))
    done
    fl_log "  dogrulama: 4 aci da yatay sonuc vermedi - ROM farkli davraniyor"
    return 1
}

# ---------- letterbox: uygulama bazli FORCE_RESIZE_APP -------
# v1.8: "smart" mod. Kendi kendine YATAY calisan uygulamalara (oyunlar,
# video oynaticilar) DOKUNULMAZ; cunku onlar zaten ekrani doldurur ve
# zorla resize edilmeleri bazi oyunlarda cokme/hata sebebi olabilir.

# Yatay oldugu bilinen populer oyunlar: ilk acilista ogrenme firsati
# olmadan da korunmalari icin yerlesik liste.
FL_BUILTIN_SKIP="com.supercell.clashroyale com.supercell.clashofclans com.supercell.brawlstars com.supercell.squad com.supercell.juice com.mojang.minecraftpe com.tencent.ig com.dts.freefireth com.dts.freefiremax com.garena.game.kgtr com.mobile.legends com.riotgames.league.wildrift com.roblox.client com.ea.gp.fifamobile com.konami.pesam com.gameloft.android.ANMP.GloftA9HM com.miHoYo.GenshinImpact com.HoYoverse.genshin com.activision.callofduty.shooter com.tencent.tmgp.pubgmhd com.innersloth.spacemafia com.trueaxis.truesurfers com.playdigious.brawlhalla com.epicgames.fortnite com.rockstargames.gtasa com.aspyr.bully com.ea.gp.pvz2 com.king.candycrushsaga"

# sistem/hariç paketler (config: COMPAT_EXCLUDE)
fl_pkg_excluded() {
    case " $COMPAT_EXCLUDE " in
        *" $1 "*) return 0 ;;
    esac
    return 1
}

# yerleşik "yatay calisir" oyun/video listesi (zorla denmedikce atlanir)
fl_builtin_skip() {
    case " $FL_BUILTIN_SKIP " in
        *" $1 "*) return 0 ;;
    esac
    return 1
}

# ---------- elle secim listeleri (v2.0) ----------
fl_list_has() {
    [ -f "$1" ] || return 1
    grep -qx "$2" "$1" 2>/dev/null
}

fl_list_add() {
    [ -n "$2" ] || return 1
    touch "$1" 2>/dev/null
    fl_list_has "$1" "$2" && return 0
    echo "$2" >> "$1" 2>/dev/null
    sort -u "$1" -o "$1" 2>/dev/null
    fl_maps_reset
}

fl_list_del() {
    [ -f "$1" ] || return 0
    grep -vx "$2" "$1" > "$1.tmp" 2>/dev/null
    mv -f "$1.tmp" "$1" 2>/dev/null
    fl_maps_reset
    return 0
}

# Tek karar noktasi: cikti "force" ya da "skip".
# Sira: dokunma listesi > sistem hariç > zorla listesi > oyun listesi > mod
fl_app_decision() {
    _ap=$1
    fl_in_never "$_ap" && { echo skip; return 0; }
    fl_pkg_excluded "$_ap" && { echo skip; return 0; }
    fl_in_force "$_ap" && { echo force; return 0; }
    fl_builtin_skip "$_ap" && { echo skip; return 0; }
    case "$COMPAT_MODE" in
        off|whitelist) echo skip ;;
        all)           echo force ;;
        *)
            case "$(fl_orient_class "$_ap")" in
                landscape|free) echo skip ;;
                *)              echo force ;;
            esac
            ;;
    esac
}

fl_decision_why() {
    _ap=$1
    fl_in_never "$_ap" && { echo "dokunma-listesi"; return 0; }
    fl_pkg_excluded "$_ap" && { echo "sistem-haric"; return 0; }
    fl_in_force "$_ap" && { echo "sen-sectigin"; return 0; }
    fl_builtin_skip "$_ap" && { echo "oyun-listesi"; return 0; }
    case "$COMPAT_MODE" in
        off)       echo "mod-kapali" ;;
        whitelist) echo "beyaz-liste-disi" ;;
        all)       echo "mod-all" ;;
        *)
            case "$(fl_orient_class "$_ap")" in
                landscape) echo "kendi-yatay" ;;
                free)      echo "serbest-yon" ;;
                portrait)  echo "kendi-dikey" ;;
                *)         echo "yon-bilinmiyor" ;;
            esac
            ;;
    esac
}

# ---------- yon ogrenme (dumpsys activity activities) --------
fl_learn_orientation() {
    [ "$LEARN_ORIENTATION" = "true" ] || return 0
    dumpsys activity activities 2>/dev/null > "$FL_ACTDUMP"
    if [ ! -s "$FL_ACTDUMP" ]; then
        fl_log "ogrenme: dumpsys activity ciktisi bos"
        return 0
    fi
    _new=$FL_DIR/.orient.new
    awk '
        /ActivityRecord\{/ {
            if (pkg != "" && ori != "") print pkg "\t" ori;
            pkg = ""; ori = "";
            if (match($0, /u[0-9]+ [^ ]+\//)) {
                s = substr($0, RSTART, RLENGTH);
                sub(/u[0-9]+ /, "", s);
                sub(/\/.*/, "", s);
                pkg = s;
            }
        }
        /mOrientation=/ {
            line = $0;
            sub(/.*mOrientation=/, "", line);
            sub(/[ \t].*/, "", line);
            ori = line;
        }
        END { if (pkg != "" && ori != "") print pkg "\t" ori }
    ' "$FL_ACTDUMP" 2>/dev/null | sort -u > "$_new"
    rm -f "$FL_ACTDUMP"

    if [ ! -s "$_new" ]; then
        fl_log "ogrenme: yon bilgisi okunamadi (dump formati farkli) - bilinmeyen uygulamalara uygulanir"
        rm -f "$_new"
        return 0
    fi

    _mrg=$FL_DIR/.orient.mrg
    if [ -f "$FL_ORIENT" ]; then
        awk -F'\t' 'NR==FNR { n[$1]=1; next } !($1 in n)' "$_new" "$FL_ORIENT" > "$_mrg" 2>/dev/null
    else
        : > "$_mrg"
    fi
    cat "$_new" >> "$_mrg" 2>/dev/null
    sort -u "$_mrg" -o "$FL_ORIENT" 2>/dev/null || { sort -u "$_mrg" > "$FL_ORIENT" 2>/dev/null; }
    rm -f "$_mrg" "$_new"
    fl_maps_reset
    fl_log "ogrenme: $(grep -c . "$FL_ORIENT" 2>/dev/null) uygulama icin yon bilgisi kayitli"
}

# landscape | portrait | free | unknown
# v2.0: hiz. Yuzlerce paket icin her seferinde awk/grep acmamak uzere
# listeler bir kez belleğe alinir (tek surec icinde).
fl_maps_reset() { FL_MAPS_LOADED=""; FL_FORCE_MAP=""; FL_NEVER_MAP=""; FL_ORIENT_MAP=""; }

fl_maps_load() {
    [ "$FL_MAPS_LOADED" = "1" ] && return 0
    FL_MAPS_LOADED=1
    if [ -f "$FL_FORCE" ]; then FL_FORCE_MAP=" $(tr '\n' ' ' < "$FL_FORCE") "; else FL_FORCE_MAP=" "; fi
    if [ -f "$FL_NEVER" ]; then FL_NEVER_MAP=" $(tr '\n' ' ' < "$FL_NEVER") "; else FL_NEVER_MAP=" "; fi
    if [ ! -f "$FL_ORIENT" ]; then FL_ORIENT_MAP=" "; fi
    [ -n "$FL_ORIENT_MAP" ] || FL_ORIENT_MAP=" $(awk -F'\t' 'NF>=2 {
            v = $2; c = "free";
            if (v ~ /LANDSCAPE/) c = "landscape";
            else if (v == "SCREEN_ORIENTATION_PORTRAIT" || v == "SCREEN_ORIENTATION_REVERSE_PORTRAIT" ||
                     v == "SCREEN_ORIENTATION_SENSOR_PORTRAIT" || v == "SCREEN_ORIENTATION_USER_PORTRAIT") c = "portrait";
            print $1 ":" c
        }' "$FL_ORIENT" 2>/dev/null | tr '\n' ' ') "
}

fl_in_force() { fl_maps_load; case "$FL_FORCE_MAP" in *" $1 "*) return 0 ;; esac; return 1; }
fl_in_never() { fl_maps_load; case "$FL_NEVER_MAP" in *" $1 "*) return 0 ;; esac; return 1; }

fl_orient_class() {
    fl_maps_load
    case "$FL_ORIENT_MAP" in
        *" $1:landscape "*) echo landscape; return ;;
        *" $1:portrait "*)  echo portrait;  return ;;
        *" $1:free "*)      echo free;      return ;;
    esac
    echo unknown
}

# ---------- v2.2: fork'suz karar + uygulama onbellegi ----------
# WebUI'nin uygulama listesi her acilista yeniden hesaplanirsa cihazda
# ~10 saniye suruyor ve WebUI kopru zaman asimina takiliyordu (sahada:
# liste bos geldi). Artik liste ONCEDEN hesaplanip apps_cache.txt'e yazilir;
# WebUI sadece cat eder.
#
# fl_decide <paket> -> FLD (force|skip), FLDW (gerekce), FLOC (yon)
# Alt kabuk (subshell) YOK: sadece case/pattern -> paket basina 0 fork.
fl_decide() {
    _dp=$1
    fl_maps_load
    FLOC=unknown
    case "$FL_NEVER_MAP" in *" $_dp "*) FLD=skip;  FLDW=dokunma-listesi; return 0 ;; esac
    case " $COMPAT_EXCLUDE " in *" $_dp "*) FLD=skip; FLDW=sistem-haric; return 0 ;; esac
    case "$FL_FORCE_MAP" in *" $_dp "*) FLD=force; FLDW=sen-sectigin; return 0 ;; esac
    case " $FL_BUILTIN_SKIP " in *" $_dp "*) FLD=skip; FLDW=oyun-listesi; return 0 ;; esac
    case "$FL_ORIENT_MAP" in
        *" $_dp:landscape "*) FLOC=landscape ;;
        *" $_dp:portrait "*)  FLOC=portrait  ;;
        *" $_dp:free "*)      FLOC=free      ;;
        *)                    FLOC=unknown   ;;
    esac
    case "$COMPAT_MODE" in
        off)       FLD=skip;  FLDW=mod-kapali ;;
        whitelist) FLD=skip;  FLDW=beyaz-liste-disi ;;
        all)       FLD=force; FLDW=mod-all ;;
        *)
            case "$FLOC" in
                landscape) FLD=skip;  FLDW=kendi-yatay ;;
                free)      FLD=skip;  FLDW=serbest-yon ;;
                portrait)  FLD=force; FLDW=kendi-dikey ;;
                *)         FLD=force; FLDW=yon-bilinmiyor ;;
            esac ;;
    esac
}

# ---------- v2.3: paket ust verisi + cihaz bilgisi ----------
fl_file_tail() {   # $1 dosya  $2 satir  -> son N satir (tail komutuna bagimli degil)
    [ -f "$1" ] || { echo "(dosya yok: $1)"; return 0; }
    awk -v n="${2:-200}" '{ a[NR]=$0 } END { s=NR-n+1; if (s<1) s=1; for (i=s;i<=NR;i++) print a[i] }' "$1" 2>/dev/null
}

fl_log_tail() {   # $1 satir : modul logu; bos/okunamazsa sebebini YAZAR
    _ln=${1:-200}
    if [ ! -f "$FL_LOG" ]; then
        echo "(log dosyasi yok: $FL_LOG)"
        echo "(modul henuz bir islem yapmadi olabilir — Durum > Uygula dedikten sonra tekrar bak)"
        return 0
    fi
    _lb=$(wc -c < "$FL_LOG" 2>/dev/null)
    case "$_lb" in ''|*[!0-9]*) _lb=0 ;; esac
    if [ "$_lb" -eq 0 ]; then
        echo "(log dosyasi bos: $FL_LOG)"
        echo "ipucu: LOG=1 mi? ls -l $FL_DIR"
        return 0
    fi
    fl_file_tail "$FL_LOG" "$_ln"
}

fl_perms_fix() {   # WebUI'nin okuyabilmesi icin durum dosyalarini 0644 yap
    chmod 0755 "$FL_DIR" 2>/dev/null
    for _pf in "$FL_DIR"/*.txt "$FL_DIR"/config.conf "$FL_LOG"; do
        [ -f "$_pf" ] && chmod 0644 "$_pf" 2>/dev/null
    done
    return 0
}

fl_pkg_meta_build() {   # tek dumpsys cagrisi -> tum paketlerin surum/boyut bilgisi
    _d=$(dumpsys package packages 2>/dev/null)
    [ -n "$_d" ] || return 1
    printf '%s\n' "$_d" | awk '
        function clean(s){ sub(/^[ \t]+/,"",s); return s }
        /^[ \t]*Package \[/ {
            if (pkg != "") print pkg "\t" vn "\t" vc "\t" ts "\t" fi "\t" cp "\t" sys;
            pkg=$2; gsub(/[\[\]\(\):]/,"",pkg);
            vn="-"; vc="-"; ts="-"; fi="-"; cp="-"; sys="0"; next
        }
        /^[ \t]*codePath=/   { cp=$0; sub(/^[ \t]*codePath=/,"",cp); next }
        /^[ \t]*versionName=/ { vn=$0; sub(/^[ \t]*versionName=/,"",vn); next }
        /^[ \t]*versionCode=/ {
            vc=$1; sub(/versionCode=/,"",vc);
            for(i=2;i<=NF;i++) if ($i ~ /^targetSdk=/) { ts=$i; sub(/targetSdk=/,"",ts) }
            next
        }
        /^[ \t]*(firstInstallTime|lastUpdateTime)=/ {
            if (fi=="-") { fi=$0; sub(/^[ \t]*(firstInstallTime|lastUpdateTime)=/,"",fi) } ; next
        }
        /^[ \t]*flags=\[/ { if ($0 ~ /SYSTEM/) sys="1"; next }
        END { if (pkg != "") print pkg "\t" vn "\t" vc "\t" ts "\t" fi "\t" cp "\t" sys }
    ' > "$FL_META.tmp" 2>/dev/null
    [ -s "$FL_META.tmp" ] || { rm -f "$FL_META.tmp"; return 1; }
    mv -f "$FL_META.tmp" "$FL_META" 2>/dev/null
    chmod 0644 "$FL_META" 2>/dev/null
    return 0
}

fl_meta_get() {   # $1 = paket  -> alanlar (bos ise hepsi "-")
    _mg=$(awk -F'\t' -v p="$1" '$1==p { print $2"\t"$3"\t"$4"\t"$5"\t"$6"\t"$7; exit }' "$FL_META" 2>/dev/null)
    [ -n "$_mg" ] && echo "$_mg" || printf -- "-\t-\t-\t-\t-\t-\n"
}

fl_apk_size() {   # $1 = yol (dosya YA DA dizin) -> bayt (yoksa 0)
    if [ -f "$1" ]; then
        _b=$(wc -c < "$1" 2>/dev/null | tr -d ' ')
        case "$_b" in ''|*[!0-9]*) echo 0 ;; *) echo "$_b" ;; esac
    elif [ -d "$1" ]; then
        # Android 10 ve oncesi codePath bir DIZIN (or. /system_ext/priv-app/X)
        _k=$(du -sk "$1" 2>/dev/null | awk '{print $1}')
        case "$_k" in ''|*[!0-9]*) echo 0 ;; *) echo $((_k * 1024)) ;; esac
    else
        echo 0
    fi
}

fl_app_label_guess() {   # paket adindan okunabilir ad (WebUI yoksa yedek)
    _lp=$1
    _lp=${_lp##*.}
    echo "$_lp"
}

fl_sysinfo() {   # cihaz bilgisi (tek cagri ~0.7 s)
    _g() { getprop "$1" 2>/dev/null; }
    _mt=$(awk '/^MemTotal:/{print $2}' /proc/meminfo 2>/dev/null)
    _ma=$(awk '/^MemAvailable:/{print $2}' /proc/meminfo 2>/dev/null)
    _up=$(awk '{print int($1)}' /proc/uptime 2>/dev/null)
    _df=$(df -k /data 2>/dev/null | tail -n 1)
    [ -n "$_df" ] || _df=$(df -k /data/user/0 2>/dev/null | tail -n 1)
    [ -n "$_df" ] || _df=$(df -k / 2>/dev/null | tail -n 1)
    _dt=$(echo "$_df" | awk '{print $2}')
    _du=$(echo "$_df" | awk '{print $3}')
    case "$_dt" in ''|*[!0-9]*) _dt="" ;; esac
    case "$_du" in ''|*[!0-9]*) _du="" ;; esac
    _bd=$(dumpsys battery 2>/dev/null)
    _bat=$(printf '%s\n' "$_bd" | awk -F': ' '/level:/{print $2; exit}')
    _btm=$(printf '%s\n' "$_bd" | awk -F': ' '/temperature:/{print $2; exit}')
    # v2.6: "wm size" ciktisi "Physical size: 2880x1800" biciminde; bosluklar
    # silinince ekranda "Physicalsize:2880x1800" gibi bozuk yaziliyordu.
    _szp=$(wm size 2>/dev/null | sed -n 's/.*Physical size: *//p' | head -n 1)
    _szo=$(wm size 2>/dev/null | sed -n 's/.*Override size: *//p' | head -n 1)
    _sz=$_szp
    [ -n "$_szo" ] && _sz="$_szp (override: $_szo)"
    _dnp=$(wm density 2>/dev/null | sed -n 's/.*Physical density: *//p' | head -n 1)
    _dno=$(wm density 2>/dev/null | sed -n 's/.*Override density: *//p' | head -n 1)
    _dn=$_dnp
    [ -n "$_dno" ] && _dn="$_dnp (override: $_dno)"
    _rr=$(settings get system peak_refresh_rate 2>/dev/null)
    case "$_rr" in ''|null|NULL) _rr="" ;; esac
    _ksu=""
    if command -v ksud >/dev/null 2>&1; then _ksu=$(ksud -V 2>/dev/null | head -n 1); fi
    [ -n "$_ksu" ] || _ksu=$(_g KSU_VERSION)
    echo "surum|$FL_MODVER"
    echo "model|$(_g ro.product.model)"
    echo "marka|$(_g ro.product.brand)"
    echo "cihaz|$(_g ro.product.device)"
    echo "urun|$(_g ro.product.name)"
    echo "android|$(_g ro.build.version.release)"
    echo "sdk|$(_g ro.build.version.sdk)"
    echo "derleme|$(_g ro.build.version.incremental)"
    echo "yama|$(_g ro.build.version.security_patch)"
    echo "miui|$(_g ro.miui.ui.version.name)"
    echo "hid|$(_g ro.build.host)"
    echo "abi|$(_g ro.product.cpu.abi)"
    echo "cekirdek|$(uname -r 2>/dev/null)"
    echo "mimari|$(uname -m 2>/dev/null)"
    echo "selinux|$(getenforce 2>/dev/null)"
    echo "root|$(id -u 2>/dev/null)"
    echo "ksu|$_ksu"
    echo "ram_toplam|$_mt"
    echo "ram_bos|$_ma"
    echo "depolama_toplam|$_dt"
    echo "depolama_dolu|$_du"
    echo "uptime|$_up"
    echo "batarya|$_bat"
    echo "batarya_sicaklik|$_btm"
    echo "ekran_cozunurluk|$_sz"
    echo "ekran_yogunluk|$_dn"
    echo "yenileme|$_rr"
    echo "ekran|$(fl_cur_size 2>/dev/null)"
    fl_is_landscape; echo "yon|$?"
}

fl_appinfo() {   # $1 = paket -> ayrintili bilgi (tek paket, ~0.4 s)
    _ip=$1
    fl_maps_load
    fl_decide "$_ip"
    _vn="-"; _vc="-"; _ts="-"; _fi="-"; _cp="-"; _sy=0
    if [ -s "$FL_META" ]; then
        while IFS=$FL_TAB read -r _a _b _c _d _e _f _g; do
            [ "$_a" = "$_ip" ] || continue
            _vn=$_b; _vc=$_c; _ts=$_d; _fi=$_e; _cp=$_f; _sy=$_g
            break
        done < "$FL_META"
    fi
    _sz=0
    case "$_cp" in /*) _sz=$(fl_apk_size "$_cp") ;; esac
    _pid=$(pidof "$_ip" 2>/dev/null | tr ' ' ',')
    case "$_pid" in ''|' ') _run=0 ;; *) _run=1 ;; esac
    _cm=""; _cy=""
    if [ -f "$FL_CSTAT" ]; then
        while IFS=$FL_TAB read -r _a _b _c; do
            [ "$_a" = "$_ip" ] || continue
            _cm=$_b; _cy=$_c; break
        done < "$FL_CSTAT"
    fi
    _act=$(cmd package resolve-activity --brief "$_ip" 2>/dev/null | tail -n 1)
    echo "paket|$_ip"
    echo "surum|$_vn"
    echo "surum_kodu|$_vc"
    echo "hedef_sdk|$_ts"
    echo "kurulum|$_fi"
    echo "apk|$_cp"
    echo "boyut|$_sz"
    echo "sistem|${_sy:-0}"
    echo "calisiyor|$_run"
    echo "pid|$_pid"
    echo "baslatici|$_act"
    echo "karar|$FLD"
    echo "neden|$FLDW"
    echo "yon|$FLOC"
    echo "compat|$_cm"
    echo "compat_yontem|$_cy"
}

fl_app_start() {   # uygulamayi ac (yeni yon ayari devreye girsin diye)
    _sp=$1
    monkey -p "$_sp" -c android.intent.category.LAUNCHER 1 > /dev/null 2>&1 && return 0
    _sa=$(cmd package resolve-activity --brief "$_sp" 2>/dev/null | tail -n 1)
    case "$_sa" in */*) am start -n "$_sa" > /dev/null 2>&1 && return 0 ;; esac
    am start -a android.intent.action.MAIN -c android.intent.category.LAUNCHER "$_sp" > /dev/null 2>&1
}

fl_app_stop() { am force-stop "$1" > /dev/null 2>&1; }

fl_app_restart() {
    _rp=$1
    fl_app_stop "$_rp"
    sleep 1
    fl_app_start "$_rp"
}

fl_apps_cache_build() {
    # pkg  karar  uygulanmis  yon  neden  surum  boyut  hedefSdk  kurulum  ekranaYay (+ son satir #ts|)
    fl_appmode_map
    fl_compat_queue || return 1
    [ -s "$FL_QUEUE" ] || return 1
    fl_maps_reset
    fl_maps_load
    [ -s "$FL_META" ] || fl_pkg_meta_build > /dev/null 2>&1
    if [ -f "$FL_DONE" ]; then _dn=" $(tr '\n' ' ' < "$FL_DONE") "; else _dn=" "; fi
    _tmp=$FL_APPS.tmp
    : > "$_tmp" 2>/dev/null || return 1
    for _p in $(cat "$FL_QUEUE" 2>/dev/null); do
        [ -n "$_p" ] || continue
        fl_decide "$_p"
        case "$_dn" in *" $_p "*) _ap=1 ;; *) _ap=0 ;; esac
        _vn="-"; _vc="-"; _ts="-"; _fi="-"; _cp="-"
        if [ -s "$FL_META" ]; then
            while IFS=$FL_TAB read -r _a _b _c _d _e _f _g; do
                [ "$_a" = "$_p" ] || continue
                _vn=$_b; _vc=$_c; _ts=$_d; _fi=$_e; _cp=$_f
                break
            done < "$FL_META"
        fi
        _sz=0
        case "$_cp" in /*) _sz=$(fl_apk_size "$_cp") ;; esac
        fl_appmode_has "$_p" && _am=1 || _am=0
        if [ "$_am" = "1" ]; then _amst=$(fl_appmode_state_get "$_p"); else _amst="-"; fi
        printf '%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\n' \
            "$_p" "$FLD" "$_ap" "$FLOC" "$FLDW" "$_vn" "$_sz" "$_ts" "$_fi" "$_am" "$_amst" >> "$_tmp"
    done
    echo "#ts|$(date +%s)" >> "$_tmp"
    mv -f "$_tmp" "$FL_APPS" 2>/dev/null
    chmod 0644 "$FL_APPS" 2>/dev/null
    return 0
}

fl_compat_status_get() {   # pkg -> ok|fail|-
    [ -f "$FL_CSTAT" ] || { echo "-"; return 0; }
    _v=$(awk -F'\t' -v p="$1" '$1 == p { print $2; exit }' "$FL_CSTAT" 2>/dev/null)
    [ -n "$_v" ] || _v="-"
    echo "$_v"
}

fl_cache_update_one() {   # tek paketin satirini onbellekte guncelle (10 sutun korunur)
    _p=$1
    [ -s "$FL_APPS" ] || return 0
    fl_appmode_map
    fl_decide "$_p"
    if [ -f "$FL_DONE" ] && grep -qx "$_p" "$FL_DONE" 2>/dev/null; then _ap=1; else _ap=0; fi
    _vn="-"; _vc="-"; _ts="-"; _fi="-"; _cp="-"
    if [ -s "$FL_META" ]; then
        while IFS=$FL_TAB read -r _a _b _c _d _e _f _g; do
            [ "$_a" = "$_p" ] || continue
            _vn=$_b; _vc=$_c; _ts=$_d; _fi=$_e; _cp=$_f
            break
        done < "$FL_META"
    fi
    _sz=0
    case "$_cp" in /*) _sz=$(fl_apk_size "$_cp") ;; esac
    fl_appmode_has "$_p" && _am=1 || _am=0
    if [ "$_am" = "1" ]; then _amst=$(fl_appmode_state_get "$_p"); else _amst="-"; fi
    awk -F'\t' -v OFS='\t' -v p="$_p" -v d="$FLD" -v a="$_ap" -v o="$FLOC" -v w="$FLDW" \
        -v v="$_vn" -v z="$_sz" -v t="$_ts" -v i="$_fi" -v m="$_am" -v s="$_amst" '
        $1==p { $2=d; $3=a; $4=o; $5=w; $6=v; $7=z; $8=t; $9=i; $10=m; $11=s; found=1 }
        { print }
        END { if (!found) print p, d, a, o, w, v, z, t, i, m, s }
    ' "$FL_APPS" > "$FL_APPS.tmp" 2>/dev/null && mv -f "$FL_APPS.tmp" "$FL_APPS" 2>/dev/null
    chmod 0644 "$FL_APPS" 2>/dev/null
    return 0
}

fl_apps_cache_age() {
    [ -s "$FL_APPS" ] || { echo 999999; return; }
    _t=$(sed -n 's/^#ts|//p' "$FL_APPS" 2>/dev/null | head -n 1)
    case "$_t" in ''|*[!0-9]*) echo 999999; return ;; esac
    echo $(( $(date +%s) - _t ))
}

fl_state_set() {  # anahtar deger  (state.txt icinde tek satir guncelle/ekle)
    [ -f "$FL_STATE" ] || : > "$FL_STATE" 2>/dev/null
    [ -f "$FL_STATE" ] || return 0
    _k=$1; _v=$2
    if grep -q "^$_k=" "$FL_STATE" 2>/dev/null; then
        sed "s|^$_k=.*|$_k=$_v|" "$FL_STATE" > "$FL_STATE.tmp" 2>/dev/null && mv -f "$FL_STATE.tmp" "$FL_STATE" 2>/dev/null
    else
        echo "$_k=$_v" >> "$FL_STATE" 2>/dev/null
    fi
    chmod 0644 "$FL_STATE" 2>/dev/null
}

fl_skip_add() {
    [ -f "$FL_SKIP" ] || : > "$FL_SKIP"
    grep -qx "$1" "$FL_SKIP" 2>/dev/null || echo "$1" >> "$FL_SKIP"
}

fl_skip_has() {
    [ -f "$FL_SKIP" ] || return 1
    grep -qx "$1" "$FL_SKIP" 2>/dev/null
}

fl_compat_queue() {
    _tmp=$FL_DIR/.pkgs.new
    : > "$_tmp" 2>/dev/null
    # v2.4: "pm list packages -3" bazi ROM'larda bos donebiliyor
    # (kullanici profili / kisitli kabuk). Sirayla 4 yol denenir.
    pm list packages -3 2>/dev/null | sed 's/^package://' >> "$_tmp"
    if [ ! -s "$_tmp" ]; then
        pm list packages -3 --user 0 2>/dev/null | sed 's/^package://' >> "$_tmp"
    fi
    if [ ! -s "$_tmp" ]; then
        cmd package list packages -3 2>/dev/null | sed 's/^package://' >> "$_tmp"
    fi
    if [ ! -s "$_tmp" ]; then
        pm list packages 2>/dev/null | sed 's/^package://' | grep -v '^android$' >> "$_tmp"
    fi
    if [ "$COMPAT_INCLUDE_SYSTEM" = "true" ]; then
        pm list packages -s 2>/dev/null | sed 's/^package://' >> "$_tmp"
    fi
    for _p in $EXTRA_PACKAGES; do echo "$_p" >> "$_tmp"; done
    grep -v '^$' "$_tmp" 2>/dev/null | sort -u > "$FL_QUEUE" 2>/dev/null
    rm -f "$_tmp" 2>/dev/null
}

fl_compat_count() {
    [ -f "$FL_DONE" ] || { echo 0; return; }
    grep -c . "$FL_DONE" 2>/dev/null | head -n 1
}

fl_compat_skipped() {
    [ -f "$FL_SKIP" ] || { echo 0; return; }
    grep -c . "$FL_SKIP" 2>/dev/null | head -n 1
}

# v2.0: hedef listesi (compat_targets.txt) uzerinden "kalan" hesabi.
# Hedefler her turda fl_compat_targets ile yeniden uretilir.
fl_compat_targets() {
    : > "$FL_TARGETS" 2>/dev/null
    [ -f "$FL_QUEUE" ] || return 0
    for _tp in $(cat "$FL_QUEUE" 2>/dev/null); do
        [ -n "$_tp" ] || continue
        [ "$(fl_app_decision "$_tp")" = "force" ] || continue
        echo "$_tp" >> "$FL_TARGETS"
    done
}

fl_compat_left() {
    if [ -f "$FL_TARGETS" ]; then
        _l=$(awk 'NR==FNR { h[$0] = 1; next } NF && !($0 in h)' "$FL_DONE" "$FL_TARGETS" 2>/dev/null | grep -c .)
        echo "${_l:-0}"
        return 0
    fi
    echo 0
}

# ---------- compat: letterbox katmani (v2.1) ------------------
# Sorun: dikey uygulama ekranin ortasinda dar sutuna sikisiyor (letterbox).
# Cozum: uygulama bazli FORCE_RESIZE_APP compat change'i.
#
# v2.0 sahada: kuyruk ve test yazildi ama TEK paket bile uygulanmadi
# ("compat paket: 0"), log'da da neden yoktu. Bu surumde:
#   * her paketin sonucu compat_status.txt'e yazilir,
#   * hatalarin ham ciktisi compat_errors.txt'e yazilir (son 20),
#   * 3 mekanizma sirayla denenir, dongu hata yuzunden SESSIZCE kesilmez,
#   * dongunun gercekten dondugu log'a yazilir ("islenen N").
#
# Mekanizmalar:
#   1) am compat enable --no-kill ID pkg     (uygulama kapatilmaz, onerilen)
#   2) am compat enable ID pkg               (kalici; uygulamayi yeniden baslatir)
#   3) cmd platform_compat enable ID pkg
#   4) XML yedegi: post-fs-data.sh sonraki acilista yazar

fl_compat_dump() {
    # dumpsys platform_compat ciktisi turda bir kez alinir (yavas komut)
    [ "$FL_CDUMP_TS" = "$(date +%s)" ] && [ -n "$FL_CDUMP" ] && return 0
    FL_CDUMP=$(dumpsys platform_compat 2>/dev/null)
    FL_CDUMP_TS=$(date +%s)
    [ -n "$FL_CDUMP" ]
}

fl_compat_verify() {
    # $1 = paket.
    # 0 = sistemde gorunuyor, 1 = gorunmuyor, 2 = dogrulanamiyor (dumpsys yok)
    # (Format surumden surume degisebildigi icin sadece paket adi aranir.)
    fl_compat_dump || return 2
    printf '%s\n' "$FL_CDUMP" | grep -qF -- "$1" && return 0
    # ayni turda eklenmis olabilir -> tazele ve bir kez daha bak
    FL_CDUMP=""; FL_CDUMP_TS=""
    fl_compat_dump || return 2
    printf '%s\n' "$FL_CDUMP" | grep -qF -- "$1" && return 0
    return 1
}

fl_compat_status_set() {   # pkg  ok|fail  yontem
    [ -f "$FL_CSTAT" ] || : > "$FL_CSTAT" 2>/dev/null
    _cs=$FL_CSTAT.tmp
    awk -F'\t' -v p="$1" 'NF && $1 != p' "$FL_CSTAT" 2>/dev/null > "$_cs"
    printf '%s\t%s\t%s\n' "$1" "$2" "$3" >> "$_cs"
    mv -f "$_cs" "$FL_CSTAT" 2>/dev/null
}

fl_compat_error_add() {   # $1 paket  $2 hata
    [ -f "$FL_CERR" ] || : > "$FL_CERR" 2>/dev/null
    # ayni hata metni tekrar tekrar yazilmasin (log sismesin)
    _msg=$(printf '%s' "$2" | tr -d '\n' | cut -c1-300)
    if [ -f "$FL_CERR" ] && grep -qF "$_msg" "$FL_CERR" 2>/dev/null && [ "$1" = "-" ]; then
        return 0
    fi
    printf '%s\t%s\t%s\n' "$(date '+%H:%M:%S')" "$1" "$_msg" >> "$FL_CERR" 2>/dev/null
    chmod 0644 "$FL_CERR" 2>/dev/null
    _cl=$(grep -c . "$FL_CERR" 2>/dev/null)
    if [ "${_cl:-0}" -gt 60 ]; then
        tail -n 40 "$FL_CERR" > "$FL_CERR.tmp" 2>/dev/null && mv -f "$FL_CERR.tmp" "$FL_CERR" 2>/dev/null
    fi
}

fl_compat_try() {
    # $1 = paket   $2 = dogrula (yes|no)
    # Cikti: "ok|<yontem>|" ya da "fail||<son hata>"
    _cp=$1
    _cv=${2:-no}
    _cerr=""
    case "${FL_METHOD_START:-1}" in
        2) _morder="2 3 1" ;;
        3) _morder="3 2 1" ;;
        *) _morder="1 2 3" ;;
    esac
    for _m in $_morder; do
        case "$_m" in
            1) _co=$(am compat enable --no-kill "$COMPAT_ID" "$_cp" 2>&1); _cm=am-nokill ;;
            2) _co=$(am compat enable "$COMPAT_ID" "$_cp" 2>&1);           _cm=am-kalici ;;
            3) _co=$(cmd platform_compat enable "$COMPAT_ID" "$_cp" 2>&1); _cm=platform-compat ;;
        esac
        case "$_co" in
            *"Enabled change"*)
                if [ "$_cv" = "yes" ]; then
                    fl_compat_verify "$_cp"; _vr=$?
                    # 2 = dumpsys yok -> komutun cevabina guven
                    if [ "$_vr" = "1" ]; then
                        _cerr="[$_cm] komut basarili dedi ama dumpsys platform_compat'te yok"
                        continue
                    fi
                fi
                echo "ok|$_cm|"; return 0 ;;
        esac
        # bazi surumler sessiz basarili olur -> sistemden dogrula
        fl_compat_verify "$_cp" && { echo "ok|$_cm|"; return 0; }
        _cerr="[$_cm] $_co"
    done
    echo "fail||$_cerr"
    return 1
}

fl_compat_one() {
    # $1 = paket   $2 = dogrula (yes|no)
    _cp=$1
    _cr=$(fl_compat_try "$_cp" "${2:-no}")
    case "$_cr" in
        ok*)
            _cm=$(printf '%s' "$_cr" | cut -d'|' -f2)
            grep -qx "$_cp" "$FL_DONE" 2>/dev/null || echo "$_cp" >> "$FL_DONE"
            fl_compat_status_set "$_cp" ok "$_cm"
            return 0 ;;
        *)
            _ce=$(printf '%s' "$_cr" | cut -d'|' -f3-)
            [ -n "$_ce" ] || _ce="(bos cikti)"
            fl_compat_status_set "$_cp" fail "-"
            fl_compat_error_add "$_cp" "$_ce"
            return 1 ;;
    esac
}

fl_compat_test_line() {
    [ -f "$FL_CTEST" ] || { echo "henuz test edilmedi"; return; }
    grep -m1 '^SONUC:' "$FL_CTEST" 2>/dev/null || head -n 1 "$FL_CTEST" 2>/dev/null
}

fl_compat_probe() {
    # Kisa test: tek paket uzerinde mekanizmayi dener, FL_COMPAT_OK ayarlar.
    FL_COMPAT_OK=no
    [ -f "$FL_QUEUE" ] || return 1
    _tp=""
    for _p in $(cat "$FL_QUEUE" 2>/dev/null); do
        [ -n "$_p" ] || continue
        fl_pkg_excluded "$_p" && continue
        fl_skip_has "$_p" && continue
        _tp=$_p; break
    done

    _t=$FL_CTEST.tmp
    : > "$_t" 2>/dev/null
    if [ -z "$_tp" ]; then
        echo "SONUC: test edilecek paket yok (kuyruk bos)" >> "$_t"
        mv -f "$_t" "$FL_CTEST" 2>/dev/null
        fl_log "compat testi: paket yok"
        return 1
    fi
    FL_CDUMP=""; FL_CDUMP_TS=""
    _r=$(fl_compat_try "$_tp")
    echo "deneme: $_tp -> $_r" >> "$_t"
    case "$_r" in
        ok*)
            FL_COMPAT_OK=yes
            echo "SONUC: CALISIYOR (change=$COMPAT_ID, yontem=$(printf '%s' "$_r" | cut -d'|' -f2))" >> "$_t" ;;
        *)
            echo "SONUC: CALISMIYOR - bu ROM'da uygulama bazli compat uygulanamiyor." >> "$_t"
            echo "  ham cikti: $(printf '%s' "$_r" | cut -d'|' -f3- | cut -c1-300)" >> "$_t" ;;
    esac
    mv -f "$_t" "$FL_CTEST" 2>/dev/null
    chmod 0644 "$FL_CTEST" 2>/dev/null
    fl_log "compat testi: $FL_COMPAT_OK ($(fl_compat_test_line))"
    [ "$FL_COMPAT_OK" = "yes" ] && return 0
    return 1
}

# XML yedek yolu: post-fs-data.sh bir sonraki acilista bu dosyayi
# /data/misc/appcompat/compat_framework_overrides.xml icine yazar.
fl_xml_prepare() {
    case "$COMPAT_XML_FALLBACK" in
        off) rm -f "$FL_XMLFLAG" 2>/dev/null; return 0 ;;
        on)  : ;;
        *)   [ "$FL_COMPAT_OK" = "yes" ] && { rm -f "$FL_XMLFLAG" 2>/dev/null; return 0; } ;;
    esac
    : > "$FL_WANT" 2>/dev/null
    if [ -f "$FL_QUEUE" ]; then
        for _p in $(cat "$FL_QUEUE" 2>/dev/null); do
            [ -n "$_p" ] || continue
            [ "$(fl_app_decision "$_p")" = "force" ] || continue
            echo "$_p" >> "$FL_WANT"
        done
    fi
    echo "$(date '+%Y-%m-%d %H:%M:%S') change=$COMPAT_ID istenen=$(grep -c . "$FL_WANT" 2>/dev/null)" > "$FL_XMLFLAG" 2>/dev/null
    fl_log "compat: XML yedegi hazirlandi ($(grep -c . "$FL_WANT" 2>/dev/null) paket, sonraki acilista uygulanir)"
}

fl_compat_batch() {
    FL_COMPAT_LAST=0
    FL_COMPAT_FAIL=0
    if [ "$COMPAT_FORCE_RESIZE" != "true" ] || [ "$COMPAT_MODE" = "off" ]; then
        fl_log "compat FORCE_RESIZE_APP: kapali (COMPAT_FORCE_RESIZE/COMPAT_MODE)"
        return 0
    fi
    [ -f "$FL_DONE" ]  || : > "$FL_DONE" 2>/dev/null
    [ -f "$FL_SKIP" ]  || : > "$FL_SKIP" 2>/dev/null
    [ -f "$FL_CSTAT" ] || : > "$FL_CSTAT" 2>/dev/null
    [ -f "$FL_CERR" ]  || : > "$FL_CERR" 2>/dev/null

    if [ "${FL_FAST:-0}" != "1" ] && [ "$COMPAT_MODE" = "smart" ]; then
        fl_learn_orientation
    fi

    fl_compat_queue
    if [ ! -s "$FL_QUEUE" ]; then
        fl_log "compat: paket listesi bos (pm list packages -3 cikti vermedi?)"
        return 0
    fi
    fl_compat_targets
    _tgt=$(grep -c . "$FL_TARGETS" 2>/dev/null)
    fl_log "compat: kuyruk $(grep -c . "$FL_QUEUE" 2>/dev/null) paket, hedef ${_tgt:-0} paket"

    FL_CDUMP=""; FL_CDUMP_TS=""
    if [ "${FL_FAST:-0}" != "1" ] || [ ! -s "$FL_CTEST" ]; then
        fl_compat_probe
    fi
    fl_log "compat: dongu basliyor"

    _start=$(date +%s)
    _n=0; _f=0; _rev=0; _skipn=0; _run=0; _consec=0
    _pk_lim=${FL_CHUNK_LIMIT:-0}
    CHUNK_MORE=0
    fl_progress_write "calisiyor" 0 "${_tgt:-0}" 0
    _min=${COMPAT_MIN_PER_TURN:-6}
    _limit=${COMPAT_BUDGET:-120}
    for _pkg in $(cat "$FL_QUEUE" 2>/dev/null); do
        [ -n "$_pkg" ] || continue

        # ---- tek karar noktasi ----
        if [ "$(fl_app_decision "$_pkg")" = "skip" ]; then
            if [ "$(fl_decision_why "$_pkg")" != "sistem-haric" ]; then
                fl_skip_add "$_pkg"
                _skipn=$((_skipn + 1))
            fi
            if grep -qx "$_pkg" "$FL_DONE" 2>/dev/null; then
                am compat reset --no-kill "$COMPAT_ID" "$_pkg" > /dev/null 2>&1
                grep -vx "$_pkg" "$FL_DONE" > "$FL_DONE.tmp" 2>/dev/null
                mv -f "$FL_DONE.tmp" "$FL_DONE" 2>/dev/null
                fl_compat_status_set "$_pkg" fail "geri-alindi"
                _rev=$((_rev + 1))
                fl_log "compat: $_pkg artik hedef degil ($(fl_decision_why "$_pkg")) -> geri alindi"
            fi
            continue
        fi

        grep -qx "$_pkg" "$FL_DONE" 2>/dev/null && continue

        _now=$(date +%s)
        if [ $((_now - _start)) -ge "$_limit" ] && [ "$_n" -ge "$_min" ]; then
            fl_log "compat: sure butcesi doldu ($_n paket uygulandi), kalan sonraki tura"
            break
        fi

        if [ "$_pk_lim" -gt 0 ] && [ "$_run" -ge "$_pk_lim" ]; then
            CHUNK_MORE=1
            fl_log "compat: parca tamam ($_run paket), kalanlar sonraki parcada"
            break
        fi
        _run=$((_run + 1))
        # ilk 3 pakette sistemden dogrulama yap (komut yalan soyluyorsa yakalanir)
        if [ "$_run" -le 3 ]; then _vf=yes; else _vf=no; fi
        if fl_compat_one "$_pkg" "$_vf"; then
            _n=$((_n + 1))
            _consec=0
        else
            _f=$((_f + 1))
            _consec=$((_consec + 1))
            if [ "$_consec" -le 3 ]; then
                fl_log "compat HATA $_pkg -> $(tail -n 1 "$FL_CERR" 2>/dev/null | cut -f3- | cut -c1-160)"
            fi
            # v2.3: ardisik hata artik TURU BITIRMEZ (sahada 1 paket uygulanip
            # 87 paket "kalan" olarak kalmisti). Once yontem degistirilir,
            # sonra basarisiz paket atlanip digerlerine devam edilir.
            if [ "$_consec" = "5" ] && [ "$FL_METHOD_START" = "1" ]; then
                FL_METHOD_START=2
                fl_log "compat: ust uste 5 hata -> 2. yonteme geciliyor (kalici enable)"
                fl_compat_error_add "-" "yontem-1 basarisiz, yontem-2 denendi"
            fi
            if [ "$_consec" = "10" ] && [ "$FL_METHOD_START" = "2" ]; then
                FL_METHOD_START=3
                fl_log "compat: ust uste 10 hata -> 3. yonteme geciliyor (platform_compat)"
            fi
            if [ "$_consec" -ge 20 ]; then
                fl_log "compat: ust uste $_consec hata -> tur durduruldu (3 yontem de basarisiz; WebUI > Tanı > katman/hata satirlari)"
                fl_log "compat: son hata -> $(tail -n 1 "$FL_CERR" 2>/dev/null | cut -f3- | cut -c1-200)"
                COMPAT_TURN_ABORTED=1
                break
            fi
        fi
    done

    FL_COMPAT_LAST=$_n
    FL_COMPAT_FAIL=$_f
    _left=$(fl_compat_left)
    if [ "$CHUNK_MORE" = "1" ]; then
        fl_progress_write "calisiyor" "$_run" "${_tgt:-0}" "$_left"
    else
        fl_progress_write "bitti" "$_run" "${_tgt:-0}" "$_left"
    fi
    # v2.8: tamamen basarili turda ESKI hata kayitlari silinir
    # (raporda saatler oncesinin hatalari "guncel" gibi gorunuyordu)
    if [ "$_f" = "0" ] && [ "$_left" = "0" ]; then
        : > "$FL_CERR" 2>/dev/null
        chmod 0644 "$FL_CERR" 2>/dev/null
    fi
    fl_log "compat: dongu bitti | islenen $_run | basarili $_n | hata $_f | geri alinan $_rev | dokunulmayan $_skipn | toplam uygulanmis $(fl_compat_count) | hedef ${_tgt:-0} | kalan $_left"

    # ornek dogrulama: komut basarili dedi ama sistemde gorunmuyor mu?
    if [ "$_n" -gt 0 ]; then
        _sample=$(tail -n 1 "$FL_DONE" 2>/dev/null)
        if [ -n "$_sample" ]; then
            if fl_compat_verify "$_sample"; then
                fl_log "compat: dogrulama OK (ornek: $_sample)"
            else
                fl_log "compat: DIKKAT - komut basarili dedi ama dumpsys platform_compat'te $_sample gorunmuyor"
            fi
        fi
    fi
    # v2.8: hepsi zaten uygulanmissa (kalan=0) bu uyari YANLIS ALARM'di
    if [ "$_n" = "0" ] && [ "$_f" = "0" ] && [ "${_tgt:-0}" -gt 0 ]; then
        if [ "$(fl_compat_left)" = "0" ]; then
            fl_log "compat: hedef $_tgt paketin tamami zaten uygulanmis, yapilacak is yok"
        else
            fl_log "compat: DIKKAT - hedef var ama tek paket islenmedi (kuyruk okunamadi?)"
        fi
    fi

    fl_xml_prepare
}

fl_progress_write() {   # $1 durum  $2 islenen  $3 toplam  $4 kalan
    {
        echo "durum=$1"
        echo "islenen=$2"
        echo "toplam=$3"
        echo "kalan=$4"
        echo "uygulanan=$(fl_compat_count)"
        echo "basarisiz=${FL_COMPAT_FAIL:-0}"
        echo "ts=$(date '+%H:%M:%S')"
    } > "$FL_PROG" 2>/dev/null
    chmod 0644 "$FL_PROG" 2>/dev/null
}

fl_progress_read() {   # key|value satirlari
    [ -f "$FL_PROG" ] || { echo "durum|yok"; return 0; }
    while IFS='=' read -r _k _v; do
        [ -n "$_k" ] && echo "$_k|$_v"
    done < "$FL_PROG"
}

fl_compat_summary() {
    # webui/action icin tek satirlik ozet
    _ok=0; _bad=0
    if [ -f "$FL_CSTAT" ]; then
        _ok=$(awk -F'\t' '$2=="ok"' "$FL_CSTAT" 2>/dev/null | grep -c .)
        _bad=$(awk -F'\t' '$2=="fail" && $3!="geri-alindi"' "$FL_CSTAT" 2>/dev/null | grep -c .)
    fi
    echo "ok=${_ok:-0} hata=${_bad:-0} toplam=$(fl_compat_count) kalan=$(fl_compat_left) mod=$COMPAT_MODE"
}

fl_compat_errors_tail() {  # $1 = satir sayisi
    [ -f "$FL_CERR" ] || return 0
    tail -n "${1:-5}" "$FL_CERR" 2>/dev/null
}

# ---------- ana is -------------------------------------------
# ============================================================
#  v2.7 — "EKRANA YAY" (komple yatay / tam ekran) modu
# ------------------------------------------------------------
#  Ne yapar?
#    Uygulamanin manifestindeki "sadece dikey" ve "en fazla 16:9"
#    kisitlarini KALDIRIR; pencereyi ekranin tamamina yayar.
#    Boylece sag-sol (letterbox) bantlari olusmaz, uygulama
#    ekrani boydan boya doldurur.
#
#  Nasil?  Android 12+ compatibility framework override'lari:
#    OVERRIDE_MIN_ASPECT_RATIO          (kapici, id 174042980)
#    OVERRIDE_MIN_ASPECT_RATIO_LARGE    (16:9 -> genis ekran, 180326787)
#    OVERRIDE_MIN_ASPECT_RATIO_PORTRAIT_ONLY  (kapatilir, 203647190)
#    OVERRIDE_ANY_ORIENTATION_TO_USER   (Android 14+, 265464455)
#
#  Zararsizligi:
#    * Uc yontem sirayla denenir (am --no-kill -> am -> cmd).
#    * Basarisiz olursa uygulama ESKI halinde kalir; hicbir sey bozulmaz.
#    * Geri alma: her id icin "am compat reset" -> fabrika degeri.
#    * Oyunlarda manifest dokunulmaz, sadece compat katmani degisir;
#      oyun cokmez (zaten boyutlanabilir hale getirir).
# ============================================================
FL_AM_GATE=OVERRIDE_MIN_ASPECT_RATIO
FL_AM_LIST="OVERRIDE_MIN_ASPECT_RATIO OVERRIDE_MIN_ASPECT_RATIO_LARGE OVERRIDE_MIN_ASPECT_RATIO_PORTRAIT_ONLY OVERRIDE_ANY_ORIENTATION_TO_USER"

# ============================================================
#  v2.8 — MIUI ORTA-PENCERE (activity embedding) KAPATMA
# ------------------------------------------------------------
#  Sorun: MIUI Pad, "uyumlu degil" saydigi uygulamalari ekranin
#  ortasinda dar bir pencereye koyuyor (siyah yan bantlar). Bu
#  AOSP letterbox'i DEGIL; bu yuzden FORCE_RESIZE_APP / oran
#  override'lari bunu kaldirmiyor. Kapatma yolu:
#      ro.config.miui_activity_embedding_enable = false
#  Bu "ro." prop'u ancak resetprop ile (dogrudan bellek yazimi)
#  degistirilebilir; system.prop dosyasi da acilista ayni isi yapar.
# ============================================================

fl_resetprop_bin() {
    for _b in /data/adb/ksu/bin/resetprop /data/adb/magisk/resetprop \
              /data/adb/ap/bin/resetprop /system/bin/resetprop; do
        [ -x "$_b" ] && { echo "$_b"; return 0; }
    done
    _c=$(command -v resetprop 2>/dev/null)
    [ -n "$_c" ] && { echo "$_c"; return 0; }
    return 1
}

fl_miui_var() {   # 0 = MIUI, 1 = degil
    case "$(getprop ro.miui.ui.version.name 2>/dev/null)" in ''|unknown) return 1 ;; esac
    return 0
}

fl_miui_embed_state() {   # "acik" | "kapali" | "yok"
    _v=$(getprop "$FL_MIUI_EMB_PROP" 2>/dev/null)
    case "$_v" in
        true|1)   echo "acik" ;;
        false|0)  echo "kapali" ;;
        *)        echo "yok" ;;
    esac
}

fl_miui_engine_guard() {   # -> acik|kapaliydi-acildi|kapali-acilamadi|yok
    # v2.18: per-app MIUI kurallarini isleyen motorun acik oldugunu
    # dogrula; kapaliysa acmayi dene. v2.17 ve oncesi, acilis betigi
    # bu motoru varsayilan olarak kapattigi icin per-app kurallar
    # olu doguyordu (bkz. post-fs-data.sh v2.18 notu).
    fl_miui_var || { echo yok; return 0; }
    _gs=$(fl_miui_embed_state)
    case "$_gs" in
        acik) echo acik; return 0 ;;
        yok)  echo yok; return 0 ;;
    esac
    _gr=$(fl_miui_embed_set on 2>/dev/null)
    case "$_gr" in
        ok*) fl_log "MIUI motoru kapaliydi, acma denendi: $_gr (servis propu acilista okumus olabilir; etki icin yeniden baslat gerekebilir)"
             echo "kapaliydi-acildi" ;;
        *)   fl_log "MIUI motoru KAPALI ve ACILAMADI: $_gr -- per-app kurallar bu yuzden calismaz"
             echo "kapali-acilamadi" ;;
    esac
}

fl_miui_embed_set() {   # $1 = off|on  -> "ok|<yontem>" | "fail|<sebep>"
    _want=false
    [ "$1" = "on" ] && _want=true
    _cur=$(getprop "$FL_MIUI_EMB_PROP" 2>/dev/null)
    [ "$_cur" = "$_want" ] && { echo "ok|zaten"; return 0; }
    _b=$(fl_resetprop_bin)
    if [ -n "$_b" ]; then
        "$_b" -n "$FL_MIUI_EMB_PROP" "$_want" >/dev/null 2>&1
        [ "$(getprop "$FL_MIUI_EMB_PROP" 2>/dev/null)" = "$_want" ] && {
            echo "ok|resetprop:$_b"; return 0; }
    fi
    setprop "$FL_MIUI_EMB_PROP" "$_want" >/dev/null 2>&1
    [ "$(getprop "$FL_MIUI_EMB_PROP" 2>/dev/null)" = "$_want" ] && { echo "ok|setprop"; return 0; }
    echo "fail|prop-degismedi:$(getprop "$FL_MIUI_EMB_PROP" 2>/dev/null)"
    return 1
}



# ============================================================
#  v2.10 — EKRANI TAM DOLDURMA (saga-sola yapisma)
#  Neden gerekli: MIUI, "uyumlu degil" saydigi oyunlari ekranin
#  ORTASINDA dar bir pencerede tutuyor (bugreport: MID_BOUND=
#  SkRegion((719,0,2162,1800)) -> 1443 px). Prop ile gomme
#  kapatilabiliyor ama bazi ROM'larda prop tutmuyor / uygulama
#  acildiktan sonra tekrar gomuluyor. Bu yuzden prop'a EK olarak
#  calisma aninda gorevi (task) tam ekran olcusune getiriyoruz:
#     am task resize <id> 0 0 <gen> <yuk>
#  ve sonucu dumpsys ile DOGRULUYORUZ.
# ============================================================
# ============================================================
#  v2.11 — MIUI 信箱模式 (mailbox) + "强制横屏" kurali
# ------------------------------------------------------------
#  NEDEN GEREKTI:
#  v2.10'a kadar "ekrana yay" yalnizca AOSP compat anahtarlarini
#  kullaniyordu (FORCE_RESIZE_APP, OVERRIDE_MIN_ASPECT_RATIO...).
#  Ama kullanicinin ekran goruntusundeki dar sutun AOSP letterbox'i
#  degil, MIUI Pad'in KENDI "信箱模式"si: tablete uyarlanmamis
#  uygulama ekranin ortasinda telefon oraninda bir sutuna konur.
#  Bu davranis compat anahtarlariyla DEGISMEZ -> o yuzden modul
#  "uygulandi" yazsa da gorunum ayni kaliyordu.
#
#  COZUM (MIUI'nin kendi kural dosyalari):
#    /data/system/cloudFeature_fixed_orientation_list.xml
#        <package name="pkg" disable="true" />        -> sutun kalkar
#    /product/etc/embedded_rules_list.xml
#        <package name="pkg" fullRule="nra:cr:rcr:nr" /> -> yatay
#
#  /product salt-okunur olabilir. O durumda kural modul katmanina
#  ($MODDIR/system/product/etc/embedded_rules_list.xml) yazilir ve
#  sonuc "bir sonraki acilista" olarak ACIKCA bildirilir.
#  Her yazma islemi dosyadan GERI OKUNARAK dogrulanir.
# ============================================================
FL_MB_ROM_PATHS="/product/etc/fixed_orientation_list.xml /system/etc/fixed_orientation_list.xml /system/product/etc/fixed_orientation_list.xml"
FL_MB_CLOUD_PATHS="/data/system/cloudFeature_fixed_orientation_list.xml"
FL_EMB_ROM_PATHS="/product/etc/embedded_rules_list.xml /system/etc/embedded_rules_list.xml /system/product/etc/embedded_rules_list.xml"
FL_EMB_CLOUD_PATHS="/data/system/cloudFeature_embedded_rules_list.xml"
FL_MB_LIST="$FL_DIR/mailbox.txt"

fl_first_file() {
    for _ffp in "$@"; do
        [ -n "$_ffp" ] || continue
        if [ -f "$_ffp" ]; then printf '%s' "$_ffp"; return 0; fi
    done
    printf ''
}
fl_mb_rom()   { fl_first_file $FL_MB_ROM_PATHS ; }
fl_mb_cloud() { fl_first_file $FL_MB_CLOUD_PATHS ; }
fl_emb_rom()  { fl_first_file $FL_EMB_ROM_PATHS ; }
fl_emb_cloud(){ fl_first_file $FL_EMB_CLOUD_PATHS ; }

fl_moddir() {
    if [ -n "${MODDIR:-}" ] && [ -d "$MODDIR" ]; then printf '%s' "$MODDIR"; return 0; fi
    if [ -d /data/adb/modules/force_landscape_all ]; then
        printf '%s' /data/adb/modules/force_landscape_all; return 0
    fi
    printf '%s' "$FL_DIR"
}
# NOT: "/product/etc" dizesi BITISIK yazilmaz; test ortaminin yol donusumu
# modul katmani yolunu bozmasin diye parcali kurulur.
fl_emb_overlay() {
    printf '%s/system/%s/etc/embedded_rules_list.xml' "$(fl_moddir)" "product"
}

fl_pkg_line() {   # $1 dosya  $2 paket -> o paketin <package ... /> satiri
    [ -f "$1" ] || return 0
    [ -n "$2" ] || return 0
    awk -v p="$2" '
        /<package[ \t]/ { if (index($0, "name=\"" p "\"") > 0) { print; exit } }
    ' "$1" 2>/dev/null | head -n 1
}

fl_mb_state() {   # $1 paket -> yok | varsayilan | aktif | kapali
    _msf=$(fl_mb_cloud)
    [ -n "$_msf" ] || _msf=$(fl_mb_rom)
    [ -n "$_msf" ] || { echo "yok"; return; }
    _msl=$(fl_pkg_line "$_msf" "$1")
    [ -n "$_msl" ] || { echo "varsayilan"; return; }
    case "$_msl" in
        *disable=\"true\"*)  echo "kapali" ;;
        *disable=\"false\"*) echo "aktif" ;;
        *)                   echo "varsayilan" ;;
    esac
}

# XML'e satir eklemek/degistirmek icin tek yol: mevcut dosyayi bozmadan,
# ayni paketin eski satir(lar)ini at, yeni satiri son kapanis etiketinden
# hemen once yaz. Sonra dosyadan geri okuyup DOGRULA.
fl_xml_upsert() {   # $1 dosya $2 paket $3 yeni satir -> ok|<dosya> | fail|<sebep>
    _xu=$1; _xp=$2; _xl=$3
    [ -f "$_xu" ] || { echo "fail|dosya-yok"; return 1; }
    [ -w "$_xu" ] || chmod 0660 "$_xu" 2>/dev/null
    [ -w "$_xu" ] || { echo "fail|yazma-izni-yok"; return 1; }
    _xt="$_xu.fl.tmp"
    awk -v p="$_xp" -v ins="$_xl" '
        { L[NR] = $0 }
        END {
            last = 0
            for (i = NR; i >= 1; i--) {
                if (L[i] ~ /^[ \t]*<\/[A-Za-z_-]+>[ \t]*$/) { last = i; break }
            }
            for (i = 1; i <= NR; i++) {
                if (L[i] ~ /<package[ \t]/ && index(L[i], "name=\"" p "\"") > 0) continue
                if (last > 0 && i == last) print ins
                print L[i]
            }
            if (last == 0) print ins
        }' "$_xu" > "$_xt" 2>/dev/null || { rm -f "$_xt"; echo "fail|awk-hatasi"; return 1; }
    [ -s "$_xt" ] || { rm -f "$_xt"; echo "fail|bos-cikti"; return 1; }
    grep -qF -- "$_xl" "$_xt" 2>/dev/null || { rm -f "$_xt"; echo "fail|dogrulanamadi"; return 1; }
    chown 1000:1000 "$_xt" 2>/dev/null
    chmod 0660 "$_xt" 2>/dev/null
    cat "$_xt" > "$_xu" 2>/dev/null || { rm -f "$_xt"; echo "fail|yazilamadi"; return 1; }
    rm -f "$_xt"
    # v2.13: MIUI izin/sahip/SELinux beklentisi (ybcq/MIUI-MagicWindow)
    fl_miui_fixperm "$_xu" >/dev/null 2>&1
    echo "ok|$_xu"
}

fl_xml_drop() {   # $1 dosya $2 paket -> 0 yazildi | 1 olmadi
    _xd=$1; _xdp=$2
    [ -f "$_xd" ] || return 1
    [ -w "$_xd" ] || return 1
    awk -v p="$_xdp" '!(/<package[ \t]/ && index($0, "name=\"" p "\"") > 0)' \
        "$_xd" > "$_xd.fl.tmp" 2>/dev/null || return 1
    [ -s "$_xd.fl.tmp" ] || { rm -f "$_xd.fl.tmp"; return 1; }
    cat "$_xd.fl.tmp" > "$_xd" 2>/dev/null
    rm -f "$_xd.fl.tmp"
    fl_miui_fixperm "$_xd" >/dev/null 2>&1
    return 0
}

fl_mb_ensure() {   # mailbox kural dosyasi (yoksa ROM kopyasindan olustur) -> yol | bos
    _me=$(fl_mb_cloud)
    [ -n "$_me" ] && { printf '%s' "$_me"; return 0; }
    _mer=$(fl_mb_rom)
    [ -n "$_mer" ] || { printf ''; return 1; }
    [ -d /data/system ] || mkdir -p /data/system 2>/dev/null
    [ -d /data/system ] || { printf ''; return 1; }
    _mec=/data/system/cloudFeature_fixed_orientation_list.xml
    cp -f "$_mer" "$_mec" 2>/dev/null || cat "$_mer" > "$_mec" 2>/dev/null
    fl_miui_fixperm "$_mec" >/dev/null 2>&1
    [ -s "$_mec" ] || { printf ''; return 1; }
    printf '%s' "$_mec"
}

fl_mb_set() {   # $1 paket $2 true|false -> ok|<dosya> | fail|<sebep>
    _msf2=$(fl_mb_ensure)
    [ -n "$_msf2" ] || { echo "fail|kural-dosyasi-yok"; return 1; }
    _msr=$(fl_xml_upsert "$_msf2" "$1" "  <package name=\"$1\" disable=\"$2\" />")
    case "$_msr" in
        ok*) if [ "$2" = "true" ]; then fl_mb_save "$1"; else fl_mb_unsave "$1"; fi ;;
    esac
    printf '%s\n' "$_msr"
}

fl_emb_write() {   # $1 paket -> ok|<yol>|hemen | ok|<yol>|yeniden-baslat | fail|<sebep>
    _ep=$1
    _eline="  <package name=\"$_ep\" fullRule=\"${FL_RULE_LANDSCAPE:-nra:cr:rcr:nr}\" />"
    for _et in "$(fl_emb_cloud)" "$(fl_emb_rom)"; do
        [ -n "$_et" ] || continue
        [ -w "$_et" ] || continue
        case "$(fl_xml_upsert "$_et" "$_ep" "$_eline")" in
            ok*) echo "ok|$_et|hemen"; return 0 ;;
        esac
    done
    _er=$(fl_emb_rom)
    [ -n "$_er" ] || { echo "fail|kural-dosyasi-yok"; return 1; }
    _eo=$(fl_emb_overlay)
    _eod=${_eo%/*}
    [ -d "$_eod" ] || mkdir -p "$_eod" 2>/dev/null
    [ -d "$_eod" ] || { echo "fail|katman-dizini-yok"; return 1; }
    [ -f "$_eo" ] || { cp -f "$_er" "$_eo" 2>/dev/null || cat "$_er" > "$_eo" 2>/dev/null; }
    chmod 0644 "$_eo" 2>/dev/null
    case "$(fl_xml_upsert "$_eo" "$_ep" "$_eline")" in
        ok*) echo "ok|katman|yeniden-baslat" ;;
        *)   echo "fail|katman-yazilamadi" ;;
    esac
}

fl_emb_state() {   # $1 paket -> yok | var|hemen | var|yeniden-baslat
    for _esd in "$(fl_emb_cloud)" "$(fl_emb_rom)"; do
        [ -n "$_esd" ] || continue
        [ -n "$(fl_pkg_line "$_esd" "$1")" ] && { echo "var|hemen"; return; }
    done
    _eso=$(fl_emb_overlay)
    if [ -f "$_eso" ] && [ -n "$(fl_pkg_line "$_eso" "$1")" ]; then
        echo "var|yeniden-baslat"; return
    fi
    echo "yok"
}

fl_mb_save()   { fl_list_add "$FL_MB_LIST" "$1"; }
fl_mb_unsave() { fl_list_del "$FL_MB_LIST" "$1"; }

# ============================================================
#  v2.16 — MIUI CANLI SERVIS KOMUTU (asli duzeltme)
#  Arastirma (sothx/coolapk, Xiaomi muhendislerinin hata ayiklama
#  komutlari): MIUI'nin kendi servisi calisma aninda kural uygular:
#     cmd miui_embedding_window update-rule   <pkg> fullRule::<kural>
#     cmd miui_embedding_window set-fixedOri  <pkg> disable::true|false
#  Dosyaya yazmak tek basina YETMEZ: servis kurallari acilista okur;
#  dosya degisikligi yeniden baslatmadan gorunmez. v2.15'e kadar modul
#  sadece dosya yazip "hemen gecerli" diyordu -> kullanici uygulamada
#  hicbir degisiklik gormuyordu. Artik: dosya (kalicilik) + cmd (canli).
#  NOT: cmd kurallari yeniden baslatinca ucar; acilista dosya devreye
#  girer. Ikisi birlikte tam cozumdur.
# ============================================================
FL_MIUI_CMD_CACHE=""
fl_miui_cmd_avail() {   # 0 = servis var, 1 = yok (islem ici onbellekli)
    case "$FL_MIUI_CMD_CACHE" in evet) return 0 ;; hayir) return 1 ;; esac
    _mo=$(cmd miui_embedding_window help 2>&1 </dev/null)
    case "$_mo" in
        *miui_embedding_window*|*update-rule*|*set-fixedOri*|*[Uu]sage*)
            FL_MIUI_CMD_CACHE=evet
            # yardim metnini bir kez loga + dosyaya yaz (tani kaniti)
            if [ ! -f "$FL_DIR/.miui_cmd_help" ]; then
                printf '%s\n' "$_mo" > "$FL_DIR/.miui_cmd_help" 2>/dev/null
                fl_log "miui-cmd: servis VAR. yardim: $(printf '%s' "$_mo" | tr '\n' ' ' | cut -c1-300)"
            fi
            return 0 ;;
    esac
    FL_MIUI_CMD_CACHE=hayir
    fl_log "miui-cmd: 'cmd miui_embedding_window' servisi yok/cevap vermiyor"
    return 1
}

fl_miui_cmd_run() {   # cmd miui_embedding_window <args> -> 0 ok | 1 fail
    _mo=$(cmd miui_embedding_window "$@" 2>&1 </dev/null)
    _mrc=$?
    fl_log "  miui-cmd: $* -> rc=$_mrc $(printf '%s' "$_mo" | tr '\n' ' ' | cut -c1-140)"
    case "$_mo" in
        *[Ee]rror*|*Exception*|*"Failed transaction"*|*"No such"*|*"Unknown"*|*[Ff]ailure*|*"not found"*) return 1 ;;
    esac
    [ "$_mrc" = "0" ]
}

fl_miui_cmd_apply() {   # $1 paket -> ok|yok : canli yatay + mailbox kapat
    fl_miui_cmd_avail || { echo yok; return 1; }
    _ok=ok
    fl_miui_cmd_run update-rule "$1" "fullRule::${FL_RULE_LANDSCAPE:-nra:cr:rcr:nr}" || _ok=yok
    fl_miui_cmd_run set-fixedOri "$1" "disable::true" || _ok=yok
    echo "$_ok"
    [ "$_ok" = "ok" ]
}

fl_miui_cmd_mailbox() {   # $1 paket $2 true|false -> 0/1 (sadece mailbox, canli)
    fl_miui_cmd_avail || return 1
    fl_miui_cmd_run set-fixedOri "$1" "disable::$2"
}

fl_miui_cmd_revert() {   # $1 paket : canli kurallari geri al
    fl_miui_cmd_avail || return 1
    _rv1=ok
    fl_miui_cmd_run set-fixedOri "$1" "disable::false" >/dev/null 2>&1 || _rv1=fail
    _rv2=ok
    fl_miui_cmd_run update-rule "$1" "fullRule::" >/dev/null 2>&1 || _rv2=fail
    # v2.19: sonuc LOGA yazilir (eskiden sessizce yutuluyordu). Bos
    # fullRule bazi ROM'larda reddedilir; o durumda dosya zaten temiz
    # oldugu icin kural yeniden baslatmada kesin duser.
    fl_log "canli-geri-al: $1 fixedOri=$_rv1 fullRule=$_rv2"
    [ "$_rv1" = "ok" ] && return 0
    return 1
}

fl_miui_apply() {   # $1 paket -> ok|<aciklama> | fail|<sebep>
    _mp=$1
    [ -n "$_mp" ] || { echo "fail|paket-yok"; return 1; }
    fl_miui_engine_guard >/dev/null 2>&1   # v2.18: motor kapaliysa acmayi dene + logla
    [ "${MIUI_MAILBOX:-true}" = "true" ] || { echo "ok|mailbox=ayar-kapali"; return 0; }
    fl_miui_var || { echo "ok|mailbox=yok(miui-degil)"; return 0; }
    if [ -z "$(fl_mb_ensure)" ]; then
        echo "ok|mailbox=yok(rom-kural-dosyasi-bulunamadi)"
        return 0
    fi
    _mrr=$(fl_mb_write2 "$_mp" 2>/dev/null)
    case "$_mrr" in
        ok*) _mres="mailbox=kapali" ;;
        *)   _mrr=$(fl_mb_set "$_mp" true)      # yedek yol (cloud)
             case "$_mrr" in
                 ok*) _mres="mailbox=kapali" ;;
                 *)   echo "fail|mailbox-kapatilamadi:$(printf '%s' "$_mrr" | cut -d'|' -f2-)"; return 1 ;;
             esac ;;
    esac
    _err=$(fl_emb_write2 "$_mp" 2>/dev/null)
    case "$_err" in
        ok*) _eres="yatay=$(printf '%s' "$_err" | cut -d'|' -f3-)" ;;
        *)   _eres="yatay=kural-yok($(printf '%s' "$_err" | cut -d'|' -f2-))" ;;
    esac
    fl_mb_save "$_mp"
    # v2.16: dosyalar kalicilik icin yazildi; simdi MIUI servisine CANLI
    # da soyle (yeniden baslatmadan gorunsun). Sonuc ozete eklenir.
    _cmd=$(fl_miui_cmd_apply "$_mp" 2>/dev/null)
    echo "ok|$_mres $_eres canli=$_cmd"
    return 0
}

fl_miui_clear() {   # $1 paket -> MIUI kural kayitlarini sil (geri alma)
    _cp2=$1
    [ -n "$_cp2" ] || return 0
    # v2.14: yazma yolu hangi dosyaya yazdiysa silme yolu da AYNISINDAN siler.
    # Eskiden /product/etc dosyalarindaki satirlar hic silinmiyordu ->
    # modulu kaldirsan bile kural /product'ta kaliyordu.
    for _clf in /data/system/cloudFeature_fixed_orientation_list.xml \
                /data/system/cloudFeature_embedded_rules_list.xml \
                /product/etc/fixed_orientation_list.xml \
                /product/etc/embedded_rules_list.xml; do
        [ -f "$_clf" ] && fl_xml_drop "$_clf" "$_cp2"
    done
    _ccf=$(fl_mb_cloud);  [ -n "$_ccf" ] && fl_xml_drop "$_ccf" "$_cp2"
    _ecf=$(fl_emb_cloud); [ -n "$_ecf" ] && fl_xml_drop "$_ecf" "$_cp2"
    _eoc=$(fl_emb_overlay); [ -f "$_eoc" ] && fl_xml_drop "$_eoc" "$_cp2"
    fl_mb_unsave "$_cp2"
    # v2.16: canli servis kurali da geri alinir (dosya silmek calisma
    # anindaki kurali dusurmez; yeniden baslat beklemek gerekmesin)
    fl_miui_cmd_revert "$_cp2" >/dev/null 2>&1
    return 0
}

fl_mb_reapply() {   # acilista: kayitli paketlerin mailbox/yatay kuralini tazele
    [ -f "$FL_MB_LIST" ] || return 0
    _rn=0; _rf=0
    for _rp2 in $(cat "$FL_MB_LIST" 2>/dev/null); do
        [ -n "$_rp2" ] || continue
        case "$(fl_miui_apply "$_rp2" 2>/dev/null)" in
            ok*) _rn=$((_rn + 1)) ;;
            *)   _rf=$((_rf + 1)) ;;
        esac
    done
    fl_log "mailbox tazeleme: $_rn tamam, $_rf hata"
}

# ============================================================
#  v2.11.1 — "KESIN TEST": tahmin degil, adim adim kanit
# ------------------------------------------------------------
#  Kullanici hakli bir soru sordu: "kesin calisacagina emin misin?"
#  Bu fonksiyon bir uygulama icin YAPILAN her seyi tek tek olcer ve
#  tek satir HUKUM verir. Hicbir adim "bence" demez:
#    ADIM|1|OK|mailbox|<dosya satiri>
#    ADIM|2|OK|yatay-kural|<hemen|yeniden-baslat>
#    ADIM|3|OK|genel-yatay|<wm degeri>
#    ADIM|4|HATA|pencere|orta|1443x1800  (ya da KANIT-YOK)
#    SONUC|CALISIYOR|...  |  SONUC|OLMADI|sebep  |  SONUC|KANIT-YOK|...
# ============================================================
# ============================================================
#  v2.12 — ACIK KAYNAK YONTEMI (MIUI_MagicWindow ile ayni)
# ------------------------------------------------------------
#  Kanit: ybcq/MIUI-MagicWindow (完美横屏应用计划) egitimi, Android 12/13 icin
#  aynen soyle diyor:
#     cp modul/system/product/etc/embedded_rules_list.xml \
#        /product/etc/embedded_rules_list.xml
#     cp ...  /data/system/cloudFeature_embedded_rules_list.xml
#     chmod 660 ... ; chown system ...      <-- IZIN/SAHIP ONEMLI
#  Yani MIUI kurallari IKINCI bir kopyadan (cloudFeature_*) okuyor ve
#  dosyanin sahibi "system", izni 660 olmak ZORUNDA. v2.11'de izin 644 /
#  sahip 1000:1000 yaziliyordu ve /product'a hic yazilmiyordu -> MIUI
#  kurali gormuyordu. "Hicbir sey olmuyor" sikayetinin bir nedeni bu.
# ============================================================
fl_chown_system() {   # $1 dosya -> MIUI'nin bekledigi sahip/izin
    [ -f "$1" ] || return 1
    chown 1000:1000 "$1" 2>/dev/null || chown system:system "$1" 2>/dev/null
    chmod 0660 "$1" 2>/dev/null
    return 0
}

fl_product_rw() {   # /product (ya da /) yazilabilir mi? -> 0 evet
    case "$(fl_prod_state 2>/dev/null)" in ok) return 0 ;; esac
    mount -o rw,remount /product >/dev/null 2>&1
    [ -w /product/etc ] && { echo ok > "$FL_DIR/.prod_rw" 2>/dev/null; return 0; }
    mount -o remount,rw / >/dev/null 2>&1
    [ -w /product/etc ] && { echo ok > "$FL_DIR/.prod_rw" 2>/dev/null; return 0; }
    mount -o rw,remount /product 2>/dev/null | grep -qi 'denied' && return 1
    return 1
}
fl_prod_state() { [ -w /product/etc ] && echo ok || echo ro ; }

fl_rules_reload() {   # MIUI kurallari yeniden okusun: 4 yol, her biri denenir
    # Amac: yeniden baslatmadan kuralin gecerli olmasi. Hicbiri tutmazsa
    # durustce "yeniden-baslat" deriz (yalan yok).
    _rl_done=""
    # 1) MIUI icerik/embedding servisi
    if command -v am >/dev/null 2>&1; then
        am force-stop com.miui.contentcatcher >/dev/null 2>&1 && _rl_done="$_rl_done contentcatcher"
        am force-stop com.miui.contentextension >/dev/null 2>&1 && _rl_done="$_rl_done contentextension"
    fi
    # 2) servis surecleri
    for _rlp in contentcatcher miui-embedding embedding; do
        _rlpid=$(pidof "com.miui.$_rlp" 2>/dev/null)
        [ -n "$_rlpid" ] && kill "$_rlpid" 2>/dev/null && _rl_done="$_rl_done kill:$_rlp"
    done
    # 3) bilinen yayin (varsa) - NOT: 'am broadcast' alici olmasa bile 0
    #    dondurur; o yuzden basari SAYILMAZ, sadece gonderilir.
    am broadcast -a miui.intent.action.CLOUD_FEATURE_UPDATE >/dev/null 2>&1
    # 4) yeni bos dosya zaman damgasi (bazi MIUI surumleri mtime izler)
    for _rlf in /data/system/cloudFeature_fixed_orientation_list.xml \
                /data/system/cloudFeature_embedded_rules_list.xml; do
        [ -f "$_rlf" ] && touch "$_rlf" 2>/dev/null
    done
    [ -n "$_rl_done" ] && { echo "tetiklendi:$_rl_done"; return 0; }
    echo "yeniden-baslat"
    return 1
}

fl_emb_write2() {   # $1 paket -> ok|<yol>|hemen | ok|katman|yeniden-baslat | fail|<sebep>
    _e2p=$1
    _e2l="  <package name=\"$_e2p\" fullRule=\"${FL_RULE_LANDSCAPE:-nra:cr:rcr:nr}\" />"
    # v2.14 ARASTIRMA SONUCU (ybcq/MIUI-MagicWindow + achzehn/magic_window):
    # Android 13 MIUI'da ETKILI dosya /data/system/cloudFeature_* kopyasidir;
    # cloud dosya VARSA /product'taki liste yok sayilir. Eski kod once
    # /product'a yazip "hemen" diye donuyordu -> A13'te kural hic okunmuyordu.
    # Artik: 1) cloud (dogru dosya) 2) /product (best-effort) 3) katman.
    _e2c=/data/system/cloudFeature_embedded_rules_list.xml
    _e2f=$(fl_emb_rom); [ -n "$_e2f" ] || _e2f=/product/etc/embedded_rules_list.xml
    if [ -d /data/system ]; then
        [ -f "$_e2c" ] || { [ -f "$_e2f" ] && cp -f "$_e2f" "$_e2c" 2>/dev/null; }
        [ -f "$_e2c" ] || { printf '<embedded_rules>\n</embedded_rules>\n' > "$_e2c" 2>/dev/null; }
        if [ -f "$_e2c" ]; then
            case "$(fl_xml_upsert "$_e2c" "$_e2p" "$_e2l")" in
                ok*)
                    fl_chown_system "$_e2c" >/dev/null 2>&1
                    # /product da yazilabilirse ikisini de guncelle (A12 ROM'lar)
                    if [ -f "$_e2f" ] && [ -w "$_e2f" ]; then
                        fl_xml_upsert "$_e2f" "$_e2p" "$_e2l" >/dev/null 2>&1
                        fl_chown_system "$_e2f" >/dev/null 2>&1
                    fi
                    [ -n "$(fl_pkg_line "$_e2c" "$_e2p")" ] && { echo "ok|$_e2c|hemen"; return 0; } ;;
            esac
        fi
    fi
    # cloud olmadi: /product yazilabilirse oraya (Android 12 yolu)
    if [ -f "$_e2f" ]; then
        [ -w "$_e2f" ] || fl_product_rw >/dev/null 2>&1
        if [ -w "$_e2f" ]; then
            case "$(fl_xml_upsert "$_e2f" "$_e2p" "$_e2l")" in
                ok*) fl_chown_system "$_e2f" >/dev/null 2>&1
                     echo "ok|$_e2f|hemen"; return 0 ;;
            esac
        fi
    fi
    # son care: modul katmani (yeniden baslatta bind-mount; skip_mount YOK)
    _e2o=$(fl_emb_overlay); _e2d=${_e2o%/*}
    [ -d "$_e2d" ] || mkdir -p "$_e2d" 2>/dev/null
    [ -f "$_e2o" ] || { [ -f "$_e2f" ] && { cp -f "$_e2f" "$_e2o" 2>/dev/null || cat "$_e2f" > "$_e2o" 2>/dev/null; }; }
    [ -f "$_e2o" ] || printf '<embedded_rules>\n</embedded_rules>\n' > "$_e2o" 2>/dev/null
    case "$(fl_xml_upsert "$_e2o" "$_e2p" "$_e2l")" in
        ok*) echo "ok|katman|yeniden-baslat" ;;
        *)   echo "fail|katman-yazilamadi" ;;
    esac
}

fl_mb_write2() {   # $1 paket [$2 true|false] -> ok|<yol>|hemen | ok|katman|... | fail
    _m2p=$1
    _m2v=${2:-true}
    _m2l="  <package name=\"$_m2p\" disable=\"$_m2v\" />"
    # v2.14: cloudFeature dosyasi Android 13'te yetkilidir (bkz. fl_emb_write2).
    # disable="true" = MIUI mailbox (ortadaki telefon sutunu) KAPALI.
    _m2c=/data/system/cloudFeature_fixed_orientation_list.xml
    _m2rom=$(fl_mb_rom); [ -n "$_m2rom" ] || _m2rom=/product/etc/fixed_orientation_list.xml
    if [ -d /data/system ]; then
        [ -f "$_m2c" ] || { [ -f "$_m2rom" ] && cp -f "$_m2rom" "$_m2c" 2>/dev/null; }
        [ -f "$_m2c" ] || { printf '<fixed_orientation_list>\n</fixed_orientation_list>\n' > "$_m2c" 2>/dev/null; }
        if [ -f "$_m2c" ]; then
            case "$(fl_xml_upsert "$_m2c" "$_m2p" "$_m2l")" in
                ok*)
                    fl_chown_system "$_m2c" >/dev/null 2>&1
                    if [ -f "$_m2rom" ] && [ -w "$_m2rom" ]; then
                        fl_xml_upsert "$_m2rom" "$_m2p" "$_m2l" >/dev/null 2>&1
                        fl_chown_system "$_m2rom" >/dev/null 2>&1
                    fi
                    echo "ok|$_m2c|hemen"; return 0 ;;
            esac
        fi
    fi
    if [ -f "$_m2rom" ]; then
        [ -w "$_m2rom" ] || fl_product_rw >/dev/null 2>&1
        if [ -w "$_m2rom" ]; then
            case "$(fl_xml_upsert "$_m2rom" "$_m2p" "$_m2l")" in
                ok*) fl_chown_system "$_m2rom" >/dev/null 2>&1
                     echo "ok|$_m2rom|hemen"; return 0 ;;
            esac
        fi
    fi
    echo "fail|mailbox-kurali-yazilamadi"
}

# ------------------------------------------------------------
#  TEK DUGME: uygula-hepsi / kapat-hepsi
#  Her adim olculur; donus: ADIM|n|OK|... / SONUC|...
# ------------------------------------------------------------
fl_uygula_hepsi() {   # $1 paket
    _ah=$1
    [ -n "$_ah" ] || { echo "SONUC|OLMADI|paket-yok"; return 1; }
    # v2.15: "HER SEYI UYGULA" artik paketi listelere de ekler ve letterbox
    # katmanini da uygular. Eskiden sadece o anlik yaziyordu: uygulama
    # listesinde 'ekrana yay' rozeti gorunmuyor, acilista yeniden
    # uygulanmiyor, FORCE_RESIZE_APP hic yazilmiyordu.
    fl_list_add "$FL_FORCE" "$_ah"; fl_list_del "$FL_NEVER" "$_ah"
    fl_appmode_add "$_ah"
    # v2.18 ADIM0: MIUI kural motoru. Kapaliysa butun per-app kurallar
    # olu dogar; once bunu olc ve gerekirse acmayi dene (acilis betigi
    # force listesi doluyken motoru zaten acik birakir).
    _ah0=$(fl_miui_engine_guard 2>/dev/null)
    case "$_ah0" in
        acik)            echo "ADIM|0|OK|miui-motor|acik" ;;
        kapaliydi-acildi) echo "ADIM|0|UYARI|miui-motor|kapaliydi, acma denendi; etki icin YENIDEN BASLAT gerekebilir" ;;
        kapali-acilamadi) echo "ADIM|0|HATA|miui-motor|KAPALI ve acilamadi -> per-app kurallar calismaz; Kayıtlar loguna bak" ;;
        *)               echo "ADIM|0|BILGI|miui-motor|durum: $_ah0" ;;
    esac
    echo "ADIM|1|...|mailbox|kural yaziliyor"
    _ah1=$(fl_mb_write2 "$_ah")
    case "$_ah1" in
        ok*) fl_mb_save "$_ah"; echo "ADIM|1|OK|mailbox|$(printf '%s' "$_ah1" | cut -d'|' -f2-)" ;;
        *)   echo "ADIM|1|HATA|mailbox|$(printf '%s' "$_ah1" | cut -d'|' -f2-)" ;;
    esac
    echo "ADIM|2|...|yatay-kural|yaziliyor"
    _ah2=$(fl_emb_write2 "$_ah")
    case "$_ah2" in
        ok*|"ok|katman"*) echo "ADIM|2|OK|yatay-kural|$(printf '%s' "$_ah2" | cut -d'|' -f2-)" ;;
        *) echo "ADIM|2|UYARI|yatay-kural|$(printf '%s' "$_ah2" | cut -d'|' -f2-)" ;;
    esac
    echo "ADIM|3|...|aosp-katman|letterbox + ekrana yay"
    if [ "${COMPAT_MODE:-smart}" != "off" ]; then
        fl_compat_one "$_ah" yes >/dev/null 2>&1
    fi
    _ah3=$(fl_appmode_apply "$_ah" 2>/dev/null)
    case "$_ah3" in
        ok*) echo "ADIM|3|OK|aosp-katman|$(printf '%s' "$_ah3" | cut -d'|' -f2-)" ;;
        *)   echo "ADIM|3|UYARI|aosp-katman|$(printf '%s' "$_ah3" | cut -d'|' -f2-)" ;;
    esac
    echo "ADIM|4|...|kural-yenile|MIUI kurallari yeniden okutuluyor"
    _ah4=$(fl_rules_reload)
    case "$_ah4" in
        tetiklendi*) echo "ADIM|4|OK|kural-yenile|$_ah4" ;;
        *)           echo "ADIM|4|UYARI|kural-yenile|$_ah4" ;;
    esac
    am force-stop "$_ah" >/dev/null 2>&1
    echo "ADIM|5|OK|uygulama|durduruldu (yeni kurallar acilista okunur)"
    echo "ADIM|6|...|pencere|olculuyor"
    _ah6=$(fl_embed_check "$_ah")
    case "$_ah6" in
        tam)   echo "ADIM|6|OK|pencere|tam ($(fl_cur_size 2>/dev/null))"
               echo "SONUC|CALISIYOR|tam ekran dogrulandi"; return 0 ;;
        orta*) echo "ADIM|6|UYARI|pencere|hala dar: $(printf '%s' "$_ah6" | cut -d'|' -f2-)"
               echo "SONUC|KANIT-YOK|uygulamayi ac, sonra 'kesin test' bas"; return 2 ;;
        *)     echo "ADIM|6|KANIT-YOK|pencere|uygulama kapali"
               echo "SONUC|KANIT-YOK|kurallar yazildi; uygulamayi ac ve 'kesin test' bas"; return 2 ;;
    esac
}

fl_kapat_hepsi() {   # $1 paket
    _kh=$1
    [ -n "$_kh" ] || { echo "SONUC|OLMADI|paket-yok"; return 1; }
    # v2.15: paket Force/Never listelerinden de cikarilir. Eskiden listede
    # kaliyordu; sonraki 'Uygula'/acilis onu yeniden zorluyordu.
    fl_list_del "$FL_FORCE" "$_kh"; fl_list_del "$FL_NEVER" "$_kh"
    # 1) mailbox / yatay kural satirlarimizi sil
    fl_mb_unsave "$_kh"
    for _khf in /product/etc/fixed_orientation_list.xml \
                /data/system/cloudFeature_fixed_orientation_list.xml \
                $(fl_mb_rom); do
        [ -f "$_khf" ] && fl_xml_drop "$_khf" "$_kh" && fl_chown_system "$_khf"
    done
    for _khf in /product/etc/embedded_rules_list.xml \
                /data/system/cloudFeature_embedded_rules_list.xml \
                $(fl_emb_rom) $(fl_emb_overlay); do
        [ -f "$_khf" ] && fl_xml_drop "$_khf" "$_kh" && fl_chown_system "$_khf"
    done
    echo "ADIM|1|OK|mailbox|satirlar silindi"
    # 2) AOSP katmani
    _kh2=$(fl_appmode_clear "$_kh" 2>/dev/null); fl_appmode_del "$_kh"
    echo "ADIM|2|OK|aosp-katman|$_kh2"
    # 3) letterbox compat
    _kh3=$(fl_reset_pkg_ids "$_kh")
    fl_xml_drop_pkg "$_kh"
    echo "ADIM|3|OK|letterbox|$_kh3"
    # 4) kural yenile + uygulamayi durdur
    _kh4=$(fl_rules_reload)
    am force-stop "$_kh" >/dev/null 2>&1
    echo "ADIM|4|OK|kural-yenile|$_kh4"
    echo "ADIM|5|OK|uygulama|durduruldu"
    # 5) dogrula
    _kh5=$(fl_mb_state "$_kh")
    case "$_kh5" in
        yok|varsayilan|aktif) echo "ADIM|6|OK|mailbox|eski hal: $_kh5"
                              echo "SONUC|CALISIYOR|ayarlar geri alindi ($_kh5)"; return 0 ;;
        kapali)               echo "ADIM|6|UYARI|mailbox|hala kapali"
                              echo "SONUC|OLMADI|mailbox satiri silinemedi"; return 1 ;;
    esac
    echo "SONUC|CALISIYOR|geri alindi"
    return 0
}

fl_kesin() {   # $1 paket -> ADIM satirlari + SONUC satiri
    _kp=$1
    [ -n "$_kp" ] || { echo "SONUC|OLMADI|paket-yok"; return 1; }

    # 0) v2.18: MIUI kural motoru (activity embedding). Kapaliysa 1-2.
    # adimlar dosyada dogru olsa bile servis islemez -> once bunu olc.
    _k_mt=$(fl_miui_embed_state)
    case "$_k_mt" in
        acik)   echo "ADIM|0|OK|miui-motor|acik (kurallari isleyen servis ayakta)" ;;
        kapali) echo "ADIM|0|HATA|miui-motor|KAPALI -> per-app kurallar bu yuzden calismaz. v2.18 acilista acar: YENIDEN BASLAT ve tekrar dene; acilmazsa Kayitlar loguna bak" ;;
        *)      echo "ADIM|0|BILGI|miui-motor|durum okunamadi (MIUI olmayabilir)" ;;
    esac

    # 1) MIUI mailbox kurali (asil neden)
    _k_mb=$(fl_mb_state "$_kp")
    case "$_k_mb" in
        kapali)   echo "ADIM|1|OK|mailbox|kural yazili: disable=\"true\"" ;;
        aktif|varsayilan)
                  echo "ADIM|1|HATA|mailbox|MIUI ortadaki sutun ACIK ($_k_mb) -> 'mailbox kapat'" ;;
        *)        echo "ADIM|1|UYARI|mailbox|kural dosyasi yok (bu ROM'da mailbox katmani yok)" ;;
    esac

    # 2) zorla yatay kurali
    _k_ey=$(fl_emb_state "$_kp")
    case "$_k_ey" in
        "var|hemen")           echo "ADIM|2|OK|yatay-kural|kural dosyasinda var (acilis/canlı komutla okunur)" ;;
        "var|yeniden-baslat")  echo "ADIM|2|UYARI|yatay-kural|katmana yazildi -> bir kez yeniden baslat" ;;
        *)                     echo "ADIM|2|UYARI|yatay-kural|kural yok (bu uygulama icin uygulanmamis)" ;;
    esac

    # 3) cihaz geneli yatay kilidi
    _k_ig=$(fl_rotation_lock_on)
    case "$_k_ig" in
        acik)   echo "ADIM|3|OK|genel-yatay|ignore-orientation=acik" ;;
        kapali) echo "ADIM|3|HATA|genel-yatay|ignore-orientation=kapali -> 'kilidi ac'" ;;
        *)      echo "ADIM|3|UYARI|genel-yatay|okunamadi" ;;
    esac

    # 4) canli pencere olcumu (uygulama acik degilse olculemez -> KANIT YOK deriz)
    _k_task=$(fl_find_task "$_kp")
    _k_geo=$(fl_embed_check "$_kp")
    _k_ekran=$(fl_cur_size 2>/dev/null)
    case "$_k_geo" in
        tam)      echo "ADIM|4|OK|pencere|ekrani tam dolduruyor ($_k_ekran)" ;;
        orta*)    echo "ADIM|4|HATA|pencere|dar pencere: $(printf '%s' "$_k_geo" | cut -d'|' -f2-) (ekran $_k_ekran)" ;;
        *)
            if [ -n "$_k_task" ]; then
                echo "ADIM|4|UYARI|pencere|olculemedi (uygulama arka planda olabilir)"
            else
                echo "ADIM|4|KANIT-YOK|pencere|uygulama acik degil"
            fi ;;
    esac

    # ---- HUKUM ----
    # v2.14: once OLCULEN gercek. Pencere tam ekransa modul calisiyor
    # demektir; kural satiri sorgusu bunu bozamaz (eskiden pencere tam
    # olsa bile "mailbox acik" deyip OLMADI veriliyordu).
    case "$_k_geo" in
        tam)
            echo "SONUC|CALISIYOR|pencere $_k_ekran dolduruyor"
            return 0 ;;
    esac
    if [ "$_k_mb" = "aktif" ] || [ "$_k_mb" = "varsayilan" ]; then
        echo "SONUC|OLMADI|MIUI mailbox acik: uygulama ortada kalir"
        return 1
    fi
    if [ "$_k_ig" = "kapali" ]; then
        echo "SONUC|OLMADI|genel yatay kilidi kapali"
        return 1
    fi
    case "$_k_geo" in
        orta*)
            echo "SONUC|OLMADI|kural yazildi ama pencere dar: uygulamayi 'yeniden baslat'"
            return 1 ;;
    esac
    if [ -z "$_k_task" ]; then
        echo "SONUC|KANIT-YOK|uygulama kapali: ac, sonra 'kesin test' tekrar bas"
        return 2
    fi
    echo "SONUC|KANIT-YOK|pencere okunamadi (dumpsys cevap vermedi)"
    return 2
}

fl_screen_size() {
    _ss=$(wm size 2>/dev/null | tr -d '\r' | awk '/Override size:/{print $3} /Physical size:/{p=$3} END{if(!p) p=""; print p}' | head -n 1)
    _so=$(wm size 2>/dev/null | tr -d '\r' | awk '/Override size:/{print $3; exit}')
    [ -n "$_so" ] && _ss=$_so
    case "$_ss" in
        [0-9]*x[0-9]*) echo "$_ss" ;;
        *)             fl_cur_size 2>/dev/null || echo "" ;;
    esac
}

fl_find_task() {   # $1 paket -> gorev kimligi (yoksa bos)
    _ft=$(dumpsys activity activities 2>/dev/null | tr -d '\r' | awk -v p="$1" '
        /Task\{/ && index($0, "A=" p) { if (match($0, /#[0-9]+/)) { print substr($0, RSTART+1, RLENGTH-1); exit } }
    ')
    [ -n "$_ft" ] || _ft=$(dumpsys activity 2>/dev/null | tr -d '\r' | awk -v p="$1" '
        /Task\{/ && index($0, p) { if (match($0, /#[0-9]+/)) { print substr($0, RSTART+1, RLENGTH-1); exit } }
    ')
    printf '%s' "$_ft"
}

fl_task_is_full() {   # $1 paket -> "evet" | "hayir" | "?"
    # 1) pencere dokumu: MID_BOUND var mi?
    _rb=$(fl_embed_check "$1")
    case "$_rb" in
        tam)   echo "evet"; return ;;
        orta*) echo "hayir"; return ;;
    esac
    # 2) yedek olcum: gorev sinirlari (dumpsys activity activities -> bounds=[l,t][r,b])
    _tg=$(fl_screen_size | cut -dx -f1)
    case "$_tg" in ''|*[!0-9]*) echo "?"; return ;; esac
    _bn=$(dumpsys activity activities 2>/dev/null | tr -d '\r' | awk -v p="$1" '
        /Task\{/ && index($0, "A=" p) { f=1; next }
        f && /bounds=\[/ { print; exit }' | sed 's/.*bounds=\[//; s/\].*//' | tr -d ' ')
    case "$_bn" in *,*,*,*) ;; *) echo "?"; return ;; esac
    _bl=$(printf '%s' "$_bn" | cut -d, -f1)
    _br=$(printf '%s' "$_bn" | cut -d, -f3)
    case "${_bl}${_br}" in ''|*[!0-9]*) echo "?"; return ;; esac
    if [ "$((_br - _bl))" -ge "$((_tg * 98 / 100))" ]; then echo "evet"; else echo "hayir"; fi
}

fl_task_fullscreen() {   # $1 paket -> "OK|tam" | "HATA|sebep"
    _fp=$1
    [ -n "$_fp" ] || { echo "HATA|paket-yok"; return 1; }
    _tid=$(fl_find_task "$_fp")
    if [ -z "$_tid" ]; then echo "HATA|gorev-yok (uygulama acik degil)"; return 1; fi
    _sz=$(fl_screen_size)
    case "$_sz" in [0-9]*x[0-9]*) : ;; *) echo "HATA|ekran-okunamadi"; return 1 ;; esac
    _w=$(printf '%s' "$_sz" | cut -dx -f1)
    _h=$(printf '%s' "$_sz" | cut -dx -f2)
    am task resize "$_tid" 0 0 "$_w" "$_h" >/dev/null 2>&1 || \
        am task resize "$_tid" 0 0 "$_w" "$_h" >/dev/null 2>&1
    _i=0
    while [ "$_i" -lt 3 ]; do
        _i=$((_i + 1))
        case "$(fl_task_is_full "$_fp")" in
            evet) echo "OK|tam|task=$_tid ${_w}x${_h}"; return 0 ;;
        esac
        sleep 1
        am task resize "$_tid" 0 0 "$_w" "$_h" >/dev/null 2>&1
    done
    case "$(fl_embed_check "$_fp")" in
        orta*) echo "HATA|miui-orta-pencere (task=$_tid)"; return 1 ;;
        tam)   echo "OK|tam|task=$_tid"; return 0 ;;
        *)     echo "?|olculemedi|task=$_tid"; return 0 ;;
    esac
}

fl_watch_on() {   # izleyici calisiyor mu -> acik|kapali
    _wp=$(cat "$FL_DIR/watch.pid" 2>/dev/null)
    if [ -n "$_wp" ] && [ -d "/proc/$_wp" ]; then echo "acik"; else echo "kapali"; fi
}

# ============================================================
#  v2.9 — GERI ALMA ARTIK DOGRULANIYOR
#  v2.8'de "birak" yalnizca am compat reset cagiriyordu ve sonucu
#  HIC KONTROL ETMIYORDU. Bu cihazda am compat sik sik
#  "Failed transaction (2147483646)" veriyor -> komut basarisiz,
#  arayuz ise "geri-alindi" yaziyordu. Kullanici: "birak diyorum,
#  hala yatay". Cozum: 4 saglayici sirayla denenir, her biri
#  am compat state / dumpsys ile DOGRULANIR, kalan varsa HATA doner.
# ============================================================
fl_xml_drop_pkg() {   # $1 paket -> istenen listesinden ve XML'den cikar
    _xp=$1
    [ -n "$_xp" ] || return 0
    if [ -f "$FL_WANT" ]; then
        grep -vx "$_xp" "$FL_WANT" > "$FL_WANT.t" 2>/dev/null
        mv -f "$FL_WANT.t" "$FL_WANT" 2>/dev/null
    fi
    _x=/data/misc/appcompat/compat_framework_overrides.xml
    [ -f "$_x" ] || return 0
    # v2.14 DUZELTME: dosyadaki oge <package name="..."> bicimindedir;
    # eski kod "packageName=" aradigi icin hicbir satir silinmiyordu.
    grep -q "name=\"$_xp\"" "$_x" 2>/dev/null || { rm -f "$_x.fl.t" 2>/dev/null; return 0; }
    awk -v p="$_xp" '!(/<package[ \t]/ && index($0, "name=\"" p "\"") > 0)' \
        "$_x" > "$_x.fl.t" 2>/dev/null && cat "$_x.fl.t" > "$_x" 2>/dev/null
    rm -f "$_x.fl.t" 2>/dev/null
    chown 1000:1000 "$_x" 2>/dev/null
    chmod 0640 "$_x" 2>/dev/null
    return 0
}

fl_ids_of_pkg() {   # $1 paket -> bu uygulamaya uygulanmis olabilecek id listesi
    echo "$COMPAT_ID $FL_AM_LIST"
}

fl_reset_pkg_ids() {   # $1 paket -> "temiz" | "kalan:id1,id2"
    _rp=$1
    _kalan=""
    for _id in $(fl_ids_of_pkg "$_rp"); do
        _st=$(fl_change_state "$_id" "$_rp")
        case "$_st" in
            disabled) continue ;;                 # zaten kapali
            unknown)  : ;;                        # ROM bilmiyor -> asagida dene
        esac
        # 1) am compat reset --no-kill
        am compat reset --no-kill "$_id" "$_rp" >/dev/null 2>&1
        [ "$(fl_change_state "$_id" "$_rp")" = "disabled" ] && continue
        # 2) am compat reset
        am compat reset "$_id" "$_rp" >/dev/null 2>&1
        [ "$(fl_change_state "$_id" "$_rp")" = "disabled" ] && continue
        # 3) cmd platform_compat reset
        cmd platform_compat reset "$_id" "$_rp" >/dev/null 2>&1
        [ "$(fl_change_state "$_id" "$_rp")" = "disabled" ] && continue
        # 4) XML override dosyasindan sil (boot'ta tekrar uygulanmasin)
        fl_xml_drop_pkg "$_rp"
        [ "$(fl_change_state "$_id" "$_rp")" = "disabled" ] && continue
        case "$(fl_change_state "$_id" "$_rp")" in
            disabled|unknown) ;;                  # unknown olumcul degil
            *) _kalan="$_kalan,$_id" ;;
        esac
    done
    if [ -n "$_kalan" ]; then echo "kalan:${_kalan#,}"; else echo "temiz"; fi
}

fl_rotation_lock_on() {   # cihaz geneli yatay kilidi acik mi? -> acik|kapali|?
    _w=$(wm get-ignore-orientation-request -d 0 2>/dev/null | tr -d '\r')
    [ -n "$_w" ] || _w=$(wm get-ignore-orientation-request 2>/dev/null | tr -d '\r')
    case "$_w" in
        *true*)  echo "acik"  ;;
        *false*) echo "kapali" ;;
        *)       echo "?" ;;
    esac
}


# ============================================================
#  v2.9 — GERI ALMA ARTIK DOGRULANIYOR
#  v2.8'de "birak" yalnizca am compat reset cagiriyordu ve sonucu
#  HIC KONTROL ETMIYORDU. Bu cihazda am compat sik sik
#  "Failed transaction (2147483646)" veriyor -> komut basarisiz,
#  arayuz ise "geri-alindi" yaziyordu. Kullanici: "birak diyorum,
#  hala yatay". Cozum: 4 saglayici sirayla denenir, her biri
#  am compat state / dumpsys ile DOGRULANIR, kalan varsa HATA doner.
fl_list_clear() {   # $1 dosya -> icini bosalt
    [ -n "$1" ] || return 0
    : > "$1" 2>/dev/null
    chmod 0644 "$1" 2>/dev/null
    return 0
}
# Uygulama su anda MIUI'nin orta penceresinde mi? (canli kontrol)
fl_embed_check() {   # $1 = paket  -> "orta|<gen>x<yuk>" | "tam" | "?"
    # v2.8 DUZELTME: v2.7'de paket adi ile MID_BOUND AYNI SATIRDA araniyordu.
    # Gercek dumpsys'te MID_BOUND ayri satirda -> kontrol hep "?" donuyordu
    # ve "ekrana yayildi" yalanini ortaya cikaramiyordu. Artik pencere blogu
    # blok blok okunuyor.
    _p=$1
    [ -n "$_p" ] || { echo "?"; return; }
    # v2.13: iki kaynak -> pencere listesi, sonra gorev (task) sinirlari.
    # Uygulama hic calismiyorsa "kapali" der; "?" demek "olcemedim" demek,
    # "kapali" demek "olcecek pencere yok" demek. Arayuz ikisini ayirir.
    _geo=""
    _d=$(dumpsys window windows 2>/dev/null)
    _blk=""
    if [ -n "$_d" ]; then
        _blk=$(printf '%s\n' "$_d" | awk -v p="$_p" '
            /Window ?#|Window\{/ { blk = (index($0, p) > 0) }
            blk { print }' | head -n 60)
    fi
    if [ -z "$_blk" ]; then
        _ad=$(dumpsys activity activities 2>/dev/null)
        if [ -n "$_ad" ]; then
            if ! printf '%s\n' "$_ad" | grep -qF "$_p"; then
                # surec yasiyor ama aktivite gorunmuyorsa "kapali" DEME
                if [ -n "$(pidof "$_p" 2>/dev/null)" ]; then echo "?" ; return; fi
                echo "kapali"; return
            fi
            _ab=$(printf '%s\n' "$_ad" | grep -F "$_p" -A 15 | grep -E 'bounds=\[|mBounds=' | head -n 1)
            _geo=$(printf '%s' "$_ab" | sed 's/[^0-9]/ /g' | awk '{ if (NF >= 4) print $1","$2","$3","$4 }')
            case "$_geo" in *,*,*,*) ;; *) echo "?" ; return ;; esac
            _blk="task"
        else
            echo "?" ; return
        fi
    fi
    _mid=$(printf '%s\n' "$_blk" | grep -F "MID_BOUND=SkRegion" | head -n 1)
    if [ "$_blk" = "task" ]; then
        :   # _geo zaten gorev sinirlarindan geldi
    elif [ -n "$_mid" ]; then
        _geo=$(printf '%s' "$_mid" | sed 's/.*SkRegion((//; s/)).*//; s/)).*//' | tr -d ' ')
    else
        # mFrame=[0,0][2880,1800] -> "0,0,2880,1800"  (v2.10: iki koseli bicim hatasi)
        _geo=$(printf '%s\n' "$_blk" | grep -E 'mFrame=|frame=\[' | head -n 1 |
               sed 's/[^0-9]/ /g' | awk '{ if (NF >= 4) print $1","$2","$3","$4 }')
    fi
    case "$_geo" in *,*,*,*) ;; *) echo "?" ; return ;; esac
    _l=$(printf '%s' "$_geo" | cut -d, -f1)
    _t=$(printf '%s' "$_geo" | cut -d, -f2)
    _r=$(printf '%s' "$_geo" | cut -d, -f3)
    _b=$(printf '%s' "$_geo" | cut -d, -f4)
    case "${_l}${_t}${_r}${_b}" in ''|*[!0-9]*) echo "?" ; return ;; esac
    _w=$((_r - _l)); _h=$((_b - _t))
    [ "$_w" -gt 0 ] && [ "$_h" -gt 0 ] || { echo "?"; return; }
    _sw=$(fl_cur_size 2>/dev/null | cut -dx -f1)
    case "$_sw" in ''|*[!0-9]*) echo "orta|${_w}x${_h}"; return ;; esac
    if [ "$_w" -ge "$((_sw * 98 / 100))" ]; then
        echo "tam"
    else
        echo "orta|${_w}x${_h}"
    fi
}

# MIUI'de gomme kapali mi? (tanı/UI icin tek satir)
fl_embed_status_line() {
    if ! fl_miui_var; then echo "MIUI degil (gerekmiyor)"; return; fi
    case "$(fl_miui_embed_state)" in
        kapali) echo "kapali (orta pencere yok)" ;;
        acik)   echo "ACIK - uygulamalar ortada kalabilir (Ayarlar > MIUI orta pencere: kapat)" ;;
        *)      echo "prop okunamadi" ;;
    esac
}

# ============================================================
#  v2.13 — MIUI KURAL DOSYALARI: IZIN + KANIT
#  Acik kaynak (ybcq/MIUI-MagicWindow, sothx, achzehn/magic_window)
#  ayni seyi soyluyor: MIUI bu dosyalari system:system ve 660 ile
#  bekler; izin/sahip yanlissa kural SESSIZCE yok sayilir.
#  Artik yazdiktan sonra izin+sahip+SELinux etiketi duzeltilir ve
#  panelde dosya dosya KANIT olarak gosterilir.
# ============================================================
fl_miui_fixperm() {   # $1 dosya -> "izin|sahip|selinux"
    _fp=$1
    [ -f "$_fp" ] || return 1
    chown 1000:1000 "$_fp" 2>/dev/null
    chmod 0660 "$_fp" 2>/dev/null
    case "$_fp" in
        /data/system/*)        chcon u:object_r:system_data_file:s0 "$_fp" 2>/dev/null ;;
        /product/*|/system/*)  restorecon "$_fp" 2>/dev/null ;;
    esac
    _fmd=$(stat -c '%a' "$_fp" 2>/dev/null)
    _fow=$(stat -c '%u:%g' "$_fp" 2>/dev/null)
    _fcx=$(ls -Zd "$_fp" 2>/dev/null | awk '{ print $1 }')
    echo "${_fmd:-?}|${_fow:-?}|${_fcx:-?}"
}

fl_rules_proof() {   # [$1 paket] -> dosya/satir kanit satirlari
    _pp=$1
    for _pf in /data/system/cloudFeature_fixed_orientation_list.xml \
               /data/system/cloudFeature_embedded_rules_list.xml \
               /product/etc/fixed_orientation_list.xml \
               /product/etc/embedded_rules_list.xml; do
        if [ ! -f "$_pf" ]; then
            echo "dosya|$_pf|yok|-|-|-"
            continue
        fi
        _pmd=$(stat -c '%a' "$_pf" 2>/dev/null)
        _pow=$(stat -c '%u:%g' "$_pf" 2>/dev/null)
        _pcx=$(ls -Zd "$_pf" 2>/dev/null | awk '{ print $1 }')
        echo "dosya|$_pf|var|${_pmd:-?}|${_pow:-?}|${_pcx:-?}"
        if [ -n "$_pp" ]; then
            _pl=$(fl_pkg_line "$_pf" "$_pp")
            if [ -n "$_pl" ]; then
                echo "satir|$_pf|$(printf '%s' "$_pl" | sed 's/^[[:space:]]*//' | cut -c1-200)"
            fi
        fi
    done
    # v2.19: motor durumu + MIUI servisinin KENDI yardim metni + son
    # canli komut cevaplari. "MIUI protokolune uygun mu" sorusunun
    # kaniti buradan okunur; yardim metni cihazdan gelir, tahmini degil.
    echo "motor|$(fl_miui_embed_state 2>/dev/null)"
    if [ -f "$FL_DIR/.miui_cmd_help" ]; then
        sed 's/^/yardim|/' "$FL_DIR/.miui_cmd_help" 2>/dev/null | head -n 30
    fi
    if [ -f "$FL_LOG" ]; then
        grep -F "miui-cmd:" "$FL_LOG" 2>/dev/null | tail -n 15 | sed 's/^/canli-log|/'
        grep -F "canli-geri-al:" "$FL_LOG" 2>/dev/null | tail -n 5 | sed 's/^/canli-log|/'
    fi
}

# ---------- v2.13: MIUI kural onbellegini ZORLA tazele ----------
# AOSP katmani aninda calisir; MIUI'nin KENDI kural dosyalari ise
# platform_compat servisi tarafindan ACILISTA okunur. Xposed'siz
# root ile tek yol zygote/system_server tazelemek (yumusak yeniden
# baslatma, ~20 sn; uygulama verisi SILINMEZ).
fl_miui_softreboot() {
    if command -v setprop >/dev/null 2>&1; then
        if setprop ctl.restart zygote >/dev/null 2>&1; then
            echo "tetiklendi|setprop ctl.restart zygote"; return 0
        fi
    fi
    _zy=$(pidof zygote64 2>/dev/null)
    [ -n "$_zy" ] || _zy=$(pidof zygote 2>/dev/null)
    if [ -n "$_zy" ]; then
        if kill -9 $_zy >/dev/null 2>&1; then
            echo "tetiklendi|kill zygote ($_zy)"; return 0
        fi
    fi
    echo "olmadi|zygote bulunamadi (kabuk root degil mi?)"; return 1
}

# ---------- v2.8: gercek compat durumu (tahmin degil) ----------
# ============================================================
#  v2.13 — GERCEK OKUMA KATMANI (3 kaynak, kaynak adi ile)
#  Sikayet kaniti: panelde 4 satir "okunamadı" yaziyordu.
#  Sebep: Android 13'te `am compat state` YOK ve tek okuma yolu
#  oydu. Artik sirayla:
#     1) am compat state                     (varsa)
#     2) dumpsys platform_compat             (A12+ gercek kaynak)
#     3) compat_framework_overrides.xml      (bizim/ROM'un override'i)
#  Hicbiri cevap vermezse "uygulanmadi|kayit-yok" der. UYDURMAZ.
#  "unknown" kelimesi arayuzde artik "kayıt yok" olarak gorunur.
# ============================================================
fl_compat_xml_state() {   # $1 id  $2 pkg -> enabled|disabled|(bos)
    _xs_f=/data/misc/appcompat/compat_framework_overrides.xml
    [ -f "$_xs_f" ] || return 0
    awk -v id="$1" -v p="$2" '
        /<change[ \t]/ { on = (index($0, "id=\"" id "\"") > 0); next }
        on && /<package[ \t]/ {
            if (index($0, "name=\"" p "\"") > 0) {
                if (index($0, "enabled=\"true\"") > 0)  { print "enabled";  exit }
                if (index($0, "enabled=\"false\"") > 0) { print "disabled"; exit }
            }
        }' "$_xs_f" 2>/dev/null
}

fl_compat_state_src() {   # $1 id  $2 pkg -> "<state>|<kaynak>"
    _ss_id=$1; _ss_p=$2
    [ -n "$_ss_id" ] && [ -n "$_ss_p" ] || { echo "unknown|paket-yok"; return; }
    _ss_o=$(am compat state "$_ss_id" "$_ss_p" 2>/dev/null | tr -d '\r')
    case "$_ss_o" in
        *LOGGED*)   echo "disabled|am-compat(logged)"; return ;;
        *ENABLED*)  echo "enabled|am-compat";          return ;;
        *DISABLED*) echo "disabled|am-compat";         return ;;
    esac
    _ss_d=$(dumpsys platform_compat 2>/dev/null)
    if [ -n "$_ss_d" ]; then
        _ss_l=$(printf '%s\n' "$_ss_d" | grep -F "$_ss_p" | grep -F "$_ss_id" | head -n 1)
        case "$_ss_l" in
            *ENABLED*)  echo "enabled|platform_compat";  return ;;
            *DISABLED*) echo "disabled|platform_compat"; return ;;
        esac
    fi
    case "$(fl_compat_xml_state "$_ss_id" "$_ss_p")" in
        enabled)  echo "enabled|xml-override";  return ;;
        disabled) echo "disabled|xml-override"; return ;;
    esac
    echo "unknown|kayit-yok"
}

fl_change_state() {   # $1 id $2 pkg -> enabled|disabled|unknown
    fl_compat_state_src "$1" "$2" | cut -d'|' -f1
}

fl_change_state_src() {   # $1 id $2 pkg -> "<kaynak-dili>|<kaynak>"
    _c2=$(fl_compat_state_src "$1" "$2")
    _c2s=$(printf '%s' "$_c2" | cut -d'|' -f1)
    _c2k=$(printf '%s' "$_c2" | cut -d'|' -f2-)
    [ "$_c2s" = "unknown" ] && _c2s=uygulanmadi
    echo "$_c2s|$_c2k"
}

# Tek paketi TAM geri al: letterbox + ekrana-yay + listeler + durdur
fl_revert_pkg() {   # $1 = paket  [$2 = "never" ise dokunma listesine ekle]
    #   -> "OK|temiz" (0) | "HATA|kalan:..." (1)
    #   NOT: "dokunma" listesine eklemek SADECE kullanici acikca istediginde
    #   olur. Global geri alma (restore / revert-all) listeye YAZMAZ; yoksa
    #   bir kez "geri al" diyen kullanici butun uygulamalari dokunulmaz
    #   yapar ve modul bir daha hicbir sey uygulayamaz (v2.9'da bulundu).
    _rv=$1
    [ -n "$_rv" ] || return 1
    _r=$(fl_reset_pkg_ids "$_rv")          # 4 saglayici + dogrulama
    fl_xml_drop_pkg "$_rv"
    fl_appmode_clear "$_rv" > /dev/null 2>&1   # v2.19: oran katmani da geri alinir (eksikti!)
    fl_miui_clear "$_rv"                   # v2.11: mailbox + yatay kural
    # v2.19: MIUI dosyalarinda satirimiz KALDI mi diye GERI OKU.
    # Kalmissa "temiz" demek yalan olur -> HATA don.
    _mkalan=""
    for _mf in /data/system/cloudFeature_fixed_orientation_list.xml \
               /data/system/cloudFeature_embedded_rules_list.xml \
               /product/etc/fixed_orientation_list.xml \
               /product/etc/embedded_rules_list.xml; do
        [ -f "$_mf" ] || continue
        [ -n "$(fl_pkg_line "$_mf" "$_rv" 2>/dev/null)" ] && _mkalan="$_mkalan $_mf"
    done
    if [ -n "$_mkalan" ]; then
        fl_compat_status_set "$_rv" fail "geri-alinamadi"
        fl_log "geri alma BASARISIZ: $_rv -> MIUI kurali su dosyalarda kaldi:$_mkalan"
        echo "HATA|miui-dosya-temizlenemedi:$_mkalan"
        return 1
    fi
    fl_list_del "$FL_FORCE" "$_rv"
    [ "$2" = "never" ] && fl_list_add "$FL_NEVER" "$_rv"
    if [ -f "$FL_DONE" ]; then
        grep -vx "$_rv" "$FL_DONE" > "$FL_DONE.tmp" 2>/dev/null
        mv -f "$FL_DONE.tmp" "$FL_DONE" 2>/dev/null
    fi
    fl_appmode_del "$_rv"
    fl_appmode_state_set "$_rv" ok "geri-alindi"
    am force-stop "$_rv" >/dev/null 2>&1
    if [ "$_r" = "temiz" ]; then
        fl_compat_status_set "$_rv" fail "geri-alindi"
        fl_log "geri alindi: $_rv (letterbox + ekrana-yay + XML + durduruldu) | DOGRULANDI"
        echo "OK|temiz"
        return 0
    fi
    fl_compat_status_set "$_rv" fail "geri-alinamadi"
    fl_log "geri alma BASARISIZ: $_rv -> $_r (am compat bu ROM'da reddediyor)"
    echo "HATA|$_r"
    return 1
}

fl_appmode_map() {
    if [ -f "$FL_APPMODE" ]; then
        FL_AM_MAP=" $(tr '\n' ' ' < "$FL_APPMODE" 2>/dev/null) "
    else
        FL_AM_MAP=" "
    fi
}
fl_appmode_has() { case "$FL_AM_MAP" in *" $1 "*) return 0 ;; esac; return 1; }
fl_appmode_add() { fl_list_add "$FL_APPMODE" "$1"; fl_appmode_map; }
fl_appmode_del() { fl_list_del "$FL_APPMODE" "$1"; fl_appmode_map; }
fl_appmode_count() {
    # NOT: "grep -c" bos dosyada "0" yazip 1 ile cikar; eski "|| echo 0"
    # yuzunden deger "0\n0" oluyordu (state.txt'ye fazladan satir). v2.7.1
    _c=$(grep -c . "$FL_APPMODE" 2>/dev/null)
    case "$_c" in ''|*[!0-9]*) _c=0 ;; esac
    echo "$_c"
}

fl_am_cmd() {   # $1 enable|disable|reset  $2 id  $3 paket ; basarili -> stdout: yontem
    # v2.16: once BELGELENMIS sozdizimi: am compat enable <degisim> <paket>
    # (Google/OPPO A13 kilavuzu). Eski kod once "am compat --no-kill enable"
    # deniyordu; ROM bunu "Usage:" ile reddederse cikti hata kalibina
    # uymadigi icin BASARILI sanilabiliyordu.
    _o=$(am compat "$1" "$2" "$3" 2>&1 </dev/null)
    case "$_o" in
        *"nknown"*|*"rror"*|*"xception"*|*"ailed"*|*"ot found"*|*"Usage"*) : ;;
        *) echo "am"; return 0 ;;
    esac
    _o=$(am compat "$1" --no-kill "$2" "$3" 2>&1 </dev/null)   # bazi ROM'lar sonda ister
    case "$_o" in
        *"nknown"*|*"rror"*|*"xception"*|*"ailed"*|*"ot found"*|*"Usage"*) : ;;
        *) echo "am-nokill-son"; return 0 ;;
    esac
    _o=$(am compat --no-kill "$1" "$2" "$3" 2>&1 </dev/null)
    case "$_o" in
        *"nknown"*|*"rror"*|*"xception"*|*"ailed"*|*"ot found"*|*"Usage"*) : ;;
        *) echo "am-nokill"; return 0 ;;
    esac
    _o=$(cmd platform_compat "$1" "$2" "$3" 2>&1 </dev/null)
    case "$_o" in
        *"nknown"*|*"rror"*|*"xception"*|*"ailed"*|*"ot found"*|*"Usage"*|"") : ;;
        *) echo "cmd"; return 0 ;;
    esac
    return 1
}

fl_am_state() {   # $1 id  $2 paket -> on | off | (bos)
    # v2.13: `am compat state` A13'te yok -> 3 kaynakli okuyucu
    case "$(fl_compat_state_src "$1" "$2" | cut -d'|' -f1)" in
        enabled)  echo on  ;;
        disabled) echo off ;;
        *)        echo ""  ;;
    esac
}

fl_appmode_apply() {   # $1 paket -> "ok|<yontemler>" | "fail|<sebep>" (return 1)
    _ap=$1
    [ -n "$_ap" ] || { echo "fail|paket-yok"; return 1; }
    fl_pkg_excluded "$_ap" && { echo "fail|sistem-paketi"; return 1; }
    _sdk=$(getprop ro.build.version.sdk 2>/dev/null)
    case "$_sdk" in ''|*[!0-9]*) _sdk=33 ;; esac

    # 1) KAPICI: acilmadan hicbir oran override'i islemez
    _w=$(fl_am_cmd enable "$FL_AM_GATE" "$_ap" 2>/dev/null)
    _ok=$?
    _st=$(fl_am_state "$FL_AM_GATE" "$_ap")
    [ "$_ok" = "0" ] || [ "$_st" = "on" ] || { echo "fail|kapici-reddetti"; return 1; }
    _m="MIN:${_w:-state}"

    # 2) 16:9'a kadar izin -> sag-sol bantlar kalkar (genis ekran doldurma)
    if _w=$(fl_am_cmd enable OVERRIDE_MIN_ASPECT_RATIO_LARGE "$_ap" 2>/dev/null); then
        _m="$_m LARGE:$_w"
    else
        _m="$_m LARGE:-"
    fi

    # 3) "sadece dikey aktiviteye uygula" kisitini kaldir
    #    (acik kalirsa yatay tutulan uygulamada override islemez)
    if _w=$(fl_am_cmd disable OVERRIDE_MIN_ASPECT_RATIO_PORTRAIT_ONLY "$_ap" 2>/dev/null); then
        _m="$_m P-ONLY:$_w"
    fi

    # 4) Android 14+: yon + boyut + oran kisitlarini komple kaldir
    if [ "$_sdk" -ge 34 ]; then
        if _w=$(fl_am_cmd enable OVERRIDE_ANY_ORIENTATION_TO_USER "$_ap" 2>/dev/null); then
            _m="$_m ANY:$_w"
        fi
    fi
    # 5) v2.8: "komut basarili dedi" YETMEZ -> sistemden gercek durumu oku
    _st_gate=$(fl_change_state "$FL_AM_GATE" "$_ap")
    _st_large=$(fl_change_state OVERRIDE_MIN_ASPECT_RATIO_LARGE "$_ap")
    # DIKKAT: "unknown" (ROM 'am compat state' bilmiyor) OLUMCUL DEGIL.
    # Sadece sistem ACIKCA "disabled" derse basarisiz say.
    case "$_st_gate" in
        disabled) echo "fail|dogrulanmadi(gate=disabled)"; return 1 ;;
    esac
    _m="$_m dogrulandi:gate=$_st_gate,large=$_st_large"

    # 6) v2.11: MIUI mailbox (asil neden) + "强制横屏" kurali.
    #    AOSP anahtarlari MIUI'nin ortadaki telefon sutununu KALDIRMAZ;
    #    o yuzden v2.10'a kadar "uygulandi" yazsa da gorunum ayniydi.
    #    Kural MIUI'nin kendi dosyasina yazilir ve geri okunarak dogrulanir.
    _mr=$(fl_miui_apply "$_ap" 2>/dev/null)
    case "$_mr" in
        ok*) _m="$_m | $(printf '%s' "$_mr" | cut -d'|' -f2-)" ;;
        # v2.14: AOSP katmani acildiysa islem BASARILIDIR; MIUI kurali
        # yazilamadiysa bunu sonucun icinde ACIKCA soyle, listeden atma.
        *)   _m="$_m | miui=hata($(printf '%s' "$_mr" | cut -d'|' -f2-))" ;;
    esac
    echo "ok|$_m"
}

fl_appmode_clear() {   # $1 paket -> her seyi fabrika degerine dondur
    _ap=$1
    [ -n "$_ap" ] || { echo "fail|paket-yok"; return 1; }
    _n=0
    for _id in $FL_AM_LIST; do
        fl_am_cmd reset "$_id" "$_ap" > /dev/null 2>&1 && _n=$((_n + 1))
    done
    # PORTRAIT_ONLY varsayilan olarak ACIK
    fl_am_cmd enable OVERRIDE_MIN_ASPECT_RATIO_PORTRAIT_ONLY "$_ap" > /dev/null 2>&1
    # v2.14: "yay" kapatmak SADECE oran katmanini geri alir. MIUI mailbox /
    # yatay kurali ayri katmandir; onu silen yol kapat-hepsi / geri-al'dir.
    echo "ok|reset:$_n"
}

fl_appmode_state_set() {   # pkg  ok|fail  yontem
    [ -f "$FL_APPMODE_ST" ] || : > "$FL_APPMODE_ST" 2>/dev/null
    _t=$FL_APPMODE_ST.tmp
    awk -F'\t' -v p="$1" 'NF && $1 != p' "$FL_APPMODE_ST" 2>/dev/null > "$_t"
    printf '%s\t%s\t%s\n' "$1" "$2" "$3" >> "$_t"
    mv -f "$_t" "$FL_APPMODE_ST" 2>/dev/null
    chmod 0644 "$FL_APPMODE_ST" 2>/dev/null
}

fl_appmode_state_get() {   # pkg -> ok|fail|-
    [ -f "$FL_APPMODE_ST" ] || { echo "-"; return; }
    _v=$(awk -F'\t' -v p="$1" '$1 == p { print $2; exit }' "$FL_APPMODE_ST" 2>/dev/null)
    [ -n "$_v" ] || _v="-"
    echo "$_v"
}

# Acilista / "Uygula" sonrasi: listedeki her pakete ekrana-yay uygula.
# Liste kucuk oldugu icin hizli; zaten uygulanmissa tekrar yazmak zararsizdir.
fl_appmode_pass() {
    fl_appmode_map
    _n=$(grep -c . "$FL_APPMODE" 2>/dev/null)
    case "$_n" in ''|*[!0-9]*) _n=0 ;; esac
    if [ "$_n" -le 0 ]; then
        fl_log "appmode: listede paket yok (ekrana-yay uygulanacak uygulama secilmemis)"
        return 0
    fi
    fl_log "appmode: $_n paket icin ekrana-yay uygulaniyor"
    _ok=0; _f=0
    for _p in $(cat "$FL_APPMODE" 2>/dev/null); do
        [ -n "$_p" ] || continue
        # v2.7.1 hiz: katman zaten aciksa 4 komut yerine 1 sorgu yap
        # v2.8: atlanan pakette de durum YAZILIR; yoksa UI eski "fail"
        # degerini gosterip "calismiyor" izlenimi veriyordu.
        _gs=$(fl_change_state "$FL_AM_GATE" "$_p")
        if [ "$_gs" = "enabled" ]; then
            _ok=$((_ok + 1))
            fl_appmode_state_set "$_p" ok "zaten-acik"
            continue
        fi
        if _r=$(fl_appmode_apply "$_p" 2>/dev/null); then
            _ok=$((_ok + 1))
            fl_appmode_state_set "$_p" ok "$(printf '%s' "$_r" | cut -d'|' -f2-)"
        else
            _f=$((_f + 1))
            fl_appmode_state_set "$_p" fail "$(printf '%s' "$_r" | cut -d'|' -f2-)"
            fl_log "appmode HATA: $_p -> $_r"
        fi
    done
    fl_log "appmode: ok=$_ok hata=$_f (toplam $_n)"
    [ "$_f" = "0" ] || fl_log "appmode DIKKAT: $_f paket uygulanamadi (ayrinti: appmode-list)"
}

# ============================================================
#  v2.14 — TEK YON UYGULAMA YOLU (WebUI "rotate" + fl_apply ortak)
#  Eskiden WebUI rotate komutu kendi basina settings yaziyordu:
#  sensor modunda accelerometer_rotation=0 yazip sensoru KAPATIYOR,
#  user_rotation=0 ile ekrani DIKEYE kilitliyordu. Artik yon nereden
#  degisirse degissin ayni fonksiyon calisir ve sonuc GERI OKUNUR.
#  $1 = sensor|lock|off|auto
#  Cikti: OK|<cozulen-mod>|ignore=..|accel=..|urot=..  |  HATA|<sebep>
# ============================================================
fl_apply_orientation() {
    _om=$1
    case "$_om" in sensor|lock|off|auto) : ;; *) echo "HATA|gecersiz-mod:$_om"; return 1 ;; esac
    if [ "$AUTO_LANDSCAPE" = "true" ]; then
        _auto=$(fl_detect_landscape)
        [ -n "$_auto" ] && ROTATION=$_auto
    fi
    # ignore-orientation katmani
    if [ "$_om" = "off" ]; then
        for _d in $DISPLAYS; do
            fl_run wm set-ignore-orientation-request -d "$_d" false || fl_run wm set-ignore-orientation-request false
        done
    elif [ "$IGNORE_ORIENTATION" = "true" ]; then
        for _d in $DISPLAYS; do
            fl_run wm set-ignore-orientation-request -d "$_d" true || fl_run wm set-ignore-orientation-request true
        done
    fi
    _ig=$(fl_rotation_lock_on)
    _mode=$_om
    if [ "$_mode" = "auto" ]; then
        if [ "$_ig" = "acik" ]; then _mode=sensor; else _mode=lock; fi
    elif [ "$_mode" = "sensor" ] && [ "$_ig" = "kapali" ]; then
        fl_log "  UYARI: ignore-orientation reddedildi -> kilit moduna geciliyor"
        _mode=lock
    fi
    case "$_mode" in
        lock)   fl_lock_landscape ;;
        sensor) fl_sensor_mode ;;
        off)    fl_off_mode ;;
    esac
    FL_MODE=$_mode
    FL_IGN_OK=bilinmiyor
    case "$_ig" in acik) FL_IGN_OK=yes ;; kapali) FL_IGN_OK=no ;; esac
    # state.txt guncelle (Durum sekmesi bayat deger gostermesin)
    fl_state_set mode "$_mode"
    fl_state_set mode_istenen "$_om"
    fl_state_set ignore "$FL_IGN_OK"
    fl_state_set ts "$(date '+%Y-%m-%d %H:%M:%S')"
    _ar=$(settings get system accelerometer_rotation 2>/dev/null | tr -d '\r')
    _ur=$(settings get system user_rotation 2>/dev/null | tr -d '\r')
    _ig2=$(fl_rotation_lock_on)
    fl_log "yon uygulandi: istenen=$_om cozulen=$_mode ignore=$_ig2 accel=$_ar urot=$_ur"
    case "$_mode" in
        off) [ "$_ig2" = "kapali" ] || { echo "HATA|ignore-orientation hala $_ig2"; return 1; } ;;
        *)   [ "$_ig2" = "acik" ]   || { echo "HATA|ignore-orientation hala $_ig2"; return 1; } ;;
    esac
    # kilit modunda ekran GERCEKTEN yatay mi (okunabiliyorsa)?
    if [ "$_mode" = "lock" ]; then
        fl_is_landscape
        [ "$?" = "1" ] && { echo "HATA|ekran yataya kilitlenemedi (hala dikey)"; return 1; }
    fi
    echo "OK|$_mode|ignore=$_ig2|accel=$_ar|urot=$_ur"
    return 0
}

# ============================================================
#  v2.14 — TEK UYGULAMAYA TAM ZORLAMA (WebUI "set force" yolu)
#  Eski "Yatay zorla" SADECE letterbox compat yaziyordu; MIUI mailbox,
#  yatay kurali ve ekrana-yay listeye bile girmiyordu. Kullanici seciyor,
#  uygulamada hicbir sey degismiyordu. Artik tek pakete BUTUN katmanlar
#  (letterbox + mailbox + fullRule + ekrana-yay) uygulanir, her biri
#  ayri ayri olculup tek satirda raporlanir.
#  Cikti: OK|<pkg>|force|letterbox=<s> mailbox=<s> kural=<s> yay=<s>
# ============================================================
fl_force_apply() {
    # v2.19: MIUI'li cihazda "Yatay zorla" = MIUI'NIN KENDI PROTOKOLU:
    #   embedded_rules_list fullRule + fixed_orientation disable="true"
    #   (dosya = kalicilik) + cmd miui_embedding_window (canli) + motor acik.
    # AOSP oran override'lari (ekrana-yay gerilmesi) bu yola ARTIK DAHIL
    # DEGIL: sahada oyunu saga-sola gerip bozdugu ve geri almayi
    # karmasiklastirdigi goruldu. Gerdime isteyen ayri dugmeyi kullanir
    # ("ekrana yay" / "HER SEYI UYGULA"). MIUI yoksa eski compat yolu.
    _fp=$1
    [ -n "$_fp" ] || { echo "HATA|paket-yok"; return 1; }
    if fl_miui_var; then
        _mt=$(fl_miui_engine_guard 2>/dev/null)
        _mr=$(fl_miui_apply "$_fp" 2>/dev/null)
        _can=yok; case "$_mr" in *canli=ok*) _can=ok ;; esac
        _lb="-"
        case "$_mr" in
            ok*)
                _mb=ok
                case "$_mr" in *yatay=hemen*) _kr=hemen ;; *yatay=yeniden-baslat*) _kr=yeniden-baslat ;; *) _kr=? ;; esac
                case "$_mr" in
                    *ayar-kapali*)
                        # kullanici MIUI mailbox'i config'den kapatmis:
                        # kural yazilmaz; yedek olarak compat yolu denenir
                        fl_compat_one "$_fp" yes >/dev/null 2>&1
                        _lb=$(fl_change_state "$COMPAT_ID" "$_fp" 2>/dev/null); [ -n "$_lb" ] || _lb=unknown
                        _mb=ayar-kapali ;;
                esac
                am force-stop "$_fp" >/dev/null 2>&1
                fl_log "force-apply(miui): $_fp kural=$_kr canli=$_can motor=$_mt letterbox=$_lb"
                echo "OK|$_fp|force|mod=miui-protokol kural=$_kr canli=$_can motor=$_mt letterbox=$_lb"
                case "$_mt" in kapali-acilamadi) return 1 ;; esac
                return 0 ;;
            *)
                fl_log "force-apply(miui) HATA: $_fp -> $_mr motor=$_mt"
                echo "OK|$_fp|force|mod=miui-protokol hata=$_mr motor=$_mt"
                return 1 ;;
        esac
    fi
    # MIUI degil: AOSP compat yolu
    _lb="-"
    if [ "${COMPAT_MODE:-smart}" != "off" ] && [ "$COMPAT_FORCE_RESIZE" = "true" ]; then
        fl_compat_one "$_fp" yes >/dev/null 2>&1
        _lb=$(fl_change_state "$COMPAT_ID" "$_fp" 2>/dev/null); [ -n "$_lb" ] || _lb=unknown
    else
        _lb=mod-kapali
    fi
    am force-stop "$_fp" >/dev/null 2>&1
    fl_log "force-apply(aosp): $_fp letterbox=$_lb"
    echo "OK|$_fp|force|mod=aosp-compat letterbox=$_lb"
    case "$_lb" in disabled|mod-kapali) return 1 ;; esac
    return 0
}

fl_apply() {
    fl_prepare
    fl_perms_fix
    fl_log "=== apply $FL_MODVER basladi | istenen=$ROTATION_MODE aci=$ROTATION dry=$DRY_RUN ==="

    if ! command -v wm >/dev/null 2>&1; then
        fl_log "HATA: 'wm' komutu PATH'te yok (PATH=$PATH)"
    fi

    if [ "${FL_SKIP_ORIENT:-0}" = "1" ]; then
        # devam parcasi: yon/settings katmani zaten yazildi, tekrar yazma (hiz)
        FL_IGN_OK=yes
        FL_MODE=$(sed -n 's/^mode=//p' "$FL_STATE" 2>/dev/null | head -n 1)
        [ -n "$FL_MODE" ] || FL_MODE=$ROTATION_MODE
    else
    if [ "$AUTO_LANDSCAPE" = "true" ]; then
        _auto=$(fl_detect_landscape)
        if [ -n "$_auto" ]; then
            [ "$ROTATION" != "$_auto" ] && fl_log "  auto: ROTATION $ROTATION -> $_auto ($(wm size 2>/dev/null | head -n 1))"
            ROTATION=$_auto
        else
            fl_log "  auto aci tespiti basarisiz, ROTATION=$ROTATION"
        fi
    fi

    # 1) Uygulamalarin "sadece dikey" istegini yok say
    FL_IGN_OK=unknown
    if [ "$IGNORE_ORIENTATION" = "true" ]; then
        for _d in $DISPLAYS; do
            fl_run wm set-ignore-orientation-request -d "$_d" true \
                || fl_run wm set-ignore-orientation-request true
        done
        _g=$(wm get-ignore-orientation-request -d 0 2>&1)
        case "$_g" in
            *true*) FL_IGN_OK=yes ;;
            *)      FL_IGN_OK=no ;;
        esac
        fl_log "  ignore-orientation etkin=$FL_IGN_OK | $_g"
    fi

    # 2) Activity'leri yeniden boyutlandirilabilir yap
    # NOT: bu deger system_server tarafindan SADECE ACI LISTA okunur
    # (AOSP ActivityTaskManagerService.retrieveSettings). Boot'tan sonra
    # yazildiysa ancak BIR SONRAKI acilista etkili olur.
    if [ "$FORCE_RESIZABLE" = "true" ]; then
        _fr_old=$(settings get global force_resizable_activities 2>&1)
        FL_FR_PREV=$_fr_old
        fl_run settings put global force_resizable_activities 1
        fl_log "  force_resizable_activities: once=$_fr_old sonra=$(settings get global force_resizable_activities 2>&1)"
        case "$_fr_old" in
            1) : ;;
            *) fl_log "  NOT: bu deger sadece acilista okunur -> bir sonraki yeniden baslatmada tam etkili" ;;
        esac
    fi

    # 2b) Multi-window katmani: resize edilemeyen uygulamalari da boyutlanabilir say
    fl_run wm set-multi-window-config --supportsNonResizable 1
    fl_log "  multi-window: $(wm get-multi-window-config 2>&1 | tr '\n' ' ')"

    # 3) Mod secimi
    _mode=$ROTATION_MODE
    case "$_mode" in
        auto)
            if [ "$FL_IGN_OK" = "yes" ]; then _mode=sensor; else _mode=lock; fi
            ;;
        sensor)
            if [ "$FL_IGN_OK" = "no" ]; then
                fl_log "  UYARI: ROM ignore-orientation istegini kabul etmiyor -> SENSOR modu bosuna olur, GARANTILI KILIT'e geciliyor"
                _mode=lock
            fi
            ;;
        lock|off) : ;;
        *) fl_log "  UYARI: bilinmeyen ROTATION_MODE='$_mode' -> kilit modu kullaniliyor"
           _mode=lock ;;
    esac
    FL_MODE=$_mode
    fl_log "  uygulanan mod=$FL_MODE"
    _ay=0; _an=0
    for _p in $(cat "$FL_APPMODE" 2>/dev/null); do
        [ -n "$_p" ] || continue
        case "$(fl_change_state "$FL_AM_GATE" "$_p")" in
            enabled) _ay=$((_ay + 1)) ;;
            *)       _an=$((_an + 1)) ;;
        esac
    done
    fl_log "  ekrana-yay: sistemde acik=$_ay / listede=$(fl_appmode_count) / kapali=$_an"
    [ "$_an" = "0" ] || fl_log "  DIKKAT: $_an paket listede ama sistemde acik degil"

    if [ "$_mode" = "lock" ]; then
        fl_lock_landscape
    elif [ "$_mode" = "off" ]; then
        fl_off_mode
    else
        fl_sensor_mode
    fi
    fi   # FL_SKIP_ORIENT

    # 4) Letterbox cozumu: uygulama bazli FORCE_RESIZE_APP
    #    Ilk turda 90 sn, kuyruk bitmediyse sonraki turlarda 30 sn'lik
    #    dilimler halinde devam eder (toplam boot basina ~3 dk, arka planda).
    # v2.1: ilk turda HER ZAMAN calisir. (v2.0'da kosul yuzunden sahada
    # hic calismadi -> "compat paket: 0".)
    if [ "$FL_FIRST" = "1" ]; then
        FL_FIRST=0
        fl_compat_batch
    elif [ "$(fl_compat_left)" -gt 0 ]; then
        _budget_save=$COMPAT_BUDGET
        COMPAT_BUDGET=60
        fl_compat_batch
        COMPAT_BUDGET=$_budget_save
    fi

    # 4b) v2.7: ekrana-yay listesi (kullanici sectiyse)
    # v2.8: MIUI orta-pencere (gomme) durumu her uygulamada loga yazilir
    if fl_miui_var; then
        case "$(fl_miui_embed_state)" in
            acik)   fl_log "MIUI orta-pencere: ACIK -> oyunlar ekranin ortasinda kalabilir! (WebUI > Durum > kapat)" ;;
            kapali) fl_log "MIUI orta-pencere: kapali (tam ekran yolu acik)" ;;
            *)      fl_log "MIUI orta-pencere: prop okunamadi ($FL_MIUI_EMB_PROP)" ;;
        esac
    else
        fl_log "MIUI orta-pencere: MIUI degil, kontrol gerekmiyor"
    fi
    fl_appmode_pass

    # 5) Opsiyonel SystemUI tazeleme
    if [ "$RESTART_SYSTEMUI" = "true" ]; then
        _suid=$(pidof com.android.systemui 2>/dev/null)
        if [ -n "$_suid" ]; then
            kill "$_suid" 2>/dev/null
            fl_log "  SystemUI yeniden baslatildi"
        fi
    fi

    # v2.2: WebUI "Durum" sekmesi bu dosyadan beslenir (hizli, sistem
    # komutu cagirmadan). Ekran/yön bilgisi de buraya yazilir.
    _yon=$(fl_is_landscape; echo $?)
    {
        echo "mode=$FL_MODE"
        echo "rotation=$ROTATION"
        echo "ignore=$FL_IGN_OK"
        echo "compat=$(fl_compat_count)"
        echo "ts=$(date '+%Y-%m-%d %H:%M:%S')"
        echo "ekran=$(fl_cur_size)"
        echo "yon=$_yon"
        echo "fr=$(settings get global force_resizable_activities 2>/dev/null)"
        echo "ss=$(pidof system_server 2>/dev/null)"
        echo "rom=$(getprop ro.build.version.incremental)"
        echo "cihaz=$(getprop ro.product.model)"
        echo "appmode=$(fl_appmode_count)"
    } > "$FL_STATE" 2>/dev/null
    chmod 0644 "$FL_STATE" 2>/dev/null
    rm -f "$FL_STATE.tmp" 2>/dev/null

    # v2.11: kayitli mailbox / yatay kuralarini acilista tazele
    fl_mb_reapply

    # uygulama listesi onbellegi (WebUI aninda acilsin diye)
    fl_apps_cache_build > /dev/null 2>&1

    if [ "${CHUNK_MORE:-0}" = "1" ] && [ "${FL_CHUNK_LIMIT:-0}" -gt 0 ]; then
        fl_log "=== parca bitti (mod=$FL_MODE, compat=$(fl_compat_count), kalan=$(fl_compat_left)) ==="
        return 0
    fi
    fl_report
    fl_log "=== apply bitti (mod=$FL_MODE, aci=$ROTATION, ignore=$FL_IGN_OK, compat=$(fl_compat_count)) ==="
}

# ---------- tani raporu --------------------------------------
fl_appmode_list_one_line() {
    _n=$(grep -c . "$FL_APPMODE" 2>/dev/null)
    case "$_n" in ''|0|*[!0-9]*) echo "yok"; return ;; esac
    tr '\n' ' ' < "$FL_APPMODE" 2>/dev/null | cut -c1-200
}

fl_report_body() {
    # v2.7: service.sh rapordan ONCE apply etmiyor olabilir -> "unknown"
    # yazmak yerine son bilinen durumu state dosyasindan oku.
    if [ "$FL_MODE" = "unknown" ] || [ -z "$FL_MODE" ]; then
        _sm=$(sed -n 's/^mode=//p' "$FL_STATE" 2>/dev/null | head -n 1)
        [ -n "$_sm" ] && FL_MODE=$_sm
    fi
    if [ "$FL_IGN_OK" = "unknown" ] || [ -z "$FL_IGN_OK" ]; then
        _si=$(sed -n 's/^ignore=//p' "$FL_STATE" 2>/dev/null | head -n 1)
        [ -n "$_si" ] && FL_IGN_OK=$_si
    fi
    echo "======================================================"
    echo " Force Landscape - All Apps  $FL_MODVER   |  TANI RAPORU"
    echo "======================================================"
    echo "tarih       : $(date '+%Y-%m-%d %H:%M:%S')"
    echo "cihaz       : $(getprop ro.product.model)  ($(getprop ro.product.device))"
    echo "android     : $(getprop ro.build.version.release) / API $(getprop ro.build.version.sdk)"
    echo "rom         : $(getprop ro.build.version.incremental)  MIUI=$(getprop ro.miui.ui.version.name)"
    echo "kernel      : $(getprop ro.kernel.version)"
    echo "istenen mod : $ROTATION_MODE     uygulanan mod: $FL_MODE"
    echo "aci         : $ROTATION          ignore-orientation: $FL_IGN_OK"
    echo "---- WindowManager ----"
    echo "wm size                     : $(wm size 2>&1 | tr '\n' ' ')"
    echo "ignore-orientation          : $(wm get-ignore-orientation-request -d 0 2>&1)"
    echo "user-rotation               : $(wm user-rotation 2>&1 | tr '\n' ' ')"
    echo "fixed-to-user-rotation      : $(wm fixed-to-user-rotation -d 0 2>&1)"
    echo "multi-window config         : $(wm get-multi-window-config 2>&1 | tr '\n' ' ')"
    echo "ekran (cur)                 : $(fl_cur_size)"
    fl_is_landscape
    _lr=$?
    if [ "$FL_MODE" = "sensor" ]; then
        case $_lr in
            0) echo "ekran yonu                  : YATAY (o an yatay tutuluyor)";;
            1) echo "ekran yonu                  : DIKEY (tablet o an dikey tutuluyor - sensor modunda normal)";;
            *) echo "ekran yonu                  : okunamadi";;
        esac
    else
        case $_lr in
            0) echo "ekran yonu                  : YATAY (dogru)";;
            1) echo "ekran yonu                  : DIKEY (!) - kilit modunda yatay olmaliydi";;
            *) echo "ekran yonu                  : okunamadi";;
        esac
    fi
    echo "system_server               : pid=$(pidof system_server 2>/dev/null)  acilis kaydi (son 400 satir): $(logcat -d -b system -t 400 2>/dev/null | grep -c 'Entered the Android system server')"
    echo "---- Settings ----"
    echo "force_resizable_activities  : $(settings get global force_resizable_activities 2>&1)  (once: ${FL_FR_PREV:-?})"
    echo "accelerometer_rotation      : $(settings get system accelerometer_rotation 2>&1)"
    echo "user_rotation               : $(settings get system user_rotation 2>&1)"
    echo "---- MIUI mailbox (v2.11) ----"
    echo "mailbox ayari               : ${MIUI_MAILBOX:-true}"
    echo "mailbox ROM kurali          : $(fl_mb_rom)"
    echo "mailbox cloud kurali        : $(fl_mb_cloud)"
    echo "yatay kural dosyasi (ROM)   : $(fl_emb_rom)"
    echo "yatay kural dosyasi (cloud) : $(fl_emb_cloud)"
    echo "yatay kural dosyasi (katman): $(fl_emb_overlay)"
    echo "mailbox islenen paket       : $(fl_count "$FL_MB_LIST")"
    echo "---- Letterbox cozumu ----"
    echo "compat change               : $COMPAT_ID (FORCE_RESIZE_APP)"
    echo "compat mod                  : $COMPAT_MODE  (smart|whitelist|all|off)"
    echo "elle secilen (force/never)  : $(fl_count "$FL_FORCE") / $(fl_count "$FL_NEVER")"
    echo "compat testi (am compat)    : $(fl_compat_test_line)"
    if [ -f "$FL_CTEST" ]; then
        sed -n '1,6p' "$FL_CTEST" 2>/dev/null | sed 's/^/  | /'
    fi
    echo "compat XML yedegi           : $([ -f "$FL_XMLFLAG" ] && echo 'hazir (sonraki acilista uygulanir)' || echo 'gerekmedi')"
    echo "resize uygulanan paket      : $(fl_compat_count)"
    echo "yatay diye atlanan          : $(fl_compat_skipped)"
    echo "kalan                       : $(fl_compat_left)"
    echo "compat ozet                 : $(fl_compat_summary)"
    if [ -s "$FL_CERR" ]; then
        echo "---- compat hatalari (son 8) ----"
        fl_compat_errors_tail 8 | cut -c1-240 | sed 's/^/  | /'
    fi
    if [ -f "$FL_SKIP" ] && [ -s "$FL_SKIP" ]; then
        echo "atlanan ornekler            : $(head -n 8 "$FL_SKIP" 2>/dev/null | tr '\n' ' ')"
    fi
    echo "---- SONUC ----"
    if [ "$FL_MODE" = "lock" ]; then
        echo "* GARANTILI KILIT modu etkin: ekran istenen aciya kilitli,"
        echo "  uygulamalar kendi yon istegini dayatamaz. Sensor kapali."
    elif [ "$FL_MODE" = "off" ]; then
        echo "* Yon modu KAPALI: modul yone karismiyor, uygulamalar kendi"
        echo "  yonunu secer (sensor acik)."
    elif [ "$FL_MODE" = "sensor" ]; then
        echo "* SENSOR modu etkin: uygulamalarin yon kilidi yok sayiliyor,"
        echo "  tablet nasil tutulursa ekran ona gore doner."
    fi
    if [ "$(fl_compat_left)" -gt 0 ]; then
        echo "* DİKKAT: $(fl_compat_left) uygulamaya resize ayari henuz uygulanmadi."
        echo "  Modulun Action dugmesine 1-2 kez bas (veya bir kez yeniden baslat)."
    fi
    if [ "$(fl_compat_count)" = "0" ] && [ "$COMPAT_FORCE_RESIZE" = "true" ]; then
        echo "* DİKKAT: hicbir uygulamaya resize uygulanamadi. Sebep yukaridaki"
        echo "  'compat testi' satirinda yaziyor. XML yedegi hazirsa bir kez"
        echo "  yeniden baslatmak yeterli."
    fi
    echo "  (mod degistirmek icin: $FL_CFG icinde ROTATION_MODE)"
    echo "---- dumpsys window displays (ilk 6 satir) ----"
    dumpsys window displays 2>/dev/null | head -n 6
    echo "---- landscape.log (son 40 satir) ----"
    tail -n 40 "$FL_LOG" 2>/dev/null
}

fl_report() {
    [ "$MAKE_REPORT" = "true" ] || return 0
    _rep=$FL_REPORT
    fl_report_body > "$_rep" 2>/dev/null
    if [ ! -s "$_rep" ]; then
        FL_REPORT=$FL_DIR/rapor.txt
        _rep=$FL_REPORT
        fl_report_body > "$_rep" 2>/dev/null
    fi
    chmod 0644 "$_rep" 2>/dev/null
    fl_log "rapor: $FL_REPORT"
}

# ---------- durum (Action dugmesi / konsol) ------------------
fl_status() {
    fl_prepare
    echo " ---------- Force Landscape $FL_MODVER ----------"
    echo " mod (istenen/uygulanan)  : $ROTATION_MODE / $FL_MODE  (aci $ROTATION)"
    echo " ignore-orientation       : $(wm get-ignore-orientation-request -d 0 2>&1)"
    echo " user-rotation            : $(wm user-rotation 2>&1 | tr '\n' ' ')"
    echo " fixed-to-user-rotation   : $(wm fixed-to-user-rotation -d 0 2>&1)"
    echo " force_resizable          : $(settings get global force_resizable_activities 2>&1)"
    echo " accelerometer_rotation   : $(settings get system accelerometer_rotation 2>&1)"
    echo " ekran (cur)              : $(fl_cur_size)"
    fl_is_landscape
    case $? in
        0) echo " ekran yonu               : YATAY";;
        1) echo " ekran yonu               : DIKEY";;
        *) echo " ekran yonu               : okunamadi";;
    esac
    echo " compat mod               : $COMPAT_MODE  (force: $(fl_count "$FL_FORCE")  never: $(fl_count "$FL_NEVER"))"
    echo " compat paket             : $(fl_compat_count)   yatay-atlanan: $(fl_compat_skipped)   kalan: $(fl_compat_left)"
    echo " compat ozet              : $(fl_compat_summary)"
    echo " compat testi             : $(fl_compat_test_line)"
    echo " compat XML yedegi        : $([ -f "$FL_XMLFLAG" ] && echo HAZIR || echo gerekmedi)"
    echo " ekrana-yay (v2.7)        : $(fl_appmode_count) paket  $(fl_appmode_list_one_line)"
    echo " log                      : $FL_LOG"
    echo " rapor                    : $FL_REPORT"
}

# ---------- geri alma ---------------------------------------
fl_restore() {
    fl_prepare
    for _d in $DISPLAYS; do
        wm set-ignore-orientation-request -d "$_d" false >>"$FL_LOG" 2>&1
    done
    wm fixed-to-user-rotation -d 0 default >>"$FL_LOG" 2>&1
    wm user-rotation free >>"$FL_LOG" 2>&1
    wm reset-multi-window-config >>"$FL_LOG" 2>&1
    settings put system accelerometer_rotation 1 >>"$FL_LOG" 2>&1
    settings put global force_resizable_activities 0 >>"$FL_LOG" 2>&1
    for _list in "$FL_DONE" "$FL_SKIP"; do
        [ -f "$_list" ] || continue
        while read -r _pkg; do
            [ -n "$_pkg" ] || continue
            am compat reset --no-kill "$COMPAT_ID" "$_pkg" >>"$FL_LOG" 2>&1
            am compat reset --no-kill FORCE_RESIZE_APP "$_pkg" >>"$FL_LOG" 2>&1
        done < "$_list"
    done
    # v2.14: MIUI mailbox/yatay kurallari da geri alinir (pause/restore
    # eskiden bu satirlari sistemde birakiyordu). Liste once kopyalanir;
    # fl_miui_clear listeyi degistirdigi icin dogrudan okumak satir atlar.
    if [ -f "$FL_MB_LIST" ]; then
        cp -f "$FL_MB_LIST" "$FL_DIR/.mb_restore.txt" 2>/dev/null
        while read -r _pkg; do
            [ -n "$_pkg" ] || continue
            fl_miui_clear "$_pkg"
        done < "$FL_DIR/.mb_restore.txt"
        rm -f "$FL_DIR/.mb_restore.txt" 2>/dev/null
        fl_log "MIUI mailbox/yatay kurallari geri alindi"
    fi
    # v2.7: ekrana-yay katmani da fabrika degerine doner
    if [ -f "$FL_APPMODE" ]; then
        while read -r _pkg; do
            [ -n "$_pkg" ] || continue
            fl_appmode_clear "$_pkg" >>"$FL_LOG" 2>&1
        done < "$FL_APPMODE"
        fl_log "ekrana-yay katmani geri alindi ($(fl_appmode_count) paket listesi korundu)"
    fi
    rm -f "$FL_DONE" "$FL_SKIP" "$FL_TARGETS" "$FL_CSTAT" "$FL_CERR" "$FL_APPMODE_ST" 2>/dev/null
    fl_log "ayarlar geri alindi (auto-rotate acik, dikey istekler gecerli)"
}
