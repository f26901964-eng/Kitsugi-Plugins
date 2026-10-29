#!/system/bin/sh
# ============================================================
#  Force Landscape - All Apps  v2.9  |  webui.sh
#  WebUI'nin arka ucu.  KernelSU / SukiSU Ultra "WebUI" dugmesi
#  webroot/index.html dosyasini acar, o da bu betigi cagirir:
#
#     sh /data/adb/modules/force_landscape_all/webui.sh <komut> [arg...]
#
#  Cikti HER ZAMAN duz metin, satir basina bir kayit.
#  "|" ayirici, TAB ayirici: apps listesi.
# ============================================================

MODDIR=${0%/*}
[ -f "$MODDIR/landscape.sh" ] || MODDIR=/data/adb/modules/force_landscape_all
. "$MODDIR/landscape.sh"

fl_prepare

cfg_set() { # $1=anahtar $2=deger
    _k=$1; _v=$2
    [ -f "$FL_CFG" ] || cp -f "$FL_DEFAULT_CFG" "$FL_CFG" 2>/dev/null
    if grep -q "^$_k=" "$FL_CFG" 2>/dev/null; then
        sed "s|^$_k=.*|$_k=$_v|" "$FL_CFG" > "$FL_CFG.t" 2>/dev/null
        mv -f "$FL_CFG.t" "$FL_CFG" 2>/dev/null
    else
        echo "$_k=$_v" >> "$FL_CFG" 2>/dev/null
    fi
    chmod 0644 "$FL_CFG" 2>/dev/null
    eval "$_k=$_v"
}

human_date() { date '+%Y-%m-%d %H:%M:%S'; }

case "$1" in

# ------------------------------------------------------------
status)
    # v2.2 HIZLI: wm / dumpsys / logcat CAGRILMAZ (cihazda her biri
    # 0.5-2 s suruyor ve WebUI gec aciliyordu). Degerler state.txt'ten.
    ST_MODE=""; ST_ROT=""; ST_IGN=""; ST_COMPAT=""; ST_TS=""; ST_EKRAN=""; ST_YON=""
    ST_FR=""; ST_SS=""; ST_ROM=""; ST_CIHAZ=""
    if [ -f "$FL_STATE" ]; then
        while IFS='=' read -r _k _v; do
            case "$_k" in
                mode)   ST_MODE=$_v ;;
                rotation) ST_ROT=$_v ;;
                ignore) ST_IGN=$_v ;;
                compat) ST_COMPAT=$_v ;;
                ts)     ST_TS=$_v ;;
                ekran)  ST_EKRAN=$_v ;;
                yon)    ST_YON=$_v ;;
                fr)     ST_FR=$_v ;;
                ss)     ST_SS=$_v ;;
                rom)    ST_ROM=$_v ;;
                cihaz)  ST_CIHAZ=$_v ;;
            esac
        done < "$FL_STATE"
    fi
    echo "surum|$FL_MODVER"
    echo "tarih|$(human_date)"
    echo "cihaz|${ST_CIHAZ:-$(getprop ro.product.model)}"
    echo "rom|$ST_ROM"
    echo "mod_istenen|$ROTATION_MODE"
    echo "mod_uygulanan|${ST_MODE:-henuz-uygulanmadi}"
    echo "son_uygulama|$ST_TS"
    echo "aci|${ST_ROT:-$ROTATION}"
    # state.txt ilk uygulamadan once yok/bos olabilir -> canli deger dene
    _lv_ign=$ST_IGN
    if [ -z "$_lv_ign" ]; then
        case "$(wm get-ignore-orientation-request -d 0 2>/dev/null)" in
            *true*)  _lv_ign=yes ;;
            *false*) _lv_ign=no ;;
            *)       _lv_ign=bilinmiyor ;;
        esac
    fi
    _lv_ekr=$ST_EKRAN
    [ -n "$_lv_ekr" ] || _lv_ekr=$(fl_cur_size 2>/dev/null)
    [ -n "$_lv_ekr" ] || _lv_ekr=okunamadi
    _lv_yon=$ST_YON
    if [ -z "$_lv_yon" ]; then fl_is_landscape; _lv_yon=$?; fi
    echo "ignore_orientation|$_lv_ign"
    echo "ekran|$_lv_ekr"
    echo "ekran_yonu|$_lv_yon"
    echo "force_resizable|$ST_FR"
    echo "compat_mod|$COMPAT_MODE"
    echo "compat_testi|$(fl_compat_test_line)"
    echo "compat_applied|$(fl_compat_count)"
    echo "compat_skip|$(fl_compat_skipped)"
    echo "compat_kalan|$(fl_compat_left)"
    echo "compat_ozet|$(fl_compat_summary)"
    echo "compat_hatali|$(awk -F'\t' '$2=="fail" && $3!="geri-alindi"' "$FL_CSTAT" 2>/dev/null | grep -c .)"
    echo "son_hata|$(fl_compat_errors_tail 1 2>/dev/null | cut -f2- | cut -c1-180)"
    echo "duraklatildi|$([ -f /data/adb/modules/force_landscape_all/disable ] && echo 1 || echo 0)"
    echo "force_liste|$(fl_count "$FL_FORCE")"
    echo "never_liste|$(fl_count "$FL_NEVER")"
    echo "appmode_liste|$(fl_appmode_count)"
    echo "miui_embed|$(fl_miui_embed_state)"
    echo "xml_yedek|$([ -f "$FL_XMLFLAG" ] && echo 1 || echo 0)"
    echo "kilit|$(fl_rotation_lock_on)"
    echo "izleyici|$(fl_watch_on)"
    _mv=$(sed -n 's/^version=//p' "$MODDIR/module.prop" 2>/dev/null | head -n 1)
    echo "modul_surum|${_mv:-?}"
    echo "system_server|$ST_SS"
    echo "apps_cache|$( [ -s "$FL_APPS" ] && echo "$(grep -vc '^#' "$FL_APPS")" || echo 0 )"
    echo "apps_age|$(fl_apps_cache_age)"
    echo "log_yol|$FL_LOG"
    echo "rapor_yol|$FL_REPORT"
    echo "log_bayt|$([ -f "$FL_LOG" ] && wc -c < "$FL_LOG" 2>/dev/null || echo 0)"
    echo "meta_paket|$([ -s "$FL_META" ] && grep -c . "$FL_META" 2>/dev/null || echo 0)"
    echo "progress|$(fl_progress_read | tr '\n' ' ')"
    ;;

# ------------------------------------------------------------
status-full)   # yavas: canli wm/dumpsys/logcat sorgulari (Teşhis icin)
    echo "surum|$FL_MODVER"
    echo "tarih|$(human_date)"
    echo "cihaz|$(getprop ro.product.model) / $(getprop ro.product.device)"
    echo "android|$(getprop ro.build.version.release) API $(getprop ro.build.version.sdk)"
    echo "rom|$(getprop ro.build.version.incremental)"
    echo "mod_istenen|$ROTATION_MODE"
    echo "aci|$ROTATION"
    echo "ignore_orientation|$(wm get-ignore-orientation-request -d 0 2>&1)"
    echo "user_rotation|$(wm user-rotation 2>&1 | tr '\n' ' ')"
    echo "fixed_to_user_rotation|$(wm fixed-to-user-rotation -d 0 2>&1)"
    echo "force_resizable|$(settings get global force_resizable_activities 2>&1)"
    echo "accelerometer|$(settings get system accelerometer_rotation 2>&1)"
    echo "ekran|$(fl_cur_size)"
    fl_is_landscape; echo "ekran_yonu|$?"
    echo "system_server|$(pidof system_server 2>/dev/null)"
    ;;

# ------------------------------------------------------------
ping)   # kopru/hiz testi (aninda)
    echo "OK|$FL_MODVER|$(date +%s)"
    ;;

# ------------------------------------------------------------
# pkg<TAB>karar<TAB>uygulanmis<TAB>yon<TAB>neden
apps)   # ONBELLEKTEN okur (cihazda <0.1 s). Yoksa olusturur.
    if [ ! -s "$FL_APPS" ]; then
        fl_apps_cache_build > /dev/null 2>&1
    fi
    if [ -s "$FL_APPS" ]; then
        grep -v '^#' "$FL_APPS"
    else
        echo "#yok|paket listesi olusturulamadi"
    fi
    ;;

# ------------------------------------------------------------
apps-refresh)   # listeyi simdi yeniden hesapla (secim/mod degisince)
    if fl_apps_cache_build > /dev/null 2>&1; then
        echo "OK|$(grep -vc '^#' "$FL_APPS")"
    else
        echo "HATA|liste olusmadi"
    fi
    ;;

# ------------------------------------------------------------
set)  # set <paket> <force|never|auto>
    _p=$2; _v=$3
    [ -n "$_p" ] || { echo "HATA|paket yok"; exit 1; }
    case "$_v" in
        force) fl_list_add "$FL_FORCE" "$_p"; fl_list_del "$FL_NEVER" "$_p" ;;
        never) fl_list_add "$FL_NEVER" "$_p"; fl_list_del "$FL_FORCE" "$_p" ;;
        auto)  fl_list_del "$FL_FORCE" "$_p"; fl_list_del "$FL_NEVER" "$_p" ;;
        *) echo "HATA|gecersiz deger: $_v"; exit 1 ;;
    esac
    # v2.8 BUYUK DUZELTME: "dokunma/otomatik" secilince once uygulanmis
    # katman ANINDA geri alinir. v2.7'de yalnizca listeye yaziliyordu;
    # geri alma bir sonraki toplu "Uygula"ya kaliyordu (sahada: kullanici
    # kapatiyor, 5 dakika sonra hala yatay zorlanmis goruyordu).
    case "$_v" in
        never|auto)
            # v2.9: geri alma DOGRULANIR; basarisizsa OK yazilmaz
            _rr=$(fl_revert_pkg "$_p")
            case "$_rr" in
                OK*)    _rv="geri-alindi" ;;
                *)      echo "HATA|$_p|$_rr"
                        fl_log "webui: $_p -> $_v REDDEDILDI ($_rr)"
                        fl_cache_update_one "$_p"; exit 1 ;;
            esac ;;
        *)
            # v2.14: "Yatay zorla" = TEK pakete TUM katmanlar (letterbox +
            # MIUI mailbox + yatay kurali + ekrana-yay), her biri olculur.
            # Eski yol sadece letterbox yaziyordu; MIUI'li cihazda secim
            # ekranda hicbir sey degistirmiyordu.
            _rv=$(fl_force_apply "$_p" 2>/dev/null | cut -d'|' -f4-)
            [ -n "$_rv" ] || _rv="uygulandi"
            ;;
    esac
    fl_log "webui: $_p -> $_v ($_rv)"
    fl_cache_update_one "$_p"
    echo "OK|$_p|$_v|$_rv"
    ;;

# ------------------------------------------------------------
revert)   # revert <paket> : bu pakete uygulanmis HER SEYI aninda geri al
    _p=$2
    [ -n "$_p" ] || { echo "HATA|paket yok"; exit 1; }
    _rr=$(fl_revert_pkg "$_p" never)
    fl_cache_update_one "$_p"
    case "$_rr" in
        OK*) echo "OK|$_p|geri-alindi|dogrulandi" ;;
        *)    echo "HATA|$_p|$_rr"
              echo "cozum|am compat bu ROM'da reddediyor; 'Hepsini geri al' dene veya yeniden baslat"
              exit 1 ;;
    esac
    ;;

# ------------------------------------------------------------
fullscreen)   # fullscreen <paket> : gorevi SU ANDA tam ekran yap + dogrula
    _fp=$2
    [ -n "$_fp" ] || { echo "HATA|paket yok"; exit 1; }
    _fr=$(fl_task_fullscreen "$_fp")
    case "$_fr" in
        OK*)  echo "OK|$_fp|tam-ekran|$(printf '%s' "$_fr" | cut -d'|' -f2-)"
              fl_log "tam ekran: $_fp -> $_fr" ;;
        \?*)  echo "OK|$_fp|olculemedi|$(printf '%s' "$_fr" | cut -d'|' -f2-)"
              fl_log "tam ekran: $_fp -> $_fr" ;;
        *)    echo "HATA|$_fp|$(printf '%s' "$_fr" | cut -d'|' -f2-)"
              fl_log "tam ekran BASARISIZ: $_fp -> $_fr"
              exit 1 ;;
    esac
    ;;

# ------------------------------------------------------------
watch)   # izleyici ac/kapa/durum
    case "$2" in
        acik|on|1)
            cfg_set WATCH_FULLSCREEN true
            if [ "$(fl_watch_on)" = "acik" ]; then
                echo "OK|izleyici|zaten-acik"
            else
                if command -v setsid >/dev/null 2>&1; then
                    setsid "$MODDIR/watch.sh" >/dev/null 2>&1 < /dev/null &
                else
                    ( trap "" HUP; "$MODDIR/watch.sh" >/dev/null 2>&1 < /dev/null & )
                fi
                # v2.12.1: baslama yarisina karsi EN FAZLA 10 sn bekle
                _i=0
                while [ "$_i" -lt 20 ]; do
                    [ "$(fl_watch_on)" = "acik" ] && break
                    sleep 0.5 2>/dev/null || sleep 1
                    _i=$((_i + 1))
                done
                _ws=$(fl_watch_on)
                if [ "$_ws" = "acik" ]; then
                    echo "OK|izleyici|acik|$_ws"
                else
                    echo "HATA|izleyici baslamadi (watch.pid yok)"; exit 1
                fi
            fi ;;
        kapali|off|0)
            cfg_set WATCH_FULLSCREEN false
            _wp=$(cat "$FL_DIR/watch.pid" 2>/dev/null)
            # v2.14: PID dogrulanmadan kill edilmez (PID baska surece
            # verilmis olabilir) ve oldugu DOGrulanmadan 'kapali' denmez.
            if [ -n "$_wp" ] && [ -r "/proc/$_wp/cmdline" ] && tr '\0' ' ' < "/proc/$_wp/cmdline" 2>/dev/null | grep -q "watch.sh"; then
                kill "$_wp" 2>/dev/null
            fi
            rm -f "$FL_DIR/watch.pid" 2>/dev/null
            if [ "$(fl_watch_on)" = "acik" ]; then
                echo "HATA|izleyici hala calisiyor"; exit 1
            fi
            echo "OK|izleyici|kapali" ;;
        *)  echo "durum|$(fl_watch_on)" ;;
    esac
    ;;

# ------------------------------------------------------------
kilit)   # kilit acik|kapali : cihaz geneli "her seyi yatay yap" kilidi
    case "$2" in
        acik|on|1)
            cfg_set IGNORE_ORIENTATION true
            wm set-ignore-orientation-request -d 0 true >/dev/null 2>&1
            echo "OK|kilit|acik|$(fl_rotation_lock_on)"
            fl_log "genel yatay kilidi: ACIK (tum uygulamalar yatay)" ;;
        kapali|off|0)
            cfg_set IGNORE_ORIENTATION false
            wm set-ignore-orientation-request -d 0 false >/dev/null 2>&1
            echo "OK|kilit|kapali|$(fl_rotation_lock_on)"
            fl_log "genel yatay kilidi: KAPALI (uygulamalar kendi yonunu secer)" ;;
        *)  echo "durum|$(fl_rotation_lock_on)" ;;
    esac
    ;;

# ------------------------------------------------------------
revert-all)   # her seyi geri al + dogrula (tek dugme)
    fl_log "revert-all: tum katmanlar geri aliniyor"
    _hata=""; _n=0
    # v2.14: FL_MB_LIST de taranir. Eskiden yalnizca Force liste/done/
    # ekrana-yay listeleri taranıyordu; sadece mailbox'i degistirilmis
    # (Force'a girmemis) paketlerin kural satirlari sistemde kaliyordu.
    for _p in $(cat "$FL_FORCE" "$FL_DONE" "$FL_APPMODE" "$FL_MB_LIST" 2>/dev/null | sort -u); do
        [ -n "$_p" ] || continue
        _n=$((_n + 1))
        _r=$(fl_revert_pkg "$_p")
        case "$_r" in OK*) ;; *) _hata="$_hata,$_p" ;; esac
    done
    fl_list_clear "$FL_FORCE" 2>/dev/null
    fl_list_clear "$FL_APPMODE" 2>/dev/null
    fl_list_clear "$FL_MB_LIST" 2>/dev/null
    : > "$FL_WANT" 2>/dev/null
    _x=/data/misc/appcompat/compat_framework_overrides.xml
    # v2.14: overrides XML'i sifirdan YAZILMAZ; kurulumda yedeği alinmis
    # baslangic (.fl.bak) geri konur. Eski kod head'i silip modulden
    # once sistemde var olan override'lari da yok ediyordu.
    [ -f "${_x}.fl.bak" ] && cp -f "${_x}.fl.bak" "$_x" 2>/dev/null
    echo "temizlenen|$_n"
    if [ -n "$_hata" ]; then
        echo "HATA|kalan:${_hata#,}"
        fl_log "revert-all: kalan paketler -> ${_hata#,}"
        exit 1
    fi
    echo "OK|hepsi|dogrulandi"
    fl_log "revert-all: temiz ($_n paket)"
    ;;

# ------------------------------------------------------------
miui-embed)   # MIUI orta-pencere (activity embedding) ac/kapa/sorgula
    case "$2" in
        off|kapat|0)
            _r=$(fl_miui_embed_set off)
            case "$_r" in
                ok*) echo "OK|kapali|$(printf '%s' "$_r" | cut -d'|' -f2-)"
                     fl_log "MIUI orta-pencere: KAPATILDI ($(printf '%s' "$_r" | cut -d'|' -f2-))" ;;
                *)   echo "HATA|$(printf '%s' "$_r" | cut -d'|' -f2-)|resetprop yok veya prop kilitli"
                     fl_log "MIUI orta-pencere: kapatilamadi -> $_r" ;;
            esac
            echo "durum|$(fl_miui_embed_state)"
            ;;
        on|ac|1)
            _r=$(fl_miui_embed_set on)
            case "$_r" in
                ok*) echo "OK|acik|$(printf '%s' "$_r" | cut -d'|' -f2-)" ;;
                *)   echo "HATA|$(printf '%s' "$_r" | cut -d'|' -f2-)" ;;
            esac
            echo "durum|$(fl_miui_embed_state)"
            ;;
        *)
            echo "durum|$(fl_miui_embed_state)"
            echo "aciklama|$(fl_embed_status_line)"
            ;;
    esac
    ;;

# ------------------------------------------------------------
uygula-hepsi)   # uygula-hepsi <paket> : TEK DUGME - her katmani uygula + olc
    _p=$2
    [ -n "$_p" ] || { echo "HATA|paket yok"; exit 1; }
    _o=$(fl_uygula_hepsi "$_p" 2>/dev/null); printf '%s\n' "$_o"
    case "$(printf '%s\n' "$_o" | grep -m1 '^SONUC|')" in
        *"|CALISIYOR|"*) exit 0 ;;
        *"|KANIT-YOK|"*) exit 2 ;;
        *)               exit 1 ;;
    esac
    ;;

# ------------------------------------------------------------
kapat-hepsi)    # kapat-hepsi <paket> : TEK DUGME - her seyi geri al + dogrula
    _p=$2
    [ -n "$_p" ] || { echo "HATA|paket yok"; exit 1; }
    _o=$(fl_kapat_hepsi "$_p" 2>/dev/null); printf '%s\n' "$_o"
    case "$(printf '%s\n' "$_o" | grep -m1 '^SONUC|')" in
        *"|CALISIYOR|"*) exit 0 ;;
        *)               exit 1 ;;
    esac
    ;;

# ------------------------------------------------------------
rules-reload)   # MIUI kurallarini yeniden baslatmadan okut
    _o=$(fl_rules_reload)
    case "$_o" in
        tetiklendi*) echo "OK|kural-yenile|$_o" ;;
        *)           echo "UYARI|kural-yenile|$_o (bir kez yeniden baslat)" ;;
    esac
    ;;

# ------------------------------------------------------------
kesin)    # kesin <paket> : adim adim KANIT + tek satir HUKUM
    _p=$2
    [ -n "$_p" ] || { echo "HATA|paket yok"; exit 1; }
    # v2.14: fl_kesin eskiden IKI kez calisiyordu (biri cikis kodu icin);
    # hem yavasti hem iki olcum birbirini tutmayabiliyordu.
    _ko=$(fl_kesin "$_p" 2>/dev/null); printf '%s\n' "$_ko"
    case "$(printf '%s\n' "$_ko" | grep -m1 '^SONUC|')" in
        *"|CALISIYOR|"*) exit 0 ;;
        *"|KANIT-YOK|"*) exit 2 ;;
        *)               exit 1 ;;
    esac
    ;;

# ------------------------------------------------------------
mailbox)  # mailbox <paket> <ac|kapat|durum>  -> MIUI ortadaki sutunu
    _p=$2; _v=$3
    [ -n "$_p" ] || { echo "HATA|paket yok"; exit 1; }
    # v2.14: buton artik ADININ dedigi seyi yapar ve SADECE mailbox
    # satirina dokunur. Eskiden 'ac' tum MIUI katmanini (miui_apply:
    # mailbox + yatay kural) calistiriyordu; 'kapat' ise fl_miui_clear
    # ile YATAY kuralini da siliyordu -> butonlar birbirinin isini bozuyordu.
    case "$_v" in
        ac|on|1)
            _r=$(fl_mb_write2 "$_p" true 2>/dev/null)
            case "$_r" in
                ok*)  fl_mb_save "$_p"
                      _mbst=$(fl_mb_state "$_p")
                      [ "$_mbst" = "kapali" ] || { echo "HATA|mailbox yazildi ama durum=$_mbst"; exit 1; }
                      echo "OK|mailbox|ac|$_mbst|$(printf '%s' "$_r" | cut -d'|' -f2-)"
                      fl_log "webui: mailbox AC $_p -> kapali (sutun kaldirildi)" ;;
                *)    echo "HATA|$(printf '%s' "$_r" | cut -d'|' -f2-)"; exit 1 ;;
            esac
            ;;
        kapat|off|0)
            # disable="false" = MIUI varsayilani acikca yazilir
            _r=$(fl_mb_write2 "$_p" false 2>/dev/null)
            case "$_r" in
                fail*) _r=$(fl_mb_set "$_p" false) ;;
                *)     fl_mb_unsave "$_p" ;;
            esac
            _mbst=$(fl_mb_state "$_p")
            echo "OK|mailbox|kapat|$_mbst|$(printf '%s' "$_r" | cut -d'|' -f2-)"
            fl_log "webui: mailbox KAPAT $_p -> $_mbst (yatay kuralina dokunulmadi)"
            ;;
        durum|"")     echo "durum|$(fl_mb_state "$_p")" ;;
        *)            echo "HATA|gecersiz deger: $_v (ac|kapat|durum)"; exit 1 ;;
    esac
    ;;

# ------------------------------------------------------------
verify)   # verify <paket> : TAHMIN YOK - sistemden gercek durum
    _p=$2
    [ -n "$_p" ] || { echo "HATA|paket yok"; exit 1; }
    echo "paket|$_p"
    _lb=$(fl_change_state "$COMPAT_ID" "$_p")
    echo "letterbox|$_lb|$(fl_compat_status_get "$_p")"
    # v2.13: degerin HANGI kaynaktan okundugu (am / platform_compat / xml)
    echo "letterbox_kaynak|$(fl_change_state_src "$COMPAT_ID" "$_p" | cut -d'|' -f2-)"
    for _id in $FL_AM_LIST; do
        echo "yay_$_id|$(fl_change_state "$_id" "$_p")"
        echo "yayk_$_id|$(fl_change_state_src "$_id" "$_p" | cut -d'|' -f2-)"
    done
    fl_appmode_map
    if fl_appmode_has "$_p"; then echo "yay_liste|1"; else echo "yay_liste|0"; fi
    echo "yay_durum|$(fl_appmode_state_get "$_p")"
    echo "pencere|$(fl_embed_check "$_p")"
    echo "miui_embed|$(fl_miui_embed_state)"
    echo "miui_var|$(fl_miui_var && echo 1 || echo 0)"
    # v2.11: MIUI mailbox (ortada telefon sutunu) + yatay kural
    echo "mailbox|$(fl_mb_state "$_p")"
    _mbf=$(fl_mb_cloud); [ -n "$_mbf" ] || _mbf=$(fl_mb_rom)
    [ -n "$_mbf" ] || _mbf="-"
    echo "mailbox_dosya|$_mbf"
    echo "mailbox_satir|$(fl_pkg_line "$_mbf" "$_p")"
    echo "yatay_kural|$(fl_emb_state "$_p")"
    ;;

# ------------------------------------------------------------

# ------------------------------------------------------------
proof)    # proof [paket] : MIUI kural dosyalarinin GERCEK hali + bizim satirimiz
    fl_rules_proof "$2"
    exit 0
    ;;

# ------------------------------------------------------------
softreboot)  # kurallari zorla yukle: zygote tazele (MIUI acilista okur)
    _sr=$(fl_miui_softreboot)
    case "$_sr" in
        tetiklendi*) echo "OK|softreboot|$(printf '%s' "$_sr" | cut -d'|' -f2-)"
                     fl_log "webui: softreboot -> $_sr" ;;
        *)           echo "UYARI|softreboot|$(printf '%s' "$_sr" | cut -d'|' -f2-)" ;;
    esac
    ;;

# ------------------------------------------------------------
appmode)  # appmode <paket> <on|off>   -> "ekrana yay" (komple yatay/tam ekran)
    _p=$2; _v=$3
    [ -n "$_p" ] || { echo "HATA|paket yok"; exit 1; }
    case "$_v" in
        on|1|true)
            fl_appmode_add "$_p"
            _r=$(fl_appmode_apply "$_p" 2>/dev/null)
            case "$_r" in
                ok*)
                    fl_appmode_state_set "$_p" ok "$(printf '%s' "$_r" | cut -d'|' -f2-)"
                    # uygulama acikken degisiklik gecerli olmaz -> kapat
                    am force-stop "$_p" > /dev/null 2>&1
                    fl_log "webui: ekrana-yay AC $_p -> $(printf '%s' "$_r" | cut -d'|' -f2-)"
                    fl_cache_update_one "$_p"
                    echo "OK|$_p|on|$(printf '%s' "$_r" | cut -d'|' -f2-)" ;;
                *)
                    fl_appmode_del "$_p"   # basarisizsa listede kalmasin
                    fl_appmode_state_set "$_p" fail "-"
                    fl_cache_update_one "$_p"
                    echo "HATA|$_p|$(printf '%s' "$_r" | cut -d'|' -f2-)"
                    exit 1 ;;
            esac
            ;;
        off|0|false)
            fl_appmode_del "$_p"
            _r=$(fl_appmode_clear "$_p" 2>/dev/null)
            fl_appmode_state_set "$_p" ok "geri-alindi"
            am force-stop "$_p" > /dev/null 2>&1
            fl_log "webui: ekrana-yay KAPAT $_p"
            fl_cache_update_one "$_p"
            echo "OK|$_p|off|geri-alindi"
            ;;
        *) echo "HATA|gecersiz deger: $_v (on|off)"; exit 1 ;;
    esac
    ;;

# ------------------------------------------------------------
appmode-list)  # secili paketler + son durum
    fl_appmode_map
    _n=$(fl_appmode_count)
    echo "#appmode|$_n"
    if [ "$_n" -gt 0 ] 2>/dev/null; then
        for _p in $(cat "$FL_APPMODE" 2>/dev/null); do
            [ -n "$_p" ] || continue
            echo "$_p|$(fl_appmode_state_get "$_p")"
        done
    fi
    ;;

# ------------------------------------------------------------

# ------------------------------------------------------------
mode) # mode <smart|whitelist|all|off>
    case "$2" in
        smart|whitelist|all|off) cfg_set COMPAT_MODE "$2"; fl_apps_cache_build > /dev/null 2>&1
            fl_log "webui: mod=$2"; echo "OK|$2" ;;
        *) echo "HATA|gecersiz mod: $2"; exit 1 ;;
    esac
    ;;

# ------------------------------------------------------------
rotate) # rotate <sensor|lock|off|auto>
    # v2.14: yon artik landscape.sh icindeki TEK yoldan (fl_apply_orientation)
    # uygulanir. Eski kod 'sensor' seciminde accelerometer_rotation=0 ve
    # user_rotation=0 yaziyordu: sensor KAPALI + ekran DIKEY kilitli.
    # auto da artik calisiyor (eskiden "gecersiz" hatasi veriyordu ve
    # WebUI bunu yine de basarili sayiyordu).
    case "$2" in
        sensor|lock|off|auto) : ;;
        *) echo "HATA|gecersiz: $2 (sensor|lock|off|auto)"; exit 1 ;;
    esac
    cfg_set ROTATION_MODE "$2"
    [ -f "$FL_CFG" ] && . "$FL_CFG"
    _rr=$(fl_apply_orientation "$2" 2>/dev/null)
    case "$_rr" in
        OK*) _rm=$(printf '%s' "$_rr" | cut -d'|' -f2)
             fl_log "webui: yon modu=$2 -> $_rr"
             echo "OK|$_rm|$(printf '%s' "$_rr" | cut -d'|' -f3-)" ;;
        *)   fl_log "webui: yon modu=$2 BASARISIZ: $_rr"
             echo "HATA|$_rr"; exit 1 ;;
    esac
    ;;

# ------------------------------------------------------------
apply) # apply [fast|full]
    _b=$COMPAT_BUDGET
    [ "$2" = "fast" ] && COMPAT_BUDGET=45
    FL_FIRST=1
    fl_apply > /dev/null 2>&1
    COMPAT_BUDGET=$_b
    echo "OK|uygulandi"
    echo "compat_testi|$(fl_compat_test_line)"
    echo "compat_applied|$(fl_compat_count)"
    echo "compat_kalan|$(fl_compat_left)"
    echo "compat_ozet|$(fl_compat_summary)"
    echo "compat_hatali|$(awk -F'\t' '$2=="fail" && $3!="geri-alindi"' "$FL_CSTAT" 2>/dev/null | grep -c .)"
    echo "son_hata|$(fl_compat_errors_tail 1 2>/dev/null | cut -f2- | cut -c1-180)"
    echo "duraklatildi|$([ -f /data/adb/modules/force_landscape_all/disable ] && echo 1 || echo 0)"
    ;;

# ------------------------------------------------------------
apply-one) # apply-one <paket> : tek uygulamaya TUM katmanlari uygula
    _p=$2
    [ -n "$_p" ] || { echo "HATA|paket yok"; exit 1; }
    fl_list_add "$FL_FORCE" "$_p"; fl_list_del "$FL_NEVER" "$_p"
    _r=$(fl_force_apply "$_p" 2>/dev/null)
    fl_log "webui: apply-one $_p -> $_r"
    fl_cache_update_one "$_p"
    echo "OK|$_p|force|$(printf '%s' "$_r" | cut -d'|' -f4-)"
    ;;

# ------------------------------------------------------------
stop) # stop <paket> : uygulamayi tamamen kapat (yeni ayar bir sonraki acilista gecerli)
    _p=$2
    [ -n "$_p" ] || { echo "HATA|paket yok"; exit 1; }
    am force-stop "$_p" > /dev/null 2>&1
    # v2.12.1: gercekten durdu mu? (pidof ile olc)
    _sp=""
    if command -v pidof >/dev/null 2>&1; then
        _sp=$(pidof "$_p" 2>/dev/null)
        [ -n "$_sp" ] && { am force-stop "$_p" >/dev/null 2>&1; _sp=$(pidof "$_p" 2>/dev/null); }
    fi
    if [ -n "$_sp" ]; then
        echo "UYARI|$_p|hala calisiyor (pid $_sp)"
    else
        echo "OK|$_p|durduruldu|dogrulandi"
    fi
    ;;

# ------------------------------------------------------------
repair) # repair [saniye] : kalan paketlere letterbox ayarini uygula
    _b=${2:-180}
    case "$_b" in ''|*[!0-9]*) _b=180 ;; esac
    _obal=$COMPAT_BUDGET
    COMPAT_BUDGET=$_b
    FL_FIRST=0
    fl_compat_batch > /dev/null 2>&1
    COMPAT_BUDGET=$_obal
    echo "OK|$(fl_compat_summary)"
    ;;

# ------------------------------------------------------------
errors) # son compat hatalari
    if [ -s "$FL_CERR" ]; then tail -n 20 "$FL_CERR"; else echo "(hata yok)"; fi
    ;;

# ------------------------------------------------------------
cstat) # paket<TAB>ok|fail<TAB>yontem
    [ -s "$FL_CSTAT" ] && cat "$FL_CSTAT" || echo "# kayit yok"
    ;;

# ------------------------------------------------------------
diag) # "hala dar sutun" teshisi
    echo "surum|$FL_MODVER"
    echo "mod|$COMPAT_MODE"
    echo "ozet|$(fl_compat_summary)"
    echo "test|$(fl_compat_test_line)"
    echo "ignore|$(wm get-ignore-orientation-request -d 0 2>&1)"
    echo "ekran|$(fl_cur_size)"
    echo "xml|$([ -f "$FL_XMLFLAG" ] && echo HAZIR || echo gerekmedi)"
    echo "-- odaklanan pencere --"
    dumpsys window windows 2>/dev/null | grep -m1 -i "mCurrentFocus" | cut -c1-200
    dumpsys window displays 2>/dev/null | grep -m3 -i "mAppBounds\|letterbox\|mBaseDisplayRect" | cut -c1-200
    echo "-- odaklanan activity --"
    dumpsys activity activities 2>/dev/null | grep -m4 -i "mSizeCompatModeEnabled\|letterbox\|mAppBounds\|mOrientation" | cut -c1-200
    echo "-- son hatalar --"
    fl_compat_errors_tail 5
    ;;

# ------------------------------------------------------------
export) # secimleri yaz (yedek almak icin kopyala)
    echo "# force"
    cat "$FL_FORCE" 2>/dev/null
    echo "# never"
    cat "$FL_NEVER" 2>/dev/null
    ;;

# ------------------------------------------------------------
setmany) # setmany <force|never|auto> <paket...>
    # v2.14: toplu secim de ANINDA uygulanir/geri alinir. Eskiden sadece
    # listeye yaziliyordu; "Gorunenleri zorla/birak" hicbir sey yapmiyordu.
    _v=$2; shift 2
    case "$_v" in force|never|auto) : ;; *) echo "HATA|gecersiz: $_v"; exit 1 ;; esac
    _c=0; _ok=0; _fail=""
    for _p in "$@"; do
        [ -n "$_p" ] || continue
        _c=$((_c + 1))
        case "$_v" in
            force)
                fl_list_add "$FL_FORCE" "$_p"; fl_list_del "$FL_NEVER" "$_p"
                fl_force_apply "$_p" >/dev/null 2>&1 && _ok=$((_ok + 1)) || _fail="$_fail $_p" ;;
            never)
                fl_revert_pkg "$_p" never >/dev/null 2>&1 && _ok=$((_ok + 1)) || _fail="$_fail $_p" ;;
            auto)
                fl_revert_pkg "$_p" >/dev/null 2>&1 && _ok=$((_ok + 1)) || _fail="$_fail $_p" ;;
        esac
        fl_cache_update_one "$_p"
    done
    fl_log "webui: setmany $_v ($_ok/$_c tamam${_fail:+, kalan:$_fail})"
    if [ -n "$_fail" ]; then echo "HATA|$_v|$_ok/$_c|kalan:$_fail"; exit 1; fi
    echo "OK|$_v|$_c"
    ;;

# ------------------------------------------------------------
test) # sadece compat testi
    fl_compat_queue > /dev/null 2>&1
    fl_compat_probe
    _rc=$?
    echo "SONUC|$([ $_rc -eq 0 ] && echo OK || echo HATA)"
    sed -n '1,12p' "$FL_CTEST" 2>/dev/null
    ;;

# ------------------------------------------------------------
test-ham)  # son testin ham ciktisi (yan etkisiz)
    cat "$FL_CTEST" 2>/dev/null || echo "(henuz test yapilmadi)"
    ;;

# ------------------------------------------------------------
log) # log [satir]   -> modul logunun son N satiri (tail'e bagimli DEGIL)
    _n=${2:-200}
    case "$_n" in ''|*[!0-9]*) _n=200 ;; esac
    fl_log_tail "$_n"
    ;;

# ------------------------------------------------------------
logs) # logs <kaynak> <satir>   kaynak: modul | hata | compat | sistem | test | yon
    _src=$2; _n=$3
    case "$_n" in ''|*[!0-9]*) _n=300 ;; esac
    case "$_src" in
        hata)
            if [ -s "$FL_CERR" ]; then fl_file_tail "$FL_CERR" "$_n"
            else echo "(letterbox hatasi kaydi yok — bu iyi haber)"; fi ;;
        compat)
            if [ -s "$FL_CSTAT" ]; then fl_file_tail "$FL_CSTAT" "$_n"
            else echo "(henuz paket kaydi yok)"; fi ;;
        test)
            if [ -s "$FL_CTEST" ]; then cat "$FL_CTEST"; else echo "(katman testi henuz yapilmadi)"; fi ;;
        yon)
            if [ -s "$FL_ORIENT" ]; then fl_file_tail "$FL_ORIENT" "$_n"; else echo "(yon onbellegi bos)"; fi ;;
        sistem)
            # logcat: sadece modulle ilgili satirlar
            logcat -d -t 4000 2>/dev/null \
              | grep -Ei "force_landscape|174042936|KernelSU|platform_compat|ActivityTaskManager.*rotation|CompatConfig" \
              > "$FL_DIR/.logcat_f.tmp" 2>/dev/null
            fl_file_tail "$FL_DIR/.logcat_f.tmp" "$_n"
            rm -f "$FL_DIR/.logcat_f.tmp" 2>/dev/null ;;
        *)
            fl_log_tail "$_n" ;;
    esac
    ;;

# ------------------------------------------------------------
logstat)   # log dosyalarinin durumu (log neden bos olabilir?)
    echo "log_yolu|$FL_LOG"
    echo "log_bayt|$([ -f "$FL_LOG" ] && wc -c < "$FL_LOG" 2>/dev/null || echo 0)"
    echo "log_satir|$([ -f "$FL_LOG" ] && grep -c . "$FL_LOG" 2>/dev/null || echo 0)"
    echo "log_son_yazma|$([ -f "$FL_LOG" ] && date -r "$FL_LOG" '+%Y-%m-%d %H:%M:%S' 2>/dev/null || echo -)"
    echo "log_yazilabilir|$([ -w "$FL_LOG" ] && echo 1 || echo 0)"
    echo "log_ayar|$LOG"
    echo "hata_bayt|$([ -f "$FL_CERR" ] && wc -c < "$FL_CERR" 2>/dev/null || echo 0)"
    echo "dizin|$(ls -l "$FL_DIR" 2>/dev/null | grep -c .)"
    ;;

# ------------------------------------------------------------
logsize)
    wc -c < "$FL_LOG" 2>/dev/null || echo 0
    ;;

# ------------------------------------------------------------
clearlog)
    : > "$FL_LOG" 2>/dev/null
    echo "OK|log temizlendi"
    ;;

# ------------------------------------------------------------
report)
    fl_report > /dev/null 2>&1
    [ -s "$FL_REPORT" ] && echo "OK|$FL_REPORT" || echo "HATA|rapor olusmadi"
    ;;

cat-report)
    [ -s "$FL_REPORT" ] || fl_report > /dev/null 2>&1
    cat "$FL_REPORT" 2>/dev/null
    ;;

# ------------------------------------------------------------
# Rapor + logu /sdcard/Download altina kopyalar (kolay paylasim)
save)
    _ts=$(date '+%Y%m%d-%H%M%S')
    _d=/sdcard/Download
    [ -d "$_d" ] || _d=/sdcard
    fl_report > /dev/null 2>&1
    _o="$_d/force_landscape_$_ts.log"
    {
        echo "===== TANI RAPORU ====="
        cat "$FL_REPORT" 2>/dev/null
        echo
        echo "===== TAM LOG ====="
        cat "$FL_LOG" 2>/dev/null
    } > "$_o" 2>/dev/null
    chmod 0664 "$_o" 2>/dev/null
    chown 1023:1023 "$_o" 2>/dev/null
    am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE -d "file://$_o" > /dev/null 2>&1
    if [ -s "$_o" ]; then
        echo "OK|$_o|$(wc -c < "$_o")"
    else
        echo "HATA|yazilamadi"
    fi
    ;;

# ------------------------------------------------------------
restore)
    fl_restore > /dev/null 2>&1
    rm -f "$FL_FORCE" "$FL_NEVER" "$FL_TARGETS" 2>/dev/null
    echo "OK|tum ayarlar geri alindi"
    ;;

# ------------------------------------------------------------
reboot)
    echo "OK|yeniden baslatiliyor"
    ( sleep 1; reboot ) > /dev/null 2>&1 &
    ;;

# ------------------------------------------------------------
pause)   # modulu gecici devre disi birak (acilista hicbir sey yapmaz)
    # v2.11.2: MODDIR sabit yaziliyordu + izleyici durdurulmuyordu + sonuc
    # dogrulanmiyordu. Artik: dogru dizin, izleyici kapatilir, DOGRULANIR.
    _pd="$(fl_moddir)/disable"
    touch "$_pd" 2>/dev/null
    _wp=$(cat "$FL_DIR/watch.pid" 2>/dev/null)
    [ -n "$_wp" ] && kill "$_wp" 2>/dev/null
    rm -f "$FL_DIR/watch.pid" 2>/dev/null
    fl_restore > /dev/null 2>&1
    if [ -f "$_pd" ]; then
        echo "OK|modul|duraklatildi|$_pd|yeniden baslatin"
        fl_log "webui: modul duraklatildi (disable dosyasi + izleyici durduruldu)"
    else
        echo "HATA|disable dosyasi olusturulamadi: $_pd"; exit 1
    fi
    ;;

# ------------------------------------------------------------
resume)
    _pd="$(fl_moddir)/disable"
    rm -f "$_pd" 2>/dev/null
    if [ -f "$_pd" ]; then
        echo "HATA|disable dosyasi silinemedi: $_pd"; exit 1
    fi
    echo "OK|modul|etkin|$([ -f "$_pd" ] && echo hayir || echo evet)|yeniden baslatin"
    ;;

# ------------------------------------------------------------
resizable) # resizable acik|kapali : "etkinlikleri yeniden boyutlandirilabilir yap"
    case "$2" in
        acik|on|1)
            cfg_set FORCE_RESIZABLE true
            settings put global force_resizable_activities 1 >/dev/null 2>&1
            _v=$(settings get global force_resizable_activities 2>/dev/null | tr -d '\r')
            case "$_v" in
                1) echo "OK|resizable|1" ;;
                *) echo "HATA|dogrulanmadi(${_v:-bos})"; exit 1 ;;
            esac ;;
        kapali|off|0)
            cfg_set FORCE_RESIZABLE false
            settings put global force_resizable_activities 0 >/dev/null 2>&1
            _v=$(settings get global force_resizable_activities 2>/dev/null | tr -d '\r')
            case "$_v" in
                0) echo "OK|resizable|0" ;;
                *) echo "HATA|dogrulanmadi(${_v:-bos})"; exit 1 ;;
            esac ;;
        *)  echo "durum|$(settings get global force_resizable_activities 2>/dev/null)" ;;
    esac
    ;;

# ------------------------------------------------------------
kontrol)  # TUM anahtarlar: durum + ac/kapa komutu (tek panel)
    # Bicim: <id>|<etiket>|<durum>|<ac-komutu>|<kapa-komutu>|<kapsam>
    # Kapsam: cihaz = tek dugme; uygulama = Uygulamalar sekmesinden secilir.
    _fgp=$(dumpsys activity activities 2>/dev/null | tr -d '\r' | \
           grep -m1 -E 'topResumedActivity|mResumedActivity' | \
           grep -oE '[a-zA-Z][a-zA-Z0-9_.]*/[a-zA-Z0-9_.$]+' | head -n 1 | cut -d/ -f1)
    echo "#ondeki|${_fgp:--}"
    echo "#kontrol|9"

    echo "yatay-kilit|Her şeyi yataya kilitle|$(fl_rotation_lock_on)|kilit acik|kilit kapali|cihaz"
    _ym=$(sed -n 's/^ROTATION_MODE=//p' "$FL_CFG" 2>/dev/null | head -n 1)
    [ -n "$_ym" ] || _ym="${ROTATION_MODE:-auto}"
    case "$FL_MODE" in ""|unknown|"") : ;; *) _ym="$FL_MODE" ;; esac
    echo "yon-modu|Yön modu|$_ym|rotate sensor|rotate off|cihaz"

    _cm=$(sed -n 's/^COMPAT_MODE=//p' "$FL_CFG" 2>/dev/null | head -n 1)
    [ -n "$_cm" ] || _cm="$COMPAT_MODE"
    echo "letterbox|Letterbox (resize) katmanı|$_cm|mode smart|mode off|cihaz"

    echo "resizable|Etkinlikleri boyutlandırılabilir yap|$(settings get global force_resizable_activities 2>/dev/null)|resizable acik|resizable kapali|cihaz"
    echo "orta-pencere|MIUI orta pencere (prop)|$(fl_miui_embed_state)|miui-embed off|miui-embed on|cihaz"
    echo "izleyici|Tam ekran izleyicisi|$(fl_watch_on)|watch acik|watch kapali|cihaz"

    _amn=$(fl_appmode_count)
    echo "ekrana-yay|Ekrana yay (seçili uygulama)|${_amn} paket|-|-|uygulama"
    _mbn=$(grep -c . "$FL_MB_LIST" 2>/dev/null)
    case "$_mbn" in ''|*[!0-9]*) _mbn=0 ;; esac
    echo "mailbox|MIUI mailbox kapalı (seçili uygulama)|${_mbn} paket|mailbox ${_fgp:-PAKET} ac|mailbox ${_fgp:-PAKET} kapat|uygulama"

    if [ -f "$(fl_moddir)/disable" ]; then _ms="duraklatildi"; else _ms="etkin"; fi
    echo "modul|Modülün kendisi|$_ms|resume|pause|cihaz"
    ;;

