#!/usr/bin/env python3
"""Kotlin cozucusu sonuclarini gercek tarayici JS'i (Node) ile karsilastirir ve
Kotlin'in buldugu her yayini eklentinin gonderecegi basliklarla dogrular."""
import json
import os
import urllib.parse

import crawl

OUT2 = os.path.join(crawl.HERE, "out2")
log = crawl.log


def origin_of(url):
    u = urllib.parse.urlsplit(url)
    return f"{u.scheme}://{u.netloc}"


def node_file(res):
    cap = res.get("captured") or {}
    for s in cap.get("setups", []):
        if isinstance(s, dict):
            for x in s.get("sources") or []:
                if isinstance(x, dict) and x.get("file"):
                    return x["file"]
    return None


def main():
    kres = {r["label"]: r for r in json.load(open(os.path.join(OUT2, "kotlin_results.json")))}
    try:
        nres = {r["label"]: r for r in json.load(open(os.path.join(OUT2, "eval_results.json"))) if "label" in r}
    except Exception:
        nres = {}
    rows = []
    same = diff = knull = 0
    for label, k in kres.items():
        n = node_file(nres.get(label, {})) if label in nres else None
        ok = (k["file"] is not None and n is not None and k["file"] == n)
        if k["file"] is None:
            knull += 1
        elif ok:
            same += 1
        else:
            diff += 1
        rows.append({"label": label, "kotlin": k["file"], "node": n, "same": ok,
                     "fallback_same": k["fallback"] == k["file"], "ms": k["ms"]})
    # Yayin dogrulamasi: eklentinin basliklari (Referer = oynatici sayfasi, Origin = kok)
    streams = []
    seen = set()
    for label, k in kres.items():
        f = k["file"]
        if not f or f in seen:
            continue
        seen.add(f)
        hdrs = {"Referer": k["url"], "Origin": origin_of(k["url"]), "Accept": "*/*"}
        r = crawl.fetch(f, headers=hdrs, label=f" kstream[{label}]", timeout=25, stream_peek=True, rounds=2)
        item = {"label": label, "file": f, "status": r.status_code if r is not None else None,
                "m3u8": bool(r is not None and r.text.lstrip().startswith("#EXTM3U"))}
        if item["m3u8"]:
            lines = [l.strip() for l in r.text.splitlines() if l.strip() and not l.startswith("#")]
            if lines:
                v = urllib.parse.urljoin(r.url, lines[0])
                vr = crawl.fetch(v, headers=hdrs, label=f" kvariant[{label}]", timeout=25, stream_peek=True, rounds=2)
                item["first_child"] = v[:160]
                item["child_status"] = vr.status_code if vr is not None else None
                if vr is not None and vr.text.lstrip().startswith("#EXTM3U"):
                    segs = [l.strip() for l in vr.text.splitlines() if l.strip() and not l.startswith("#")]
                    if segs:
                        sg = urllib.parse.urljoin(vr.url, segs[0])
                        sr = crawl.fetch(sg, headers=hdrs, label=f" kseg[{label}]", timeout=25, stream_peek=True, rounds=2)
                        item["segment_status"] = sr.status_code if sr is not None else None
                        item["segment_magic"] = sr.content[:4].hex() if sr is not None else None
                elif vr is not None:
                    item["segment_magic"] = vr.content[:4].hex()
        streams.append(item)
    summary = {
        "samples": len(kres), "kotlin_eq_node": same, "kotlin_ne_node": diff, "kotlin_null": knull,
        "fallback_eq_targeted": sum(1 for r in rows if r["fallback_same"]),
        "streams_tested": len(streams), "streams_ok": sum(1 for s in streams if s["m3u8"]),
        "max_ms": max([r["ms"] for r in rows] or [0]),
    }
    json.dump({"summary": summary, "rows": rows, "streams": streams},
              open(os.path.join(OUT2, "kotlin_check.json"), "w"), indent=1)
    log("[KCHECK] " + json.dumps(summary))
    with open(os.path.join(OUT2, "_kotlin_summary.txt"), "w") as fh:
        fh.write(json.dumps(summary, indent=1) + "\n")
        for r in rows:
            fh.write(f"{'OK ' if r['same'] else 'XX '} {r['label']}  kotlin={r['kotlin']}\n                node={r['node']}\n")
        for s in streams:
            fh.write(f"STREAM {s['status']} m3u8={s['m3u8']} child={s.get('child_status')} seg={s.get('segment_status')} "
                     f"magic={s.get('segment_magic')} {s['file'][:120]}\n")


if __name__ == "__main__":
    main()
