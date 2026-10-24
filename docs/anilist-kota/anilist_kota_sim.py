#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
AniListQuotaGate — algoritma doğrulama ve karşılaştırma simülasyonu.

Doğrulanan garantiler
---------------------
G1. Hiçbir 60 sn'lik pencerede bütçe (limit - rezerv) aşılmaz  -> 429 üretilmez.
G2. İki istek arası en az 60_000/bütçe ms boşluk olur            -> burst limiter tetiklenmez.
G3. 429 gelirse Retry-After / X-RateLimit-Reset'in öngördüğü ana kadar istek çıkmaz.
G4. Öncelik rezervi: bütçenin %60'ı dolduktan sonra PREFETCH, %40'ından sonra
    BACKGROUND isteği gönderilmez (arka plan, kullanıcının önünü kesmez).
G5. X-RateLimit-Limit başlığı öğrenilir; dokümandaki 90 varsayımı ile başlanmaz.

Kullanım: python3 anilist_kota_sim.py
"""
from __future__ import annotations

import random
from collections import deque
from dataclasses import dataclass, field
from typing import Deque, Dict, List, Optional, Tuple

MINUTE = 60_000
WINDOW = MINUTE


class Priority:
    CRITICAL = 0      # kullanıcının beklediği okuma/yazma (bildirim, liste, giriş)
    INTERACTIVE = 1   # ekranda görünen içerik
    PREFETCH = 2      # görünmeyen raflar / sonraki sayfa
    BACKGROUND = 3    # toplu eşitleme, içe aktarma, ısıtma


# --------------------------------------------------------------------------- #
# AniListQuotaGate — Kotlin dosyasının birebir portu
# --------------------------------------------------------------------------- #
class Gate:
    def __init__(self, limit: int = 30, gap_scale: float = 1.0) -> None:
        self.limit = limit
        self.gap_scale = gap_scale
        self.starts: Deque[int] = deque()
        self.next_slot_at = 0
        self.blocked_until = 0
        self.soft_blocked_until = 0
        self.last_remaining: Optional[int] = None

    # -- türetilmiş değerler ------------------------------------------------- #
    @property
    def reserve(self) -> int:
        return max(2, self.limit // 10)

    @property
    def budget(self) -> int:
        return max(2, self.limit - self.reserve)

    @property
    def min_gap_ms(self) -> int:
        # YUKARI yuvarlama kritik: 60_000/27 = 2222 ms olsaydı 28 istek
        # (0, 2222, ..., 59994) tek bir 60 sn penceresine sığardı.
        # 2223 ms ile 27 istek 60_021 ms'ye yayılır; 28. istek pencere dışıdır.
        base = -(-WINDOW // self.budget)          # ceil
        return int(base * self.gap_scale)

    # -- planlama ------------------------------------------------------------ #
    def _plan(self, now: int) -> int:
        slot = max(now, self.next_slot_at, self.blocked_until, self.soft_blocked_until)
        budget = self.budget
        # Slot'un KENDİ penceresine göre temizle (now'a göre değil!).
        for _ in range(4):
            while self.starts and self.starts[0] <= slot - WINDOW:
                self.starts.popleft()
            if len(self.starts) < budget:
                return slot
            slot = self.starts[0] + WINDOW
        return slot

    def _commit(self, slot: int) -> None:
        self.starts.append(slot)
        self.next_slot_at = slot + self.min_gap_ms

    # -- dış API ------------------------------------------------------------- #
    def acquire(self, now: int, priority: int, max_wait_ms: int) -> Tuple[bool, int, str]:
        budget = self.budget
        used = len([x for x in self.starts if x > now - WINDOW])
        if priority == Priority.PREFETCH and used >= int(budget * 0.60):
            return False, 0, "quota-reserved"
        if priority == Priority.BACKGROUND and used >= int(budget * 0.40):
            return False, 0, "quota-reserved"
        if self.blocked_until > now:
            wait = self.blocked_until - now
            return (wait <= max_wait_ms), max(wait, 0), "rate-limited"
        slot = self._plan(now)
        wait = max(0, slot - now)
        if wait > max_wait_ms:
            return False, wait, "wait-too-long"
        self._commit(slot)
        return True, wait, "ok"

    def on_headers(self, now: int, limit: Optional[int], remaining: Optional[int],
                   reset_epoch_s: Optional[int]) -> None:
        if limit is not None and 1 <= limit <= 1000:
            self.limit = limit
        self.last_remaining = remaining
        if remaining is not None and remaining <= 1 and reset_epoch_s:
            self.soft_blocked_until = max(self.soft_blocked_until, reset_epoch_s * 1000)

    def on_rate_limited(self, now: int, retry_after_ms: Optional[int]) -> None:
        wait = max(1_000, min(120_000, retry_after_ms if retry_after_ms else 60_000))
        self.blocked_until = max(self.blocked_until, now + wait)

    def wait_estimate_ms(self, now: int) -> int:
        return max(0, self._plan(now) - now)


# --------------------------------------------------------------------------- #
# İş yükü üreteci
# --------------------------------------------------------------------------- #
@dataclass
class Request:
    t: int
    kind: str
    priority: int
    cache_key: Optional[str] = None


def workload(seed: int = 7, minutes: int = 10) -> List[Request]:
    """Yoğun bir oturum: soğuk açılış, detay, sekmeler, arama, toplu eşitleme."""
    rnd = random.Random(seed)
    reqs: List[Request] = []

    def add(t: int, kind: str, prio: int, n: int, key: Optional[str] = None,
            spread_ms: int = 300) -> None:
        for i in range(n):
            reqs.append(Request(t + rnd.randint(0, spread_ms), kind, prio,
                                f"{key}:{i}" if key else None))

    # Soğuk açılış patlaması: ~2 sn içinde 26 istek
    add(0, "explore-batch", Priority.INTERACTIVE, 4, "explore")
    add(100, "calendar", Priority.INTERACTIVE, 1, "calendar")
    add(200, "hero", Priority.INTERACTIVE, 2, "hero")
    add(300, "profile-stats", Priority.INTERACTIVE, 3, "profile")
    add(400, "notif-page", Priority.CRITICAL, 2, "notif")
    add(600, "my-list", Priority.INTERACTIVE, 3, "mylist")
    add(800, "explore-prefetch", Priority.PREFETCH, 6, "explore-pf")
    add(900, "mapping-warmup", Priority.BACKGROUND, 5, "warm")

    # 0:20 detay sayfası
    add(20_000, "detail", Priority.INTERACTIVE, 6, "detail1", spread_ms=1_500)
    # 0:35 bildirim sekmesi gezme (6 sekme)
    add(35_000, "notif-tabs", Priority.CRITICAL, 6, "notiftab", spread_ms=2_500)
    # 0:50 arama (debounce yok)
    add(50_000, "search", Priority.INTERACTIVE, 5, "search", spread_ms=2_000)
    # 1:10 aynı ekranı yeniden açma (önbellek isabeti beklenir)
    add(70_000, "explore-batch", Priority.INTERACTIVE, 4, "explore")
    add(71_000, "detail", Priority.INTERACTIVE, 6, "detail1", spread_ms=500)
    # Arka plan eşitleme her 2 dakikada
    for t in (120_000, 240_000, 360_000, 480_000):
        add(t, "sync", Priority.BACKGROUND, 10, f"sync{t}", spread_ms=1_000)
    # Kullanıcı gezinmeye devam ediyor
    t = 150_000
    while t < minutes * MINUTE:
        add(t, "detail", Priority.INTERACTIVE, 5, f"detail-{t}", spread_ms=2_000)
        t += rnd.randint(45_000, 110_000)

    reqs.sort(key=lambda r: r.t)
    return reqs


# --------------------------------------------------------------------------- #
# Politika A — mevcut Kitsugi-Beta davranışı
# --------------------------------------------------------------------------- #
def run_current(reqs: List[Request]) -> Dict[str, int]:
    gap = 350                      # ANILIST_MIN_INTERVAL_NORMAL_MS
    next_slot = 0
    cooldown_until = 0
    starts: Deque[int] = deque()
    sent = retries = rate_limited = user_errors = 0
    peak = 0

    for r in reqs:
        now = r.t
        while starts and now - starts[0] >= WINDOW:
            starts.popleft()
        if cooldown_until > now:
            wait = cooldown_until - now
            if wait > 6_000:            # ANILIST_SHORT_COOLDOWN_WAIT_MS
                user_errors += 1        # fail-fast: "yanıtsız kaldı"
                continue
            now = cooldown_until
            while starts and now - starts[0] >= WINDOW:
                starts.popleft()
        slot = max(now, next_slot)
        next_slot = slot + gap
        sent += 1
        starts.append(slot)
        peak = max(peak, len(starts))
        if len(starts) > 30:            # AniList: dakikada 30
            rate_limited += 1
            cooldown_until = max(cooldown_until, slot + 5_000)
            if retries < len(reqs):     # ANILIST_MAX_RETRIES = 1
                retries += 1
                sent += 1
                starts.append(slot + 5_000)
    user_errors += rate_limited
    return {
        "sent": sent, "cache": 0, "coalesced": 0, "deferred": 0,
        "rate_limited": rate_limited, "retries": retries,
        "user_errors": user_errors, "peak": peak,
        "avg_wait": 0, "max_wait": 0,
    }


# --------------------------------------------------------------------------- #
# Politika B — AniListQuotaGate + önbellek + tekilleştirme + öncelik
# --------------------------------------------------------------------------- #
def run_gate(reqs: List[Request], ttl_ms: int = 300_000,
             allow_stale: bool = False) -> Dict[str, int]:
    gate = Gate(limit=30)
    cache: Dict[str, int] = {}
    inflight: Dict[str, int] = {}
    starts: Deque[int] = deque()
    sent = cache_hits = coalesced = deferred = user_errors = rate_limited = 0
    stale_served = 0
    peak = 0
    waits: List[int] = []

    for r in reqs:
        now = r.t
        while starts and now - starts[0] >= WINDOW:
            starts.popleft()

        if r.cache_key and r.cache_key in cache and now - cache[r.cache_key] < ttl_ms:
            cache_hits += 1
            continue
        if r.cache_key and r.cache_key in inflight and now - inflight[r.cache_key] < 15_000:
            coalesced += 1
            continue

        max_wait = 45_000 if r.priority <= Priority.INTERACTIVE else 5_000
        ok, wait, reason = gate.acquire(now, r.priority, max_wait)
        if not ok:
            if reason == "quota-reserved":
                deferred += 1
            elif allow_stale and r.cache_key and r.cache_key in cache:
                stale_served += 1       # bayat önbellek gösterilir, hata yok
            elif r.priority <= Priority.INTERACTIVE:
                user_errors += 1        # yalnızca kullanıcının beklediği istek
            else:
                deferred += 1           # arka plan: sonraki tura kalır, hata değil
            continue

        at = now + wait
        if r.priority <= Priority.INTERACTIVE:
            waits.append(wait)
        sent += 1
        while starts and at - starts[0] >= WINDOW:
            starts.popleft()
        starts.append(at)
        peak = max(peak, len(starts))
        if r.cache_key:
            cache[r.cache_key] = at
            inflight[r.cache_key] = at
        if len(starts) > gate.limit:
            rate_limited += 1
            gate.on_rate_limited(at, 30_000)

    return {
        "sent": sent, "cache": cache_hits, "coalesced": coalesced, "deferred": deferred,
        "rate_limited": rate_limited, "retries": 0,
        "user_errors": user_errors, "peak": peak, "stale": stale_served,
        "avg_wait": int(sum(waits) / len(waits)) if waits else 0,
        "max_wait": max(waits) if waits else 0,
    }


# --------------------------------------------------------------------------- #
# Hedef mimari — istek sayısını düşüren birleştirmeler
#   Her satır: (gelen istek, giden istek). Gerekçeleri raporda dosya/dosya.
# --------------------------------------------------------------------------- #
MERGE: Dict[str, Tuple[int, int]] = {
    "notif-page": (2, 1),        # Page.notifications + Viewer.unreadNotificationCount tek belge
    "notif-tabs": (6, 1),        # sunucu tarafı type_in yerine ALL + istemci filtresi
    "detail": (6, 2),            # media/relations/characters/staff/recommendations alias'lı
    "search": (5, 1),            # 400 ms debounce + tek sorgu
    "mylist": (3, 1),            # MediaListCollection tek istek
    "profile-stats": (3, 1),     # User.statistics tek istek
    "explore-prefetch": (6, 0),  # görünmeyen raf önden yüklenmez
    "hero": (2, 1),              # media + banner tek istek
    "mapping-warmup": (5, 5),
    "explore-batch": (4, 4),
    "calendar": (1, 1),
    "sync": (10, 10),
}


def collapse(reqs: List[Request], burst_ms: int = 3_000) -> List[Request]:
    """Hedef mimaride aynı işi yapan istekleri BURST içinde birleştirir.

    Yalnızca aynı türden, aralarında <= burst_ms olan istekler tek sorguya
    (GraphQL alias / istemci filtresi / debounce) indirilir. Dakikalar sonraki
    yeni kullanıcı hareketleri ayrı istek olarak kalır.
    """
    ordered = sorted(reqs, key=lambda r: r.t)
    out: List[Request] = []
    i = 0
    while i < len(ordered):
        j = i
        while (j + 1 < len(ordered)
               and ordered[j + 1].kind == ordered[i].kind
               and ordered[j + 1].t - ordered[i].t <= burst_ms):
            j += 1
        group = ordered[i:j + 1]
        n_in, n_out = MERGE.get(group[0].kind, (len(group), len(group)))
        if len(group) <= n_in:
            keep = min(len(group), n_out)
        else:
            keep = max(1, round(len(group) * n_out / n_in))
        out.extend(group[:keep])
        i = j + 1
    return out


# --------------------------------------------------------------------------- #
# Testler
# --------------------------------------------------------------------------- #
def t_window(limit: int = 30, n: int = 400) -> Tuple[bool, int]:
    """G1: kayan pencere garantisi."""
    gate = Gate(limit=limit)
    t, accepted = 0, []
    while len(accepted) < n and t < 30 * MINUTE:
        ok, wait, _ = gate.acquire(t, Priority.INTERACTIVE, 120_000)
        if ok:
            accepted.append(t + wait)
        t += 50
    worst = 0
    for s in accepted:
        worst = max(worst, sum(1 for x in accepted if s <= x < s + WINDOW))
    return worst <= gate.budget, worst


def t_gap(limit: int = 30) -> Tuple[bool, int]:
    """G2: minimum aralık."""
    gate = Gate(limit=limit)
    t, prev, worst = 0, None, 10 ** 9
    for _ in range(60):
        ok, wait, _ = gate.acquire(t, Priority.CRITICAL, 300_000)
        if ok:
            at = t + wait
            if prev is not None:
                worst = min(worst, at - prev)
            prev = at
        t += 10
    return worst >= gate.min_gap_ms - 1, worst


def t_429() -> bool:
    """G3: Retry-After uygulanıyor."""
    gate = Gate(limit=30)
    gate.on_rate_limited(1_000, 45_000)
    ok, wait, reason = gate.acquire(2_000, Priority.CRITICAL, 60_000)
    return ok and wait == 44_000 and reason == "rate-limited"


def t_priority() -> bool:
    """G4: öncelik rezervi."""
    gate = Gate(limit=30)
    t = 0
    for _ in range(int(gate.budget * 0.45)):
        gate.acquire(t, Priority.CRITICAL, 300_000)
        t += 10
    ok_bg, _, why_bg = gate.acquire(t + 20_000, Priority.BACKGROUND, 60_000)
    ok_user, _, _ = gate.acquire(t + 20_000, Priority.CRITICAL, 60_000)
    return (ok_bg is False and why_bg == "quota-reserved") and ok_user is True


def t_headers() -> bool:
    """G5: limit öğrenme."""
    gate = Gate(limit=90)
    gate.on_headers(0, limit=30, remaining=29, reset_epoch_s=None)
    return gate.limit == 30 and gate.budget == 27


def main() -> None:
    print("=" * 80)
    print("AniListQuotaGate — garantiler")
    print("=" * 80)
    checks = [
        ("G1  Kayan pencere bütçeyi aşmıyor", t_window()),
        ("G2  Minimum istek aralığı korunuyor", t_gap()),
        ("G3  429 / Retry-After'a uyuluyor", t_429()),
        ("G4  Öncelik rezervi (arka plan öne geçmiyor)", t_priority()),
        ("G5  X-RateLimit-Limit öğreniliyor", t_headers()),
    ]
    passed = 0
    for name, res in checks:
        if isinstance(res, tuple):
            ok, detail = res
            extra = f"  (60 sn'de en çok {detail} istek)" if name.startswith("G1") else f"  ({detail} ms)"
        else:
            ok, extra = res, ""
        passed += 1 if ok else 0
        print(f"  [{'PASS' if ok else 'FAIL'}] {name}{extra}")
    print(f"\n  {passed}/{len(checks)} test geçti.\n")

    print("=" * 80)
    print("Aynı kullanıcı davranışı, üç mimari — 10 dakikalık yoğun oturum")
    print("  A: bugünkü Kitsugi-Beta (350 ms aralık, önbellek yok)")
    print("  B: yalnızca AniListQuotaGate (aynı istek sayısı)")
    print("  C: kapı + sorgu birleştirme + önbellek + bayat veri (hedef)")
    print("=" * 80)
    reqs = workload()
    reqs_c = collapse(reqs)
    a = run_current(reqs)
    b = run_gate(reqs)
    c = run_gate(reqs_c, allow_stale=True)
    rows = [
        ("Uygulamanın ürettiği istek", len(reqs), len(reqs), len(reqs_c)),
        ("Gerçekten gönderilen HTTP isteği", a["sent"], b["sent"], c["sent"]),
        ("Önbellekten karşılanan", a["cache"], b["cache"], c["cache"]),
        ("Bayat önbellekten karşılanan", 0, 0, c["stale"]),
        ("Uçuştaki isteğe bindirilen", a["coalesced"], b["coalesced"], c["coalesced"]),
        ("Düşük önceliği ertelenen", a["deferred"], b["deferred"], c["deferred"]),
        ("60 sn'lik pencerede en yüksek istek", a["peak"], b["peak"], c["peak"]),
        ("HTTP 429 sayısı", a["rate_limited"], b["rate_limited"], c["rate_limited"]),
        ("429 yüzünden tekrar deneme", a["retries"], b["retries"], c["retries"]),
        ("Kullanıcının gördüğü hata", a["user_errors"], b["user_errors"], c["user_errors"]),
        ("Kullanıcı isteğinin ort. beklemesi (ms)", a["avg_wait"], b["avg_wait"], c["avg_wait"]),
        ("En uzun bekleme (ms)", a["max_wait"], b["max_wait"], c["max_wait"]),
    ]
    print(f"{'Ölçüt':44}{'A: Mevcut':>12}{'B: Kapı':>12}{'C: Hedef':>12}")
    print("-" * 80)
    for label, x, y, z in rows:
        print(f"{label:44}{str(x):>12}{str(y):>12}{str(z):>12}")
    print()
    print("Okuma: A'da 429 var; her 429 sonrası uygulama 5-30 sn global donuyor.")
    print("B, 429'u sıfırlar ama soğuk açılışta son istek ~1 dakika bekler;")
    print("C, talebi önce azalttığı için aynı bütçede hem 429'suz hem hızlıdır.")
    print()
    print("Not: AniList'in burst limiter eşiği yayınlanmadığı için 60 sn kayan")
    print("pencere modeli kullanıldı; kapı ayrıca minimum aralık uygular.")


if __name__ == "__main__":
    main()