# ------------------------------------------------------------
tamkontrol)  # TAM KONTROL: her yetenek + ondeki uygulama icin KANIT + hukum
    _f=$(dumpsys activity activities 2>/dev/null | tr -d '\r' | \
         grep -m1 -E 'topResumedActivity|mResumedActivity' | \
         grep -oE '[a-zA-Z][a-zA-Z0-9_.]*/[a-zA-Z0-9_.$]+' | head -n 1 | cut -d/ -f1)
    echo "ONDEKI|${_f:--}"
    echo "--- anahtarlar ---"
    sh "$0" kontrol 2>/dev/null | grep -v '^#'
    echo "--- tani ---"
    sh "$0" tani 2>/dev/null
    if [ -n "$_f" ]; then
        echo "--- kesin: $_f ---"
        fl_kesin "$_f" 2>/dev/null
    else
        echo "SONUC|KANIT-YOK|onde uygulama yok"
    fi
    ;;


# ------------------------------------------------------------
sysinfo)   # cihaz bilgileri (canli, tek cagri)
    fl_sysinfo
    ;;

# ------------------------------------------------------------
appinfo) # appinfo <paket>
    _p=$2
    [ -n "$_p" ] || { echo "HATA|paket yok"; exit 1; }
    fl_appinfo "$_p"
    ;;

