#!/usr/bin/env python3
"""prebuilt/*.cs3 dosyalarini builds calisma alanina SURUM KARSILASTIRMALI kopyalar.

Neden gerekli:
  Derleyici.yml eskiden `cp prebuilt/*.cs3` ile kosulsuz kopyaliyordu. Bir
  commit'te eklenti dizini degismediyse derleme yapilmiyor; bu durumda
  builds/ altindaki TAZE .cs3 dosyasi, prebuilt/ altindaki ESKI surumle
  eziliyordu. Sonuc: yayindaki surum geriye dusuyordu (or. AnimeciX 23 -> 21).
  Bu betik yalnizca kaynak surum hedeftekinden BUYUKSE (ya da hedef yoksa)
  kopyalar; boylece eski prebuilt dosyasi taze derlemeyi ezemez.

Kullanim:
  python3 tools/stage_prebuilt.py prebuilt "$GITHUB_WORKSPACE/builds"
"""

import json
import os
import shutil
import sys
import zipfile


def cs3_version(path):
    """.cs3 (zip) icindeki manifest.json surumunu dondurur; okunamazsa -1."""
    try:
        with zipfile.ZipFile(path) as zf:
            with zf.open("manifest.json") as fh:
                meta = json.load(fh)
        version = meta.get("version")
        return version if isinstance(version, int) else -1
    except Exception:
        return -1


def copy_if_newer(src, dst):
    """src'yi dst'ye kopyalar; dst daha yeni surum tasiyorsa dokunmaz."""
    if not os.path.exists(dst):
        shutil.copy2(src, dst)
        print(f"  + {os.path.basename(dst)} eklendi (surum {cs3_version(src)})")
        return True

    src_ver = cs3_version(src)
    dst_ver = cs3_version(dst)
    if src_ver > dst_ver:
        shutil.copy2(src, dst)
        print(f"  ^ {os.path.basename(dst)} guncellendi ({dst_ver} -> {src_ver})")
        return True

    if src_ver < dst_ver:
        print(
            f"  = {os.path.basename(dst)} korundu: builds surumu {dst_ver}, "
            f"prebuilt surumu {src_ver} (geriye dusurulmedi)"
        )
    return False


def main():
    if len(sys.argv) != 3:
        print(__doc__)
        return 2

    src_dir = sys.argv[1]
    builds_root = sys.argv[2]

    if not os.path.isdir(src_dir):
        print(f"  ! prebuilt dizini yok: {src_dir}")
        return 0
    if not os.path.isdir(builds_root):
        print(f"  ! builds dizini yok: {builds_root}")
        return 1

    targets = [builds_root, os.path.join(builds_root, "prebuilt")]
    for target in targets:
        os.makedirs(target, exist_ok=True)

    staged = 0
    for name in sorted(os.listdir(src_dir)):
        if not name.endswith(".cs3"):
            continue
        src = os.path.join(src_dir, name)
        if not os.path.isfile(src):
            continue
        for target in targets:
            if copy_if_newer(src, os.path.join(target, name)):
                staged += 1

    print(f"  prebuilt staging tamam: {staged} dosya kopyalandi/guncellendi")
    return 0


if __name__ == "__main__":
    sys.exit(main())
