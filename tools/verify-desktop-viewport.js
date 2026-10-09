// Playwright CLI: run-code --filename tools/verify-desktop-viewport.js
// Uses only isolated fixture pages. No Agent servers, accounts or user sessions.
async page => {
  const browser = page.context().browser();
  const mobile = { viewport: { width: 360, height: 640 }, isMobile: true, hasTouch: true };
  // DSH 0.2.0-rc.2 has this viewport. The inline script forces a parser checkpoint
  // before it, reproducing document-start injection preceding the upstream meta.
  const head = '<!doctype html><html><head><meta charset="utf-8"><script>void 0;</script>';
  const viewport = '<meta name="viewport" content="width=device-width, initial-scale=1">';
  const tail = '<style>#desktop{display:none}@media(min-width:1000px){#desktop{display:block}}</style></head><body><div id="desktop">Desktop layout</div></body></html>';
  const output = [];
  const check = (value, message) => { if (!value) throw new Error(message); };
  for (const [name, enabled, html] of [
    ['mobile', false, head + viewport + tail],
    ['desktop-late-meta', true, head + viewport + tail],
    ['desktop-duplicate-meta', true, head + viewport + viewport + tail],
    ['desktop-no-meta', true, head + tail],
    ['mobile-restored', false, head + viewport + tail],
  ]) {
    const context = await browser.newContext(mobile);
    try {
      const target = await context.newPage();
      if (enabled) await target.addInitScript({ path: 'android/app/src/main/assets/desktop-viewport.js' });
      await target.route('https://viewport.test/**', route => route.fulfill({ contentType: 'text/html', body: html }));
      await target.goto('https://viewport.test/');
      const read = () => target.evaluate(() => ({
        width: innerWidth,
        metas: [...document.querySelectorAll('meta[name="viewport" i]')].map(meta => meta.content),
        desktop: getComputedStyle(document.querySelector('#desktop')).display === 'block',
      }));
      await target.waitForFunction(wide => wide ? innerWidth >= 1200 : innerWidth < 500, enabled);
      let state = await read();
      check(state.desktop === enabled, name + ': responsive layout mismatch ' + JSON.stringify(state));
      if (enabled) {
        check(state.metas.every(content => content === 'width=1280'), name + ': conflicting meta');
        // An upstream component can replace, append, rename or rewrite a viewport.
        await target.evaluate(() => {
          document.querySelectorAll('meta[name="viewport" i]').forEach(meta => meta.remove());
          const meta = document.createElement('meta'); meta.name = 'description'; meta.content = 'width=device-width,initial-scale=1'; document.head.appendChild(meta); meta.name = 'VIEWPORT';
        });
        await target.waitForFunction(() => innerWidth >= 1200 && [...document.querySelectorAll('meta[name="viewport" i]')].every(meta => meta.content === 'width=1280'));
        await target.evaluate(() => { document.querySelector('meta[name="viewport" i]').content = 'width=device-width,initial-scale=1'; });
        await target.waitForFunction(() => innerWidth >= 1200 && document.querySelector('meta[name="viewport" i]').content === 'width=1280');
        state = await read();
        check(state.desktop, name + ': dynamic replacement lost desktop layout');
      }
      output.push({ name, ...state });
    } finally { await context.close(); }
  }
  return output;
}
