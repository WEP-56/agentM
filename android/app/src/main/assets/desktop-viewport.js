// Installed at document start only in desktop mode, on the active Agent origin.
(() => {
  const apply = () => {
    if (!document.head) return;
    let metas = document.querySelectorAll('meta[name="viewport" i]');
    if (metas.length === 0) {
      // The HTML parser may not have reached the page's own viewport yet.
      // Creating one now lets a later mobile declaration override it in Chromium.
      if (document.readyState === 'loading') return;
      const meta = document.createElement('meta');
      meta.name = 'viewport';
      document.head.appendChild(meta);
      metas = [meta];
    }
    // Chromium combines multiple declarations; keep every one consistent.
    for (const meta of metas) {
      if (meta.content !== 'width=1280') meta.content = 'width=1280';
    }
  };
  new MutationObserver(apply).observe(document, {
    childList: true, subtree: true, attributes: true, attributeFilter: ['name', 'content'],
  });
  document.addEventListener('DOMContentLoaded', apply, { once: true });
  apply();
})();