# ------------------------------------------------------------
app-start|app-stop|app-restart) # uygulamayi ac / durdur / yeniden baslat
    _p=$2
    [ -n "$_p" ] || { echo "HATA|paket yok"; exit 1; }
    case "$1" in
        app-start)   fl_app_start   "$_p" ;;
        app-stop)    fl_app_stop    "$_p" ;;
        app-restart) fl_app_restart "$_p" ;;
    esac
    sleep 1
    _pid=$(pidof "$_p" 2>/dev/null | tr ' ' ',')
    case "$_pid" in ''|' ') echo "OK|$_p|kapali|" ;; *) echo "OK|$_p|calisiyor|$_pid" ;; esac
    ;;

# ------------------------------------------------------------
apply-chunk) # apply-chunk <paket-sayisi> [ilk|devam]
    _c=$2
    case "$_c" in ''|*[!0-9]*) _c=8 ;; esac
    [ "$_c" -lt 1 ] && _c=1
    FL_CHUNK_LIMIT=$_c
    FL_FAST=1
    FL_FIRST=1
    case "$3" in devam) FL_SKIP_ORIENT=1 ;; *) FL_SKIP_ORIENT=0 ;; esac
    fl_apply > /dev/null 2>&1
    fl_progress_read
    echo "devam|${CHUNK_MORE:-0}"
    echo "kalan|$(fl_compat_left)"
    echo "uygulanan|$(fl_compat_count)"
    ;;

