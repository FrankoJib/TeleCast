const { chromium } = require('playwright');
const fs = require('fs');
const dir = process.argv[2];
const shield = fs.readFileSync(dir + '/shield.js', 'utf8');
const sweep = fs.readFileSync(dir + '/sweep.js', 'utf8');
const scan = fs.readFileSync(dir + '/scan.js', 'utf8');
const IMG = 'data:image/gif;base64,R0lGODlhAQABAIAAAP///wAAACH5BAEAAAAALAAAAAABAAEAAAICRAEAOw==';
const html = `<!doctype html><html><head><meta name=viewport content="width=device-width,initial-scale=1">
<style>body{margin:0;font:16px sans-serif} .sec{height:900px;position:relative}</style></head><body>
<div class=sec id=s1><a id=l1 href="https://it.wikipedia.org/wiki/Film">Wikipedia</a>
 <form id=f1 action="https://duckduckgo.com/" method=get><input name=q value=x><button id=go>Cerca</button></form>
 <video id=v1 preload=none src="https://cdn.esempio.it/film.mp4"></video></div>
<div class=sec id=s2><a id=card href="https://www.imdb.com/title/1"><img src="${IMG}" style="width:100vw;height:80vh;display:block"></a></div>
<div class=sec id=s3><a id=bigtext href="https://esempio.org/" style="display:block;width:100vw;height:80vh;background:#333;color:#fff">Guarda il trailer</a></div>
<div class=sec id=s4><iframe id=adf src="https://c1.popads.net/banner" loading=lazy width=300 height=250></iframe>
 <iframe id=okf src="https://player.vimeo.com/video/1" width=300 height=150></iframe></div>
</body></html>`;
let fails = 0;
function check(name, cond, extra) { console.log((cond ? '  OK   ' : '  FAIL ') + name + (cond ? '' : '  ' + JSON.stringify(extra))); if (!cond) fails++; }
(async () => {
  const browser = await chromium.launch();
  const page = await browser.newPage({ viewport: { width: 400, height: 800 }, isMobile: true, hasTouch: true });
  await page.setContent(html);
  await page.evaluate(() => {
    window.tcTEST = { taps: [], tap(u, o) { this.taps.push([u, o]); }, blocked(h) { return /(^|\.)popads\.net$/.test(h); }, found(j, p, t) { this.f = JSON.parse(j); } };
    // la pagina di prova non deve navigare davvero
    document.addEventListener('click', e => { if (e.target.closest && e.target.closest('a')) e.preventDefault(); }, true);
    document.addEventListener('submit', e => e.preventDefault(), true);
  });
  await page.evaluate(shield); await page.evaluate(shield); // due volte: non deve duplicare
  const taps = () => page.evaluate(() => { const t = window.tcTEST.taps.slice(); window.tcTEST.taps = []; return t; });

  await page.click('#l1');
  let t = await taps();
  check('link vero toccato → registrato una volta, non strato', t.length === 1 && t[0][0] === 'https://it.wikipedia.org/wiki/Film' && t[0][1] === false, t);

  await page.evaluate(() => document.getElementById('l1').click());
  await page.evaluate(() => document.getElementById('l1').dispatchEvent(new MouseEvent('click', { bubbles: true, cancelable: true })));
  t = await taps();
  check('clic simulati dagli script → ignorati', t.length === 0, t);

  await page.click('#go');
  t = await taps();
  check('invio di un modulo → registrato', t.length === 1 && t[0][0] === 'https://duckduckgo.com/' && t[0][1] === false, t);

  await page.evaluate(() => document.getElementById('s2').scrollIntoView());
  await page.click('#card img');
  t = await taps();
  check('scheda grande con immagine → link vero', t.length === 1 && t[0][0] === 'https://www.imdb.com/title/1' && t[0][1] === false, t);

  await page.evaluate(() => document.getElementById('s3').scrollIntoView());
  await page.click('#bigtext');
  t = await taps();
  check('pulsante grande con testo → link vero', t.length === 1 && t[0][1] === false, t);

  // strato trasparente su tutta la pagina (il classico "tocchi Play e si apre lo spam")
  await page.evaluate(() => { const a = document.createElement('a'); a.id = 'ov'; a.href = 'https://vinci-un-iphone.top/'; a.style.cssText = 'position:fixed;inset:0;z-index:99;opacity:0'; document.body.appendChild(a); });
  await page.mouse.click(200, 400);
  t = await taps();
  check('link trasparente sopra la pagina → strato pubblicitario', t.length === 1 && t[0][0] === 'https://vinci-un-iphone.top/' && t[0][1] === true, t);
  await page.evaluate(() => document.getElementById('ov').remove());

  await page.evaluate(() => { const a = document.createElement('a'); a.id = 'ov2'; a.href = 'https://spam2.top/'; const d = document.createElement('div'); d.style.cssText = 'position:fixed;inset:0;z-index:99'; a.appendChild(d); document.body.appendChild(a); });
  await page.mouse.click(200, 400);
  t = await taps();
  check('div vuoto dentro un link sopra la pagina → strato pubblicitario', t.length === 1 && t[0][0] === 'https://spam2.top/' && t[0][1] === true, t);
  await page.evaluate(() => document.getElementById('ov2').remove());

  await page.evaluate(() => { const a = document.createElement('a'); a.id = 'ov3'; a.href = 'https://spam3.top/'; a.style.cssText = 'position:fixed;inset:0;z-index:99;background:rgba(0,0,0,0)'; a.textContent = '   '; document.body.appendChild(a); });
  await page.mouse.click(200, 400);
  t = await taps();
  check('link grande con solo spazi → strato pubblicitario', t.length === 1 && t[0][1] === true, t);
  await page.evaluate(() => document.getElementById('ov3').remove());

  await page.evaluate(sweep);
  const vis = await page.evaluate(() => [getComputedStyle(document.getElementById('adf')).display, getComputedStyle(document.getElementById('okf')).display]);
  check('riquadro pubblicitario nascosto, video di Vimeo no', vis[0] === 'none' && vis[1] !== 'none', vis);

  await page.evaluate(scan);
  const f = await page.evaluate(() => window.tcTEST.f);
  check('video della pagina trovato', Array.isArray(f) && f.includes('https://cdn.esempio.it/film.mp4'), f);

  // senza ponte (pagina iniziale o ponte assente) gli script non fanno danni
  const p2 = await browser.newPage();
  await p2.setContent('<a id=x href="https://a.b/">x</a>');
  const errs = []; p2.on('pageerror', e => errs.push(String(e)));
  await p2.evaluate(shield); await p2.evaluate(sweep); await p2.evaluate(scan);
  check('nessun errore senza ponte', errs.length === 0, errs);

  await browser.close();
  console.log(fails === 0 ? 'TUTTI I TEST JS OK' : 'FALLITI: ' + fails);
  process.exit(fails ? 1 : 0);
})().catch(e => { console.error(e); process.exit(2); });
