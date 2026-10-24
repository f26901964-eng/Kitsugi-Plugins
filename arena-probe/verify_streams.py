#!/usr/bin/env python3
"""Cozulen yayin adreslerini (m3u8/master.txt) farkli basliklarla dener (gecici).

Amac: oynaticinin hangi Referer/Origin ile 200 + #EXTM3U aldigini kanitlamak.
"""
import json
import os
import re
import urllib.parse

import crawl  # proxy havuzu ve fetch() ortak

OUT = crawl.OUT
log = crawl.log


def origin_of(url):
    u = urllib.parse.urlsplit(url)
    return f"{u.scheme}://{u.netloc}"


def files_from(result):
    files = []
    cap = result.get("captured") or {}
    for s in cap.get("setups", []):
        if not isinstance(s, dict):
            continue
        srcs = s.get("sources") or []
        if not srcs and isinstance(s.get("playlist"), list) and s["playlist"]:
            srcs = (s["playlist"][0] or {}).get("sources") or []
        for x in srcs:
            if isinstance(x, dict) and x.get("file"):
                files.append(x["file"])
        if s.get("file"):
            files.append(s["file"])
    for h in cap.get("hls", []):
        if h.get("loadSource"):
            files.append(h["loadSource"])
    for p in cap.get("playerjs", []):
        if isinstance(p, dict) and p.get("file"):
            files.append(p["file"])
    return [f for f in dict.fromkeys(files) if isinstance(f, str)]


def try_stream(url, embed, label):
    combos = [
        ("embed-ref+origin", {"Referer": embed, "Origin": origin_of(embed), "Accept": "*/*"}),
        ("embed-origin-ref", {"Referer": origin_of(embed) + "/", "Accept": "*/*"}),
        ("no-ref", {"Accept": "*/*"}),
    ]
    out = []
    for name, hdrs in combos:
        r = crawl.fetch(url, headers=hdrs, label=f" stream[{label}:{name}]", timeout=30, stream_peek=True)
        if r is None:
            out.append({"combo": name, "status": None})
            continue
        body = r.text[:1500]
        item = {"combo": name, "status": r.status_code, "final": r.url,
                "ctype": r.headers.get("Content-Type"), "head": body[:700]}
        if body.lstrip().startswith("#EXTM3U"):
            lines = [l.strip() for l in body.splitlines() if l.strip() and not l.startswith("#")]
            if lines:
                variant = urllib.parse.urljoin(r.url, lines[0])
                vr = crawl.fetch(variant, headers=hdrs, label=f" variant[{label}:{name}]", timeout=30, stream_peek=True)
                if vr is not None:
                    vb = vr.text[:1200]
                    item["variant"] = {"url": variant, "status": vr.status_code, "ctype": vr.headers.get("Content-Type"), "head": vb[:500]}
                    seg_lines = [l.strip() for l in vb.splitlines() if l.strip() and not l.startswith("#")]
                    if seg_lines and vb.lstrip().startswith("#EXTM3U"):
                        seg = urllib.parse.urljoin(vr.url, seg_lines[0])
                        sr = crawl.fetch(seg, headers=hdrs, label=f" segment[{label}:{name}]", timeout=30, stream_peek=True)
                        if sr is not None:
                            item["segment"] = {"url": seg, "status": sr.status_code, "ctype": sr.headers.get("Content-Type"),
                                               "len_peek": len(sr.content), "magic": sr.content[:8].hex()}
        out.append(item)
    return out


def main():
    try:
        results = json.load(open(os.path.join(OUT, "eval_results.json")))
    except Exception as exc:
        log(f"eval_results yok: {exc}")
        return
    report = []
    for res in results:
        if "url" not in res:
            continue
        files = files_from(res)
        log(f"\n[STREAM] {res['label']} {res['url']} -> {files}")
        for f in files[:3]:
            absf = urllib.parse.urljoin(res["url"], f)
            report.append({"label": res["label"], "embed": res["url"], "file": absf,
                           "tests": try_stream(absf, res["url"], res["label"])})
    json.dump(report, open(os.path.join(OUT, "stream_tests.json"), "w"), indent=1)


if __name__ == "__main__":
    main()
