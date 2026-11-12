// Quire site: repository links, theme switch, screenshot switches and the copy button.
(function () {
  'use strict';

  var root = document.documentElement;
  var THEME_KEY = 'quire-theme';

  // Repository links. The URL lives in one place: data-repo on <html>.
  var repo = (root.getAttribute('data-repo') || '').replace(/\/+$/, '');
  if (repo) {
    document.querySelectorAll('a[data-repo-path]').forEach(function (a) {
      a.href = repo + a.getAttribute('data-repo-path');
    });
  }

  // Theme. "system" follows prefers-color-scheme. "light" and "dark" are stored.
  function readTheme() {
    var v = null;
    try { v = window.localStorage.getItem(THEME_KEY); } catch (e) {}
    return v === 'light' || v === 'dark' ? v : 'system';
  }

  function saveTheme(choice) {
    try {
      if (choice === 'system') window.localStorage.removeItem(THEME_KEY);
      else window.localStorage.setItem(THEME_KEY, choice);
    } catch (e) {}
  }

  var themeMetas = Array.prototype.slice.call(document.querySelectorAll('meta[name="theme-color"]'));
  var metaDefaults = themeMetas.map(function (m) { return m.getAttribute('content'); });

  function applyTheme(choice) {
    if (choice === 'light' || choice === 'dark') root.setAttribute('data-theme', choice);
    else root.removeAttribute('data-theme');

    // Screenshots with a dark variant: point the <source> at the chosen theme.
    var media = choice === 'dark' ? 'all' : choice === 'light' ? 'not all' : '(prefers-color-scheme: dark)';
    document.querySelectorAll('picture[data-themed] source').forEach(function (s) {
      if (s.getAttribute('media') !== media) s.setAttribute('media', media);
    });

    themeMetas.forEach(function (m, i) {
      var c = choice === 'dark' ? '#1f1d1a' : choice === 'light' ? '#f8f5ef' : metaDefaults[i];
      m.setAttribute('content', c);
    });

    document.querySelectorAll('[data-theme-choice]').forEach(function (b) {
      b.setAttribute('aria-pressed', String(b.getAttribute('data-theme-choice') === choice));
    });
  }

  applyTheme(readTheme());

  document.querySelectorAll('[data-theme-choice]').forEach(function (b) {
    b.addEventListener('click', function () {
      var choice = b.getAttribute('data-theme-choice');
      saveTheme(choice);
      applyTheme(choice);
    });
  });

  // Switches: a segmented control that shows one [data-panel] at a time.
  document.querySelectorAll('[data-switch]').forEach(function (box) {
    var buttons = Array.prototype.slice.call(box.querySelectorAll('[data-view]'));
    var panels = Array.prototype.slice.call(box.querySelectorAll('[data-panel]'));

    function show(view) {
      buttons.forEach(function (b) {
        b.setAttribute('aria-pressed', String(b.getAttribute('data-view') === view));
      });
      panels.forEach(function (p) {
        var on = p.getAttribute('data-panel') === view;
        p.classList.toggle('is-off', !on);
        if (on) p.removeAttribute('aria-hidden');
        else p.setAttribute('aria-hidden', 'true');
      });
    }

    var first = buttons.filter(function (b) { return b.getAttribute('aria-pressed') === 'true'; })[0] || buttons[0];
    if (first) show(first.getAttribute('data-view'));

    buttons.forEach(function (b) {
      b.addEventListener('click', function () { show(b.getAttribute('data-view')); });
    });
  });

  // Copy the MCP configuration that is on screen.
  document.querySelectorAll('[data-copy]').forEach(function (btn) {
    var box = btn.closest('[data-switch]') || document;
    var status = box.querySelector('[data-copy-status]');
    var timer = null;

    function say(label, message) {
      btn.textContent = label;
      if (status) status.textContent = message;
      window.clearTimeout(timer);
      timer = window.setTimeout(function () {
        btn.textContent = 'Copy';
        if (status) status.textContent = '';
      }, 1800);
    }

    function selectCode(code) {
      try {
        var range = document.createRange();
        range.selectNodeContents(code);
        var sel = window.getSelection();
        sel.removeAllRanges();
        sel.addRange(range);
      } catch (e) {}
    }

    btn.addEventListener('click', function () {
      var code = box.querySelector('[data-panel]:not(.is-off) pre code');
      if (!code) return;
      var text = code.textContent;
      var failed = function () {
        selectCode(code);
        say('Selected', 'Copy failed. The configuration is selected. Press Ctrl+C to copy it.');
      };
      try {
        navigator.clipboard.writeText(text).then(function () {
          say('Copied', 'The configuration is on the clipboard.');
        }, failed);
      } catch (e) {
        failed();
      }
    });
  });
})();

// The promo video plays on its own, muted, unless the reader asks for less motion.
(function () {
  var v = document.querySelector('video[data-promo]');
  if (!v) return;
  var still = window.matchMedia && window.matchMedia('(prefers-reduced-motion: reduce)').matches;
  if (!still) {
    v.autoplay = true;
    var p = v.play();
    if (p && p.catch) p.catch(function () {});
  }
})();
