/* SAARTHI liquid glass — lightweight adaptation of the CodePen technique.
 *
 * The reference renders glass with WebGL: background sampling (via an
 * html2canvas snapshot), blur, refraction, rim/edge effects. SAARTHI gets
 * the same four ingredients at near-zero cost:
 *   sampling  -> backdrop-filter:url(#saLiq) samples the LIVE backdrop
 *                (real-time, GPU-composited; no snapshot, no canvas loop)
 *   refraction-> SVG feTurbulence + feDisplacementMap inside the filter
 *   blur      -> blur() chained after the url() filter
 *   rim       -> CSS inset highlights + top sheen (.liq in saarthi-shell.css)
 *
 * Fallback is automatic: `html.liq-on` is added ONLY when the browser
 * reports backdrop-filter:url() support AND the device has a fine pointer
 * without a reduced-motion request. Everywhere else the plain CSS
 * glass (blur + translucent surface + rim) applies untouched. The site is
 * fully functional without it. Pointer sheen targets .liq-hero only and is
 * skipped entirely unless liq-on is active.
 */
(function () {
  'use strict';

  var SVG_NS = 'http://www.w3.org/2000/svg';

  function injectDefs() {
    if (document.getElementById('saLiq')) return;
    var svg = document.createElementNS(SVG_NS, 'svg');
    svg.setAttribute('aria-hidden', 'true');
    svg.setAttribute('width', '0');
    svg.setAttribute('height', '0');
    svg.style.position = 'absolute';
    // Filter region widened so edge displacement never clips.
    svg.innerHTML =
      '<defs>' +
      '<filter id="saLiq" x="-20%" y="-20%" width="140%" height="140%" color-interpolation-filters="sRGB">' +
      '<feTurbulence type="fractalNoise" baseFrequency="0.012 0.02" numOctaves="1" seed="7" result="n"/>' +
      '<feDisplacementMap in="SourceGraphic" in2="n" scale="9" xChannelSelector="R" yChannelSelector="G" result="d"/>' +
      '</filter>' +
      '<filter id="saLiqSoft" x="-20%" y="-20%" width="140%" height="140%" color-interpolation-filters="sRGB">' +
      '<feTurbulence type="fractalNoise" baseFrequency="0.02 0.03" numOctaves="1" seed="11" result="n"/>' +
      '<feDisplacementMap in="SourceGraphic" in2="n" scale="6" xChannelSelector="R" yChannelSelector="G" result="d"/>' +
      '</filter>' +
      '</defs>';
    document.body.insertBefore(svg, document.body.firstChild);
  }

  function supportsLiquid() {
    try {
      if (window.CSS && typeof window.CSS.supports === 'function') {
        // Chromium accepts url() in backdrop-filter; others drop it.
        if (!window.CSS.supports('backdrop-filter', 'url(#saLiq)')) return false;
      } else {
        return false;
      }
    } catch (e) { return false; }
    try {
      // PERF: the url() filter forces software rasterization, so enable it
      // only where it is cheap and visible: fine-pointer devices without a
      // reduced-motion request. Touch / reduced-motion keep static CSS glass.
      if (window.matchMedia && window.matchMedia('(prefers-reduced-motion: reduce)').matches) return false;
      if (window.matchMedia && window.matchMedia('(hover: none)').matches) return false;
    } catch (e) { /* ignore */ }
    return true;
  }

  function initSheen() {
    // Sheen only matters when the liquid enhancement is active; otherwise
    // there is no ::after highlight to move (CSS keeps opacity 0).
    if (!document.documentElement.classList.contains('liq-on')) return;
    var reduce = false, touch = false;
    try { reduce = window.matchMedia('(prefers-reduced-motion: reduce)').matches; } catch (e) {}
    try { touch = window.matchMedia('(hover: none)').matches; } catch (e) {}
    if (reduce || touch) return;
    var pending = null;
    document.addEventListener('pointermove', function (e) {
      if (pending) return;
      pending = requestAnimationFrame(function () {
        pending = null;
        var el = e.target && e.target.closest ? e.target.closest('.liq-hero') : null;
        if (!el) return;
        var r = el.getBoundingClientRect();
        el.style.setProperty('--mx', ((e.clientX - r.left) / Math.max(r.width, 1) * 100).toFixed(1) + '%');
        el.style.setProperty('--my', ((e.clientY - r.top) / Math.max(r.height, 1) * 100).toFixed(1) + '%');
      });
    }, { passive: true });
  }

  document.addEventListener('DOMContentLoaded', function () {
    injectDefs();
    if (supportsLiquid()) {
      document.documentElement.classList.add('liq-on');
    }
    initSheen();
  });

  window.SaarthiLiquid = { refresh: injectDefs };
})();
