/* SAARTHI sign-in page behaviour.
   Two roles, a username/email and a password — nothing else. No social login,
   no OTP, no phone login. Configuration internals are never shown: the page
   reports sign-in outcomes in neutral terms and always offers continuing
   without signing in. */
(function () {
  'use strict';

  var form = document.getElementById('auth-form');
  var userEl = document.getElementById('auth-user');
  var passEl = document.getElementById('auth-pass');
  var submitEl = document.getElementById('auth-submit');
  var statusEl = document.getElementById('auth-status');
  var revealEl = document.getElementById('auth-reveal');
  var segEl = document.querySelector('.auth-seg');
  var segBtns = Array.prototype.slice.call(document.querySelectorAll('.auth-seg-btn'));
  var role = 'farmer';

  function say(text, kind) {
    if (!statusEl) return;
    statusEl.textContent = text || '';
    statusEl.className = 'auth-status' + (kind ? ' is-' + kind : '');
  }

  function selectRole(next) {
    role = next;
    segBtns.forEach(function (b) {
      var on = b.getAttribute('data-role') === next;
      b.classList.toggle('is-active', on);
      b.setAttribute('aria-checked', on ? 'true' : 'false');
    });
    if (segEl) segEl.setAttribute('data-index', next === 'official' ? '1' : '0');
  }

  segBtns.forEach(function (b) {
    b.addEventListener('click', function () { selectRole(b.getAttribute('data-role')); });
  });

  // Show / hide password.
  if (revealEl && passEl) {
    revealEl.addEventListener('click', function () {
      var shown = passEl.type === 'text';
      passEl.type = shown ? 'password' : 'text';
      revealEl.textContent = shown ? 'Show' : 'Hide';
      revealEl.setAttribute('aria-pressed', shown ? 'false' : 'true');
      revealEl.setAttribute('aria-label', shown ? 'Show password' : 'Hide password');
    });
  }

  // Availability probe (presentation-silent): the page never shows a
  // configuration warning. Sign-in stays usable; failures surface only
  // after the user submits the form.
  function loadStatus() {
    fetch('/api/auth/status')
      .then(function (r) { return r.ok ? r.json() : null; })
      .then(function () { /* intentionally silent — no visible warning */ })
      .catch(function () { /* intentionally silent — no visible warning */ });
  }

  // Neutral presentation of sign-in outcomes. Server internals about
  // configuration, credentials or settings are never rendered: an
  // unavailable backend becomes a plain "unavailable" message, and the
  // visitor can always continue without signing in.
  function friendlyMessage(status, serverMessage, fallback) {
    var text = String(serverMessage || '');
    if (status === 'not_configured'
        || /not configured|no credentials|SAARTHI_AUTH|settings are provided/i.test(text)) {
      return 'Sign-in is unavailable right now. You can continue without signing in.';
    }
    return text || fallback;
  }

  function setBusy(busy) {
    if (!submitEl) return;
    submitEl.classList.toggle('is-busy', busy);
    submitEl.disabled = busy;
    var label = submitEl.querySelector('.auth-submit-label');
    if (label) label.textContent = busy ? 'Signing in' : 'Sign in';
  }

  if (form) {
    form.addEventListener('submit', function (ev) {
      ev.preventDefault();
      var username = (userEl && userEl.value || '').trim();
      var password = (passEl && passEl.value) || '';
      if (!username || !password) {
        say('Enter both your username or email and your password.', 'error');
        (username && !password ? passEl : userEl).focus();
        return;
      }
      setBusy(true);
      say('Checking your details…');
      fetch('/api/auth/sign-in', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ username: username, password: password, role: role }),
      })
        .then(function (res) {
          return res.json().catch(function () { return {}; }).then(function (body) {
            return { status: res.status, body: body };
          });
        })
        .then(function (r) {
          var body = r.body || {};
          if (r.status === 200 && body.status === 'ok') {
            // The token is handed to the session store for the platform's own
            // guarded views to pick up later; the page itself only says so.
            try { sessionStorage.setItem('saarthi_session', body.token); } catch (e) {}
            say('Signed in as ' + (body.role_label || role) + '.', 'ok');
            setBusy(false);
            window.setTimeout(function () { window.location.href = '/home'; }, 700);
            return;
          }
          setBusy(false);
          say(friendlyMessage(body.status, body.message, 'Sign-in did not succeed.'), 'error');
        })
        .catch(function () {
          setBusy(false);
          say('Sign-in is unavailable right now. You can continue without signing in.', 'error');
        });
    });
  }

  document.addEventListener('DOMContentLoaded', function () {
    selectRole('farmer');
    loadStatus();
  });
})();