# ------------------------------------------------------------
apply-parallel)  # eski adiyla uyumluluk (tek parca, buyuk)
    FL_CHUNK_LIMIT=0
    fl_apply > /dev/null 2>&1
    fl_progress_read
    echo "devam|0"
    ;;

# ------------------------------------------------------------
progress)
    fl_progress_read
    echo "kalan|$(fl_compat_left)"
    echo "uygulanan|$(fl_compat_count)"
    echo "hatali|$(awk -F'\t' '$2=="fail" && $3!="geri-alindi"' "$FL_CSTAT" 2>/dev/null | grep -c .)"
    ;;

# ------------------------------------------------------------
tani)   # yapilandirilmis tani: her satir KONTROL|OK|HATA|<aciklama>
    id -u >/dev/null 2>&1 || { echo "KONTROL|HATA|root|id calismadi"; }
    echo "KONTROL|OK|root|uid=$(id -u 2>/dev/null)"
    command -v pm >/dev/null 2>&1 && echo "KONTROL|OK|pm|$(command -v pm)" || echo "KONTROL|HATA|pm|pm bulunamadi"
    command -v wm >/dev/null 2>&1 && echo "KONTROL|OK|wm|$(command -v wm)" || echo "KONTROL|HATA|wm|wm bulunamadi"
    _np=$(pm list packages -3 2>/dev/null | grep -c .)
    if [ "${_np:-0}" -gt 0 ]; then echo "KONTROL|OK|kullanici_paketleri|$_np paket"
    else echo "KONTROL|HATA|kullanici_paketleri|pm list packages -3 bos dondu"; fi
    if [ -s "$FL_APPS" ]; then echo "KONTROL|OK|onbellek|$(grep -vc '^#' "$FL_APPS" 2>/dev/null) satir, $(fl_apps_cache_age) s once"
    else echo "KONTROL|HATA|onbellek|apps_cache.txt yok (apps-refresh calistir)"; fi
    if [ -s "$FL_META" ]; then echo "KONTROL|OK|paket_meta|$(grep -c . "$FL_META" 2>/dev/null) paket"
    else echo "UYARI|paket_meta|surum/boyut bilgisi yok (dumpsys package okunamadi)"; fi
    _wr=$(wm get-ignore-orientation-request -d 0 2>&1)
    case "$_wr" in *true*) echo "KONTROL|OK|ignore-orientation|$_wr" ;; *) echo "KONTROL|HATA|ignore-orientation|$_wr" ;; esac
    _fr=$(settings get global force_resizable_activities 2>&1)
    case "$_fr" in 1) echo "KONTROL|OK|force_resizable|1" ;; *) echo "UYARI|force_resizable|$_fr" ;; esac
    _sz=$(fl_cur_size 2>/dev/null)
    if [ -n "$_sz" ]; then echo "KONTROL|OK|ekran|$_sz"; else echo "KONTROL|HATA|ekran|dumpsys window okunamadi"; fi
    _ct=$(fl_compat_test_line 2>/dev/null)
    case "$_ct" in *CALISIYOR*) echo "KONTROL|OK|compat|$_ct" ;; *) echo "UYARI|compat|$_ct" ;; esac
    _ap=$(fl_compat_count); _kl=$(fl_compat_left)
    if [ "$_ap" -gt 0 ]; then echo "KONTROL|OK|letterbox|uygulanan=$_ap kalan=$_kl"
    else echo "UYARI|letterbox|hic uygulanmadi (kalan=$_kl)"; fi
    if [ -s "$FL_CERR" ]; then echo "UYARI|letterbox_hatalari|$(grep -c . "$FL_CERR" 2>/dev/null) kayit (Log > Hatalar)"; fi
    # v2.8: MIUI orta-pencere (en sik "ortada kaldi" sebebi)
    if fl_miui_var; then
        case "$(fl_miui_embed_state)" in
            acik)   echo "UYARI|miui_orta_pencere|ACIK - uygulamalar ekranin ortasinda kalir (Ayarlar > MIUI orta pencere: kapat)" ;;
            kapali) echo "KONTROL|OK|miui_orta_pencere|kapali (tam ekran yolu acik)" ;;
            *)      echo "UYARI|miui_orta_pencere|prop okunamadi" ;;
        esac
    fi
    # v2.11: MIUI mailbox (ortada telefon sutunu) katmani
    _mbr=$(fl_mb_rom); _mbc=$(fl_mb_cloud)
    if [ -n "$_mbr" ] || [ -n "$_mbc" ]; then
        echo "KONTROL|OK|mailbox_dosya|ROM:${_mbr:--} CLOUD:${_mbc:--}"
    else
        echo "UYARI|mailbox_dosya|MIUI kural dosyasi bulunamadi (mailbox devre disi)"
    fi
    _fgp=$(dumpsys activity activities 2>/dev/null | tr -d '\r' | \
           grep -m1 -E 'topResumedActivity|mResumedActivity' | \
           grep -oE '[a-zA-Z][a-zA-Z0-9_.]*/[a-zA-Z0-9_.$]+' | head -n 1 | cut -d/ -f1)
    if [ -n "$_fgp" ]; then
        case "$(fl_mb_state "$_fgp")" in
            kapali)   echo "KONTROL|OK|mailbox_ondeki|$_fgp -> kapali" ;;
            aktif|varsayilan)
                      echo "UYARI|mailbox_ondeki|$_fgp -> ACIK (uygulama ortada kalir; ekrana yay)" ;;
            *)        echo "UYARI|mailbox_ondeki|$_fgp -> kural dosyasi yok" ;;
        esac
    fi
    # v2.7.1: ekrana-yay katmani
    _amn=$(fl_appmode_count)
    if [ "${_amn:-0}" -gt 0 ] 2>/dev/null; then
        _amok=0
        for _p in $(cat "$FL_APPMODE" 2>/dev/null); do
            [ "$(fl_am_state "$FL_AM_GATE" "$_p")" = "on" ] && _amok=$((_amok + 1))
        done
        if [ "$_amok" = "$_amn" ]; then echo "KONTROL|OK|ekrana-yay|$_amok/$_amn paket etkin"
        else echo "UYARI|ekrana-yay|$_amok/$_amn paket etkin (kalan icin Uygula)"; fi
    else
        echo "KONTROL|OK|ekrana-yay|secili paket yok"
    fi
    if [ -s "$FL_LOG" ]; then echo "KONTROL|OK|log|$(wc -c < "$FL_LOG" 2>/dev/null) bayt"
    else echo "UYARI|log|log dosyasi bos"; fi
    for _f in "$FL_APPS" "$FL_CSTAT" "$FL_CERR" "$FL_STATE" "$FL_LOG"; do
        [ -f "$_f" ] || continue
        _pm=$(stat -c '%a' "$_f" 2>/dev/null)
        case "$_pm" in 6??|7??) : ;; *) echo "UYARI|izin|$_f izin=$_pm (0644 olmali)"; chmod 0644 "$_f" 2>/dev/null ;; esac
    done
    echo "KONTROL|OK|surum|$FL_MODVER"
    ;;

