#!/usr/bin/env python3
"""Proxy bulunamazsa VPN Gate (akademik gonullu VPN agi) uzerinden TR cikisi dener (gecici)."""
import base64
import csv
import io
import os
import subprocess
import sys
import time

import requests

OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "out")
os.makedirs(OUT, exist_ok=True)

EXTRA = """
data-ciphers AES-256-GCM:AES-128-GCM:AES-128-CBC:AES-256-CBC
data-ciphers-fallback AES-128-CBC
tls-cipher DEFAULT:@SECLEVEL=0
connect-retry-max 1
"""


def geo_ok():
    try:
        r = requests.get("https://filmmakinesi.to/", timeout=20,
                         headers={"User-Agent": "Mozilla/5.0", "Accept-Language": "tr-TR"})
        low = r.text.lower()
        return r.status_code == 200 and "unavailable in your region" not in low and "filmmakinesi" in low
    except Exception as exc:
        print("geo test hata:", exc)
        return False


def main():
    try:
        txt = requests.get("http://www.vpngate.net/api/iphone/", timeout=60).text
    except Exception as exc:
        print("vpngate liste alinamadi:", exc)
        return 1
    lines = [l for l in txt.splitlines() if l and not l.startswith("*")]
    if lines and lines[0].startswith("#"):
        lines[0] = lines[0][1:]
    rows = list(csv.DictReader(io.StringIO("\n".join(lines))))
    tr = [r for r in rows if r.get("CountryShort") == "TR" and r.get("OpenVPN_ConfigData_Base64")]
    print(f"vpngate toplam={len(rows)} TR={len(tr)}")
    tr.sort(key=lambda r: int(r.get("Score") or 0), reverse=True)
    for idx, row in enumerate(tr[:6]):
        cfg = base64.b64decode(row["OpenVPN_ConfigData_Base64"]).decode("utf-8", "replace")
        path = f"/tmp/vpn_{idx}.ovpn"
        with open(path, "w") as fh:
            fh.write(cfg + EXTRA)
        print(f"deneniyor: {row.get('HostName')} {row.get('IP')} score={row.get('Score')}")
        proc = subprocess.Popen(["sudo", "openvpn", "--config", path, "--log", f"/tmp/vpn_{idx}.log", "--daemon"])
        proc.wait()
        for _ in range(25):
            time.sleep(2)
            if subprocess.run(["ip", "link", "show", "tun0"], capture_output=True).returncode == 0:
                break
        time.sleep(4)
        if geo_ok():
            print("VPN TR cikisi CALISIYOR")
            with open(os.environ.get("GITHUB_ENV", "/dev/null"), "a") as fh:
                fh.write("PROBE_MODE=direct\n")
            return 0
        print(open(f"/tmp/vpn_{idx}.log").read()[-1500:])
        subprocess.run(["sudo", "killall", "openvpn"])
        time.sleep(2)
    return 1


if __name__ == "__main__":
    sys.exit(main())
