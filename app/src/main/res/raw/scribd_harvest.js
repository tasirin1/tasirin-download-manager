/*
 * Scribd page-image harvester.
 * Runs inside WebView after the document viewer rendered (client challenge
 * already passed by the browser engine). Scrolls the viewer to trigger
 * lazy-loading and collects every page image URL (html.scribdassets.com).
 */
(function () {
  'use strict';

  var MAX_STEPS = 300;   // safety cap for very long documents
  var SLEEP_MS = 600;    // wait after each scroll for lazy-load
  var STABLE_LIMIT = 4;  // stop after N scrolls that add no new pages

  function isPageUrl(u) {
    if (!u) return false;
    if (!/^https?:\/\//i.test(u)) return false;
    if (!/html\.scribdassets\.com/i.test(u)) return false;
    if (u.indexOf('-pages') !== -1 || u.indexOf('/pages/') !== -1) return true;
    return /(\.jpe?g|\.png)(\?|$)/i.test(u);
  }

  function collect(map) {
    var imgs = document.querySelectorAll(
      'img.absimg, img.orig_image, img[src*="html.scribdassets"]'
    );
    for (var i = 0; i < imgs.length; i++) {
      var s = imgs[i].currentSrc || imgs[i].src || '';
      if (isPageUrl(s)) map[s] = true;
    }
    // Fallback: scan inline scripts for absolute page-image URLs.
    var scripts = document.querySelectorAll('script:not([src])');
    for (var j = 0; j < scripts.length; j++) {
      var txt = scripts[j].textContent || '';
      var re = /https?:\/\/[^"'\s\\]+\.(?:jpe?g|png)(?:\?[^"'\s\\]*)?/gi;
      var m;
      while ((m = re.exec(txt)) !== null) {
        var u = m[0];
        if (isPageUrl(u)) map[u] = true;
      }
    }
    return map;
  }

  function getScroller() {
    var node = document.querySelector('.outer_page_container') ||
               document.querySelector('.document_scroller') ||
               document.body;
    while (node && node !== document.documentElement) {
      var o = (getComputedStyle(node).overflowY || '') +
              (getComputedStyle(node).overflow || '');
      if (/(hidden|scroll|auto)/.test(o)) return node;
      node = node.parentElement;
    }
    return document.scrollingElement || document.documentElement;
  }

  function sleep(ms) {
    return new Promise(function (resolve) { setTimeout(resolve, ms); });
  }

  function getTitle() {
    var el = document.querySelector('[data-e2e="doc_page_title"]');
    if (el && el.innerText && el.innerText.trim()) {
      return el.innerText.trim();
    }
    return (document.title || '').replace(/\| Scribd/gi, '').trim();
  }

  async function harvest() {
    // Wait until the client-challenge page is replaced by the real document.
    for (var w = 0; w < 90; w++) {
      var title = (document.title || '').toLowerCase();
      var hasViewer = !!document.querySelector('.outer_page_container') ||
        document.querySelectorAll('img[src*="html.scribdassets"]').length > 0;
      if (title.indexOf('client challenge') === -1 && hasViewer) break;
      await sleep(1000);
    }

    var map = {};
    collect(map);
    var sc = getScroller();
    var stable = 0;

    for (var step = 0; step < MAX_STEPS; step++) {
      var before = Object.keys(map).length;
      var maxScroll = sc.scrollHeight - sc.clientHeight;
      if (maxScroll <= 0) break;
      if (sc.scrollTop < maxScroll) {
        sc.scrollTop = Math.min(
          sc.scrollTop + Math.max(sc.clientHeight, 200) * 0.9,
          maxScroll
        );
      } else {
        break;
      }
      await sleep(SLEEP_MS);
      collect(map);
      var after = Object.keys(map).length;
      if (after === before) {
        stable++;
        if (stable >= STABLE_LIMIT) break;
      } else {
        stable = 0;
      }
    }

    var urls = Object.keys(map);
    var result = {
      urls: urls,
      title: getTitle(),
      pages: document.querySelectorAll(
        "div.outer_page_container div[id^='outer_page_']"
      ).length || urls.length
    };
    window.__scribdHarvestResult = JSON.stringify(result);
    return true;
  }

  window.__scribdStartHarvest = function () {
    if (window.__scribdHarvestResult) return Promise.resolve(true);
    return harvest();
  };
})();
