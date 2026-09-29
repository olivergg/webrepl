// Drives webrepl in headless Chrome through the demo scenario and records it as a webm.
// Usage: node record.js <url-with-token> <out-dir>   (see record.sh, which does it all)
const { chromium } = require('playwright-core');
const [url, outDir] = process.argv.slice(2);
const W = 1200, H = 720, FAKE_HOST = 'demo-app/10.0.0.42';
const CHROME = process.env.CHROME || '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome';

(async () => {
  const b = await chromium.launch({ executablePath: CHROME, headless: true });
  const ctx = await b.newContext({ viewport: { width: W, height: H },
                                   recordVideo: { dir: outDir, size: { width: W, height: H } } });
  // quick access starts closed so history gets the width; ⌘K opens it later
  await ctx.addInitScript(() => localStorage.setItem('webrepl-ui', JSON.stringify({ qa: false })));
  // never paint the recording machine's hostname: rewritten in a microtask, before any frame
  await ctx.addInitScript(fake => addEventListener('DOMContentLoaded', () => {
    const h = document.getElementById('host');
    new MutationObserver(() => { if (h.textContent !== fake && h.textContent !== '—') h.textContent = fake; })
      .observe(h, { childList: true, characterData: true, subtree: true });
  }), FAKE_HOST);
  // headless video has no pointer: draw one
  await ctx.addInitScript(() => addEventListener('DOMContentLoaded', () => {
    const c = document.createElement('div');
    c.style.cssText = 'position:fixed;z-index:99999;width:14px;height:14px;border-radius:50%;'
      + 'background:rgba(196,99,63,.55);border:2px solid #fff;box-shadow:0 1px 4px rgba(0,0,0,.35);'
      + 'pointer-events:none;left:-40px;top:-40px;transform:translate(-50%,-50%);transition:left .35s,top .35s';
    document.body.appendChild(c);
    addEventListener('mousemove', e => { c.style.left = e.clientX + 'px'; c.style.top = e.clientY + 'px'; }, true);
  }));

  const p = await ctx.newPage();
  const errs = []; p.on('pageerror', e => errs.push(e.message));
  const pause = ms => p.waitForTimeout(ms);
  const idle = () => p.waitForFunction(() => !document.querySelector('.form.running'), null, { timeout: 20000 });
  const type = async (s, delay = 55) => { await p.focus('#in'); await p.keyboard.type(s, { delay }); };
  const send = async () => { await p.keyboard.press('Enter'); await idle(); await pause(900); };
  const moveTo = async (loc, dx) => {
    const bb = await loc.boundingBox();
    await p.mouse.move(bb.x + (dx ?? bb.width / 2), bb.y + bb.height / 2, { steps: 12 }); await pause(300);
  };

  await p.goto(url);
  await p.waitForFunction(() => document.querySelector('#ns')?.textContent === 'demo.app', null, { timeout: 60000 });
  await p.waitForFunction(fake => document.querySelector('#host')?.textContent === fake, FAKE_HOST, { timeout: 10000 });
  await pause(1200);

  // 1. hello world, 2. a helper returning data (pretty-printed)
  await type('(println "Hello, webrepl!")'); await pause(300); await send();
  await type('(recent-orders 3)'); await pause(300); await send();

  // 3. .method completion, reflected on the bound value
  await type('(def svc (get-bean "orderService"))'); await send();
  await type('(-> svc .cou', 90);
  await p.waitForSelector('.cmp-row', { timeout: 5000 }); await pause(1300);
  await p.keyboard.press('ArrowDown'); await pause(600);
  await p.keyboard.press('Tab'); await pause(500);
  await type(')'); await pause(300); await send();

  // 4. tap> inspector: the root arrives expanded, drill into :address then :orders
  await type('(tap> (find-customer "Ada"))'); await send();
  await moveTo(p.locator('#taptoggle')); await p.click('#taptoggle');
  const kids = p.locator('.tap-item .kids .node.branch');
  await kids.first().waitFor({ state: 'visible', timeout: 5000 }); await pause(700);
  for (const i of [0, 1]) {
    const k = kids.nth(i); await moveTo(k, 40);
    await k.click({ position: { x: 40, y: (await k.boundingBox()).height / 2 } }); await pause(1100);
  }

  // 5. notebook snippet from quick access (⌘K), then eval it
  await p.focus('#in'); await p.keyboard.press('Meta+k'); await pause(800);
  await p.focus('#palq'); await p.keyboard.type('cancel', { delay: 90 }); await pause(900);
  await p.keyboard.press('Enter'); await pause(900);
  await send(); await pause(2500);

  const video = p.video(); await ctx.close(); await b.close();
  if (errs.length) { console.error('page errors:', errs); process.exit(1); }
  console.log(await video.path());
})().catch(e => { console.error('FAIL', e.message); process.exit(1); });
