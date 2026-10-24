// Gecici arastirma: gercek Chromium ile gomulu oynaticiyi acip oynaticinin
// istedigi yayin adreslerini (m3u8/master.txt) ag trafiginden yakalar.
'use strict';
const fs = require('fs');
const path = require('path');
const { chromium } = require('playwright');

const OUT = path.join(__dirname, 'out');

async function main() {
  const manifest = JSON.parse(fs.readFileSync(path.join(OUT, 'manifest.json'), 'utf8'));
  let proxies = [];
  try { proxies = JSON.parse(fs.readFileSync(path.join(OUT, 'proxies.json'), 'utf8')).map((p) => p.proxy); } catch (e) {}
  const mode = process.env.PROBE_MODE || 'proxy';
  const results = [];
  const embeds = manifest.embeds.slice(0, 8);
  for (const e of embeds) {
    const tried = [];
    for (let i = 0; i < Math.min(4, Math.max(1, proxies.length)); i++) {
      const proxy = mode === 'direct' ? null : proxies[i];
      const launchOpts = { headless: true, args: ['--autoplay-policy=no-user-gesture-required'] };
      if (proxy) launchOpts.proxy = { server: proxy };
      const browser = await chromium.launch(launchOpts);
      const ctx = await browser.newContext({
        userAgent: 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36',
        locale: 'tr-TR', ignoreHTTPSErrors: true,
      });
      const page = await ctx.newPage();
      const reqs = [];
      page.on('request', (r) => {
        const u = r.url();
        if (/m3u8|master|\.txt(\?|$)|\.mp4|\.ts(\?|$)|playlist|\/hls\/|\.vtt|\.srt/i.test(u)) {
          reqs.push({ url: u, headers: r.headers(), type: r.resourceType() });
        }
      });
      const responses = [];
      page.on('response', async (r) => {
        const u = r.url();
        if (/m3u8|master|\.txt(\?|$)/i.test(u)) {
          let head = '';
          try { head = (await r.text()).slice(0, 400); } catch (err) {}
          responses.push({ url: u, status: r.status(), head });
        }
      });
      let navErr = null;
      try {
        await page.goto(e.url, { referer: e.referer, timeout: 60000, waitUntil: 'domcontentloaded' });
        await page.waitForTimeout(6000);
      } catch (err) { navErr = String(err).slice(0, 300); }
      let playlist = null;
      try {
        playlist = await page.evaluate(() => {
          try {
            if (typeof jwplayer === 'function') {
              const p = jwplayer();
              const pl = p.getPlaylist ? p.getPlaylist() : null;
              try { p.play && p.play(); } catch (e) {}
              return JSON.parse(JSON.stringify(pl));
            }
          } catch (e) { return 'err:' + e.message; }
          return null;
        });
      } catch (err) { playlist = 'eval-err:' + String(err).slice(0, 200); }
      try { await page.waitForTimeout(8000); } catch (err) {}
      let htmlLen = 0;
      try { htmlLen = (await page.content()).length; } catch (err) {}
      await browser.close();
      const item = { label: e.label, url: e.url, referer: e.referer, proxy, navErr, playlist, reqs, responses, htmlLen };
      tried.push(item);
      console.log(`[BROWSER] ${e.label} proxy=${proxy} navErr=${navErr} reqs=${reqs.length} playlist=${JSON.stringify(playlist).slice(0, 400)}`);
      for (const r of reqs.slice(0, 6)) console.log(`    req ${r.type} ${r.url.slice(0, 200)} ref=${r.headers.referer} origin=${r.headers.origin}`);
      if (!navErr || reqs.length) break;
    }
    results.push(tried);
  }
  fs.writeFileSync(path.join(OUT, 'browser_results.json'), JSON.stringify(results, null, 1));
}

main().catch((e) => { console.error(e); process.exit(0); });
