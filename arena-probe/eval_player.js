// Gecici arastirma: gomulu oynatici sayfasindaki JS'i gercekten calistirip
// jwplayer().setup(...) / Hls.loadSource(...) / XHR'a giden GERCEK adresi yakalar.
// Bu, eklentideki Kotlin cozucusunun dogrulanacagi "altin" sonuctur.
'use strict';
const fs = require('fs');
const path = require('path');
const vm = require('vm');

const OUT = process.env.PROBE_OUT ? path.resolve(process.env.PROBE_OUT) : path.join(__dirname, 'out');

function extractScripts(html) {
  const out = [];
  const re = /<script\b([^>]*)>([\s\S]*?)<\/script>/gi;
  let m;
  while ((m = re.exec(html))) {
    const attrs = m[1] || '';
    const code = m[2] || '';
    const src = (attrs.match(/\bsrc\s*=\s*["']([^"']+)["']/i) || [])[1];
    if (src) { out.push({ kind: 'external', src }); continue; }
    if (/type\s*=\s*["']application\/ld\+json["']/i.test(attrs)) { out.push({ kind: 'ldjson', code }); continue; }
    const type = (attrs.match(/\btype\s*=\s*["']([^"']+)["']/i) || [])[1];
    if (type && !/javascript|ecmascript|module/i.test(type)) { out.push({ kind: 'other:' + type, code }); continue; }
    out.push({ kind: 'inline', code });
  }
  return out;
}

function safeClone(v, depth = 0) {
  if (depth > 6) return '[deep]';
  if (v === null || v === undefined) return v;
  const t = typeof v;
  if (t === 'string' || t === 'number' || t === 'boolean') return v;
  if (t === 'function') return '[fn]';
  if (Array.isArray(v)) return v.slice(0, 50).map((x) => safeClone(x, depth + 1));
  if (t === 'object') {
    const o = {};
    let n = 0;
    for (const k of Object.keys(v)) {
      if (n++ > 80) break;
      try { o[k] = safeClone(v[k], depth + 1); } catch (e) { o[k] = '[err]'; }
    }
    return o;
  }
  return String(v);
}

function makeChain(name, log) {
  const fn = function () { return proxy; };
  const proxy = new Proxy(fn, {
    get(_t, prop) {
      if (prop === Symbol.toPrimitive) return () => '';
      if (prop === 'toString' || prop === 'valueOf') return () => '';
      if (prop === 'then') return undefined;
      if (prop === 'length') return 0;
      if (prop === Symbol.iterator) return function* () {};
      return proxy;
    },
    apply(_t, _this, args) { return proxy; },
    construct() { return proxy; },
    set() { return true; },
    has() { return true; },
  });
  return proxy;
}

function runEmbed(entry) {
  const html = fs.readFileSync(path.join(OUT, entry.file), 'utf8');
  const url = entry.url || entry.embed;
  const u = new URL(url);
  const scripts = extractScripts(html);
  const captured = { setups: [], hls: [], playerjs: [], videojs: [], clappr: [], xhr: [], fetch: [], ajax: [],
    windowOpen: [], locationSets: [], logs: [], errors: [], sourcesCalls: [] };
  const timers = [];

  const chain = makeChain('chain');
  const elem = () => {
    const el = {
      style: {}, dataset: {}, classList: { add() {}, remove() {}, contains() { return false; }, toggle() {} },
      setAttribute() {}, getAttribute() { return null; }, removeAttribute() {}, hasAttribute() { return false; },
      appendChild(c) { return c; }, removeChild(c) { return c; }, insertBefore(c) { return c; }, prepend() {}, append() {},
      addEventListener() {}, removeEventListener() {}, querySelector() { return elem(); }, querySelectorAll() { return []; },
      getElementsByTagName() { return []; }, getElementsByClassName() { return []; }, getBoundingClientRect() { return { width: 1280, height: 720, top: 0, left: 0 }; },
      focus() {}, blur() {}, click() {}, remove() {}, play() { return Promise.resolve(); }, pause() {}, load() {},
      innerHTML: '', innerText: '', textContent: '', value: '', children: [], childNodes: [], parentNode: null,
      offsetWidth: 1280, offsetHeight: 720, clientWidth: 1280, clientHeight: 720, src: '', id: '', className: '',
    };
    return el;
  };
  const jwInst = new Proxy({}, {
    get(_t, prop) {
      if (prop === 'setup') return (cfg) => { captured.setups.push(safeClone(cfg)); return jwInst; };
      if (prop === 'getPlaylistItem') return () => ({});
      if (prop === 'getPlaylist') return () => [];
      if (prop === 'getPosition' || prop === 'getDuration' || prop === 'getVolume') return () => 0;
      if (prop === 'getContainer') return () => elem();
      if (prop === 'load') return (pl) => { captured.sourcesCalls.push(safeClone(pl)); return jwInst; };
      if (prop === 'then') return undefined;
      if (prop === Symbol.toPrimitive) return () => '';
      return () => jwInst;
    },
  });
  const jwplayer = function () { return jwInst; };
  jwplayer.key = '';
  jwplayer.version = '8.33.2';
  jwplayer.utils = chain;

  class Hls {
    constructor(cfg) { captured.hls.push({ ctor: safeClone(cfg) }); }
    static isSupported() { return true; }
    loadSource(src) { captured.hls.push({ loadSource: src }); }
    attachMedia() {}
    on() {}
    destroy() {}
  }
  Hls.Events = new Proxy({}, { get: (_t, p) => String(p) });

  class XHR {
    open(method, href) { this._u = href; captured.xhr.push({ method, url: String(href) }); }
    setRequestHeader(k, v) { captured.xhr.push({ header: [k, v] }); }
    send(body) { captured.xhr.push({ send: body ? String(body).slice(0, 2000) : null }); }
    addEventListener() {}
    abort() {}
  }

  const jq = function (arg) {
    if (typeof arg === 'function') timers.push({ fn: arg, n: 1 });
    return jqChain;
  };
  const jqChain = new Proxy(function () {}, {
    get(_t, prop) {
      if (prop === 'ready') return (fn) => { timers.push({ fn, n: 1 }); return jqChain; };
      if (prop === 'then') return undefined;
      if (prop === Symbol.toPrimitive) return () => '';
      if (prop === 'length') return 1;
      return () => jqChain;
    },
    apply() { return jqChain; },
  });
  jq.ajax = (opts) => { captured.ajax.push(safeClone(opts)); return jqChain; };
  jq.get = (a) => { captured.ajax.push({ get: safeClone(a) }); return jqChain; };
  jq.post = (a, b) => { captured.ajax.push({ post: safeClone(a), data: safeClone(b) }); return jqChain; };
  jq.getJSON = (a) => { captured.ajax.push({ getJSON: safeClone(a) }); return jqChain; };
  jq.fn = {};
  jq.extend = Object.assign;

  const locationObj = {
    href: url, origin: u.origin, protocol: u.protocol, host: u.host, hostname: u.hostname,
    pathname: u.pathname, search: u.search, hash: u.hash, port: u.port,
    reload() {}, replace(x) { captured.locationSets.push(String(x)); }, assign(x) { captured.locationSets.push(String(x)); },
    toString() { return url; },
  };
  const documentObj = {
    cookie: '', referrer: entry.referer || '', readyState: 'complete', title: '',
    location: locationObj, domain: u.hostname, URL: url,
    getElementById() { return elem(); }, querySelector() { return elem(); }, querySelectorAll() { return []; },
    getElementsByTagName() { return [elem()]; }, getElementsByClassName() { return []; },
    createElement() { return elem(); }, createTextNode() { return elem(); },
    addEventListener(ev, fn) { if (typeof fn === 'function') timers.push({ fn, n: 1 }); },
    removeEventListener() {}, write() {}, writeln() {},
    body: elem(), head: elem(), documentElement: elem(),
    hasFocus() { return true; }, hidden: false, visibilityState: 'visible',
  };
  const ctx = {
    console: { log: (...a) => captured.logs.push(a.map(String).join(' ').slice(0, 500)), warn() {}, error() {}, info() {}, debug() {}, table() {}, clear() {} },
    atob: (s) => Buffer.from(String(s), 'base64').toString('latin1'),
    btoa: (s) => Buffer.from(String(s), 'latin1').toString('base64'),
    setTimeout: (fn, ms) => { if (typeof fn === 'function') timers.push({ fn, n: 1, ms }); return timers.length; },
    setInterval: (fn, ms) => { if (typeof fn === 'function') timers.push({ fn, n: 2, ms }); return timers.length; },
    clearTimeout() {}, clearInterval() {}, requestAnimationFrame(fn) { timers.push({ fn, n: 1 }); return 1; },
    navigator: { userAgent: 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36', language: 'tr-TR', languages: ['tr-TR', 'tr'], platform: 'Win32', webdriver: false, cookieEnabled: true, maxTouchPoints: 0, hardwareConcurrency: 8, plugins: [], mimeTypes: [] },
    location: locationObj, document: documentObj,
    screen: { width: 1920, height: 1080, availWidth: 1920, availHeight: 1040 },
    innerWidth: 1280, innerHeight: 720, outerWidth: 1280, outerHeight: 800, devicePixelRatio: 1,
    jwplayer, Hls, XMLHttpRequest: XHR,
    fetch: (href, opts) => { captured.fetch.push({ url: String(href), opts: safeClone(opts) }); return new Promise(() => {}); },
    $: jq, jQuery: jq,
    Playerjs: function (cfg) { captured.playerjs.push(safeClone(cfg)); return chain; },
    videojs: function (id, opts) { captured.videojs.push({ id: String(id), opts: safeClone(opts) }); return chain; },
    Clappr: { Player: function (opts) { captured.clappr.push(safeClone(opts)); return chain; } },
    open: (x) => { captured.windowOpen.push(String(x)); return null; },
    addEventListener(ev, fn) { if (typeof fn === 'function') timers.push({ fn, n: 1 }); },
    removeEventListener() {}, postMessage() {}, alert() {}, confirm() { return true; }, prompt() { return ''; },
    localStorage: { getItem() { return null; }, setItem() {}, removeItem() {} },
    sessionStorage: { getItem() { return null; }, setItem() {}, removeItem() {} },
    performance: { now: () => Date.now() },
    MutationObserver: class { observe() {} disconnect() {} },
    IntersectionObserver: class { observe() {} disconnect() {} },
    Image: class { set src(v) { captured.fetch.push({ img: String(v) }); } },
    TextDecoder, TextEncoder, URL, URLSearchParams, Promise, JSON, Math, Date, RegExp, Array, Object, String, Number, Boolean, Symbol, Error, TypeError, Uint8Array, ArrayBuffer, DataView, Map, Set, WeakMap, parseInt, parseFloat, isNaN, isFinite, encodeURIComponent, decodeURIComponent, encodeURI, decodeURI, escape, unescape,
  };
  ctx.window = ctx; ctx.self = ctx; ctx.globalThis = ctx; ctx.parent = { location: { href: entry.referer || '' }, postMessage() {} }; ctx.top = ctx.parent; ctx.frameElement = elem();
  vm.createContext(ctx);

  const results = { label: entry.label, url, referer: entry.referer, scripts: [], externalScripts: [], ldjson: [] };
  for (const s of scripts) {
    if (s.kind === 'external') { results.externalScripts.push(s.src); continue; }
    if (s.kind === 'ldjson') { results.ldjson.push(s.code.slice(0, 3000)); continue; }
    if (s.kind !== 'inline' || !s.code.trim()) continue;
    let attempt = 0;
    let ok = false;
    let lastErr = null;
    while (attempt < 60) {
      attempt++;
      try {
        vm.runInContext(s.code, ctx, { timeout: 4000 });
        ok = true;
        break;
      } catch (e) {
        lastErr = e;
        const m = /^(\S+) is not defined/.exec(e && e.message || '');
        if (e && e.name === 'ReferenceError' && m) {
          ctx[m[1]] = makeChain(m[1]);
          continue;
        }
        break;
      }
    }
    results.scripts.push({ len: s.code.length, ok, attempts: attempt, error: ok ? null : String(lastErr && (lastErr.stack || lastErr)).slice(0, 600) });
  }
  // zamanlayicilari calistir
  let runs = 0;
  while (timers.length && runs < 300) {
    const t = timers.shift();
    runs++;
    try { t.fn.call(ctx); } catch (e) { captured.errors.push('timer: ' + String(e && e.message).slice(0, 200)); }
    if (t.n > 1) timers.push({ fn: t.fn, n: t.n - 1 });
  }
  // baglamda kalan URL benzeri stringler
  const urlish = [];
  for (const k of Object.keys(ctx)) {
    try {
      const v = ctx[k];
      if (typeof v === 'string' && /https?:\/\//.test(v) && v.length < 2000) urlish.push([k, v]);
    } catch (e) {}
  }
  results.captured = captured;
  results.urlVars = urlish.slice(0, 40);
  return results;
}

function main() {
  const manifest = JSON.parse(fs.readFileSync(path.join(OUT, 'manifest.json'), 'utf8'));
  const all = [];
  for (const e of manifest.embeds) {
    try {
      const r = runEmbed(e);
      all.push(r);
      const files = [];
      for (const s of r.captured.setups) {
        const srcs = (s && (s.sources || (s.playlist && s.playlist[0] && s.playlist[0].sources))) || [];
        for (const x of srcs) files.push(x && x.file);
        if (s && s.file) files.push(s.file);
      }
      for (const h of r.captured.hls) if (h.loadSource) files.push(h.loadSource);
      for (const p of r.captured.playerjs) if (p && p.file) files.push(p.file);
      console.log(`[EVAL] ${e.label} ${e.url}\n   setups=${r.captured.setups.length} files=${JSON.stringify(files)} xhr=${JSON.stringify(r.captured.xhr).slice(0, 300)} ajax=${JSON.stringify(r.captured.ajax).slice(0, 300)} fetch=${JSON.stringify(r.captured.fetch).slice(0, 300)}`);
      for (const s of r.scripts) if (!s.ok) console.log(`   script-hata: ${s.error && s.error.split('\n')[0]}`);
    } catch (err) {
      console.log(`[EVAL-FAIL] ${e.label}: ${err && err.stack}`);
      all.push({ label: e.label, error: String(err && err.stack) });
    }
  }
  fs.writeFileSync(path.join(OUT, 'eval_results.json'), JSON.stringify(all, null, 1));
}

main();