# ------------------------------------------------------------
backup)   # secimleri JSON-ish duz metin olarak ver (disa aktar)
    echo "#FORCE_LANDSCAPE_BACKUP|$FL_MODVER|$(date '+%Y-%m-%d %H:%M:%S')"
    echo "#mod|$COMPAT_MODE"
    echo "#yon|$ROTATION_MODE"
    [ -f "$FL_FORCE" ] && sed 's/^/force|/' "$FL_FORCE" 2>/dev/null
    [ -f "$FL_NEVER" ] && sed 's/^/never|/' "$FL_NEVER" 2>/dev/null
    [ -f "$FL_APPMODE" ] && sed 's/^/appmode|/' "$FL_APPMODE" 2>/dev/null
    [ -f "$FL_MB_LIST" ] && sed 's/^/mailbox|/' "$FL_MB_LIST" 2>/dev/null
    ;;

restore-backup)   # stdin'den yedek okur + hepsini UYGULAR
    _in=$(cat 2>/dev/null)
    # v2.14: eski kod listeleri yazip hicbir sey uygulamiyordu
    # (force pakete hicbir katman gitmiyordu). Artik her paket tipine
    # gore gercekten uygulanir; yon ayari da aninda gecer.
    _f=$(printf '%s\n' "$_in" | awk -F'|' '$1=="force"&&$2!=""{print $2}')
    _n=$(printf '%s\n' "$_in" | awk -F'|' '$1=="never"&&$2!=""{print $2}')
    _a=$(printf '%s\n' "$_in" | awk -F'|' '$1=="appmode"&&$2!=""{print $2}')
    _b=$(printf '%s\n' "$_in" | awk -F'|' '$1=="mailbox"&&$2!=""{print $2}')
    _md=$(printf '%s\n' "$_in" | awk -F'|' '$1=="#mod"{print $2; exit}')
    _yn=$(printf '%s\n' "$_in" | awk -F'|' '$1=="#yon"{print $2; exit}')
    : > "$FL_FORCE" 2>/dev/null
    : > "$FL_NEVER" 2>/dev/null
    : > "$FL_APPMODE" 2>/dev/null
    for _v in $_f; do fl_list_add "$FL_FORCE" "$_v"; done
    for _v in $_n; do fl_list_add "$FL_NEVER" "$_v"; done
    for _v in $_a; do fl_list_add "$FL_APPMODE" "$_v"; done
    [ -n "$_md" ] && cfg_set COMPAT_MODE "$_md"
    [ -n "$_yn" ] && cfg_set ROTATION_MODE "$_yn"
    [ -f "$FL_CFG" ] && . "$FL_CFG"
    for _v in $_n; do fl_revert_pkg "$_v" never >/dev/null 2>&1; done
    for _v in $_f; do fl_force_apply "$_v" >/dev/null 2>&1; done
    for _v in $_b; do
        fl_list_has "$FL_FORCE" "$_v" || fl_mb_save "$_v"
        fl_mb_write2 "$_v" true >/dev/null 2>&1 && fl_mb_save "$_v"
    done
    fl_apps_cache_build > /dev/null 2>&1
    [ -n "$_yn" ] && fl_apply_orientation "$_yn" >/dev/null 2>&1
    echo "OK|$(fl_count "$FL_FORCE")|$(fl_count "$FL_NEVER")|$(fl_appmode_count)"
    ;;

# ------------------------------------------------------------
*)
    echo "Force Landscape $FL_MODVER webui.sh"
    echo "komutlar: ping status status-full sysinfo apps apps-refresh appinfo"
    echo "          set setmany mode rotate apply apply-chunk progress"
    echo "          apply-one app-start app-stop app-restart stop repair"
    echo "          test test-ham errors cstat diag tani export log logs logstat"
    echo "          logsize clearlog report cat-report save restore reboot"
    echo "          pause resume backup restore-backup appmode appmode-list"
    echo "          verify revert revert-all miui-embed fullscreen watch"
    ;;
esac
exit 0
