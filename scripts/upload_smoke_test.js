// Smoke test alur upload halaman remote (tanpa dependensi, cukup Node builtin).
// Dipanggil dari scripts/prepare_remote.py --check (CI). Baca remote.src.html,
// jalankan skrip utama dengan stub DOM/XHR, lalu mulai upload lewat jalur nyata
// (startFsUpload). Guard ini ada karena pemanggilan uploadFiles() pernah
// tertukar urutan argumen (fsProgressList dikirim sebagai `done`, callback
// sebagai `listEl`) sehingga upload selalu gagal diam-diam dengan
// "listEl.appendChild is not a function".
'use strict';
const fs = require('fs');
const path = require('path');
const vm = require('vm');

const SRC = path.resolve(__dirname, '..', 'remote.src.html');
const html = fs.readFileSync(SRC, 'utf8');
const blocks = [...html.matchAll(/<script>([\s\S]*?)<\/script>/g)];
if (blocks.length < 2) {
  console.error('FAIL: remote.src.html tidak punya blok <script> utama');
  process.exit(1);
}
const main = blocks[1][1];

function makeEl(id) {
  return {
    id: id || '',
    _text: '',
    style: {},
    dataset: {},
    value: '',
    files: [],
    disabled: false,
    checked: false,
    classList: { add() {}, remove() {}, toggle() {}, contains() { return false; } },
    children: [],
    set textContent(v) { this._text = String(v); },
    get textContent() { return this._text; },
    set innerHTML(v) { this._html = String(v); this.children = []; },
    get innerHTML() { return this._html; },
    appendChild(c) { this.children.push(c); return c; },
    querySelector() { return makeEl(); },
    querySelectorAll() { return []; },
    addEventListener() {}, removeEventListener() {},
    setAttribute() {}, getAttribute() { return null; },
    closest() { return null; },
    focus() {}, click() {}, scrollIntoView() {},
    getBoundingClientRect() { return { top: 0, height: 0 }; },
  };
}

const elements = {};
const requests = [];

class FakeXHR {
  constructor() { this.upload = {}; }
  open(m, u) { this.method = m; this.url = u; }
  setRequestHeader() {}
  send(body) {
    requests.push({ method: this.method, url: this.url, bodySize: body && body.size ? body.size : 0 });
    const u = new URL(this.url, 'http://x');
    const id = u.searchParams.get('id');
    const self = this;
    if (this.method === 'GET') {
      // upload_verify: langsung "selesai".
      this.status = 200;
      this.responseText = JSON.stringify({ ok: true, name: 'test.bin' });
      queueMicrotask(() => self.onload && self.onload());
      return;
    }
    const chunk = parseInt(u.searchParams.get('chunk'), 10);
    const chunks = parseInt(u.searchParams.get('chunks'), 10);
    if (chunk === chunks - 1) {
      this.status = 200;
      this.responseText = JSON.stringify({ ok: true, pending: true, name: 'test.bin' });
    } else {
      this.status = 200;
      this.responseText = JSON.stringify({ ok: true, name: 'test.bin' });
    }
    queueMicrotask(() => self.onload && self.onload());
  }
}

const sandbox = {
  console,
  document: {
    getElementById(id) { return elements[id] || (elements[id] = makeEl(id)); },
    createElement() { return makeEl(); },
    querySelector() { return makeEl(); },
    querySelectorAll() { return []; },
    addEventListener() {}, removeEventListener() {},
    visibilityState: 'visible',
    title: '',
    documentElement: makeEl('documentElement'),
    body: makeEl('body'),
    head: makeEl('head'),
  },
  window: {
    addEventListener() {}, removeEventListener() {},
    innerWidth: 800, innerHeight: 600,
    location: { search: '', pathname: '/', hash: '' },
  },
  location: { search: '', pathname: '/', hash: '', reload() {} },
  history: { replaceState() {}, pushState() {} },
  navigator: { userAgent: 'smoke-test', sendBeacon() {} },
  localStorage: { getItem() { return null; }, setItem() {}, removeItem() {} },
  sessionStorage: { getItem() { return null; }, setItem() {}, removeItem() {} },
  EventSource: function () {
    this.readyState = 1;
    this.addEventListener = function () {};
    this.close = function () { this.readyState = 2; };
  },
  fetch: () => new Promise(() => {}),
  XMLHttpRequest: FakeXHR,
  FileReader: function () {},
  URLSearchParams,
  URL,
  Blob: function () {},
  File: function () {},
  setTimeout, clearTimeout, setInterval, clearInterval, queueMicrotask,
  requestAnimationFrame() {}, cancelAnimationFrame() {},
  alert() {}, confirm() { return true; },
};

try {
  vm.runInNewContext(main, sandbox, { filename: 'remote-main.js' });
} catch (e) {
  console.error('FAIL: skrip remote tidak bisa dimuat: ' + (e && e.message));
  process.exit(1);
}

// Spy fsMsg untuk mendeteksi pesan selesai (callback done startFsUpload).
const msgs = [];
sandbox.fsMsg = function (text, isErr) { msgs.push({ text: String(text), isErr: !!isErr }); };

// 1 file kecil (1 chunk) + 1 file besar multi-chunk.
const jobs = sandbox.fsUploadJobsFrom([
  { name: 'a.txt', size: 1000, webkitRelativePath: '', slice: (s, e) => ({ size: e - s }) },
  { name: 'b.bin', size: 5 * 1024 * 1024 + 12345, webkitRelativePath: '', slice: (s, e) => ({ size: e - s }) },
]);

try {
  sandbox.startFsUpload(jobs);
} catch (e) {
  console.error('FAIL: startFsUpload melempar: ' + (e && e.message));
  process.exit(1);
}

setTimeout(() => {
  const uploads = requests.filter((r) => r.method === 'POST' && r.url.includes('/api/upload'));
  const okDone = msgs.some((m) => m.text.indexOf('Done: 2 files uploaded') === 0);
  const failMsg = msgs.find((m) => m.text.indexOf('Upload failed to start') === 0);
  if (!uploads.length) {
    console.error('FAIL: tidak ada request /api/upload' + (failMsg ? ' — ' + failMsg.text : ''));
    process.exit(1);
  }
  if (!okDone) {
    console.error('FAIL: upload tidak selesai (tidak ada pesan Done)');
    process.exit(1);
  }
  console.log('SMOKE OK: upload 2 file selesai (' + uploads.length + ' chunk POST)');
  process.exit(0);
}, 200);
