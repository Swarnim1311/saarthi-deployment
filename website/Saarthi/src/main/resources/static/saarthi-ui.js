/* SAARTHI shared UI: sticky nav state, mobile menu, scroll reveal,
   view transitions, cinematic forecast strip. English only. */
(function () {
  'use strict';

  function setActiveNav() {
    var path = (location.pathname || '/').replace(/\/+$/, '') || '/';
    var map = {
      '/': 'home', '/home': 'home', '/weather': 'weather', '/farmer': 'farmer', '/forecast': 'timeline', '/timeline': 'timeline',
      '/risk-map': 'map', '/map': 'map', '/cropatlas': 'cropatlas',
      '/climate': 'intelligence', '/intelligence': 'intelligence',
      '/policies': 'policy-alerts', '/policy-alerts': 'policy-alerts'
    };
    var key = map[path] || 'home';
    document.querySelectorAll('[data-nav]').forEach(function (a) {
      if (a.getAttribute('data-nav') === key) a.classList.add('active');
      else a.classList.remove('active');
    });
  }

  function initMenu() {
    var btn = document.getElementById('sa-menu-btn') || document.querySelector('.fab-menu');
    var menu = document.getElementById('sa-mobile-menu') || document.getElementById('rail-overlay');
    if (!btn || !menu) return;
    btn.addEventListener('click', function () {
      var open = menu.classList.toggle('open');
      btn.setAttribute('aria-expanded', open ? 'true' : 'false');
      btn.textContent = open ? '✕' : '☰';
    });
    menu.addEventListener('click', function (e) {
      if (e.target.closest('a')) {
        menu.classList.remove('open');
        btn.setAttribute('aria-expanded', 'false');
        btn.textContent = '☰';
      }
    });
  }

  function initReveal() {
    var els = document.querySelectorAll('.sa-reveal');
    if (!els.length) return;
    if (!('IntersectionObserver' in window)) {
      els.forEach(function (el) { el.classList.add('in'); });
      return;
    }
    var reduce = window.matchMedia && window.matchMedia('(prefers-reduced-motion: reduce)').matches;
    if (reduce) { els.forEach(function (el) { el.classList.add('in'); }); return; }
    var io = new IntersectionObserver(function (entries) {
      entries.forEach(function (en) {
        if (en.isIntersecting) { en.target.classList.add('in'); io.unobserve(en.target); }
      });
    }, { threshold: 0.12, rootMargin: '0px 0px -8% 0px' });
    els.forEach(function (el) { io.observe(el); });
  }

  function initTransitions() {
    document.addEventListener('click', function (e) {
      var a = e.target.closest('a[href^="/"]');
      if (!a) return;
      var url = new URL(a.href, location.origin);
      if (url.origin !== location.origin) return;
      if (e.metaKey || e.ctrlKey || e.shiftKey || e.altKey) return;
      if (!document.startViewTransition) return;
      // Let same-page hashes behave normally.
      if (url.pathname === location.pathname && url.hash) return;
      e.preventDefault();
      document.startViewTransition(function () { location.href = a.href; });
    });
  }

  function carryLocationToFarmer() {
    document.querySelectorAll('[data-carry-to-farmer]').forEach(function (a) {
      a.addEventListener('click', function () {
        // SaarthiGeo already persists selection in localStorage;
        // portal.js restores it. Nothing else to carry.
        try { sessionStorage.setItem('saarthi_enter_portal', String(Date.now())); } catch (e) {}
      });
    });
  }

  document.addEventListener('DOMContentLoaded', function () {
    setActiveNav(); initMenu(); initReveal(); initTransitions(); carryLocationToFarmer();
    document.body.classList.add('sa-enter');
  });
})();
