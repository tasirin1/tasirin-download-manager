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
    _handlers: {},
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
    addEventListener(type, fn) { (this._handlers[type] = this._handlers[type] || []).push(fn); },
    removeEventListener() {},
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
    _handlers: {},
    addEventListener(type, fn) { (this._handlers[type] = this._handlers[type] || []).push(fn); },
    removeEventListener() {},
    innerWidth: 800, innerHeight: 600,
    scrollY: 0,
    scrollTo() {},
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
  Image: function () { this.src = ''; },
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

// --- Tes navigasi & breadcrumb File Manager (guard regresi) ---
function fsExpect(cond, label) {
  if (!cond) {
    console.error('FAIL: ' + label);
    process.exit(1);
  }
}

fsExpect(sandbox.parentFsPath('f:/sdcard/Download/APK') === 'f:/sdcard/Download', 'parentFsPath naik 1 level (f:)');
fsExpect(sandbox.parentFsPath('f:/sdcard') === '', 'parentFsPath dari f:/sdcard ke root');
fsExpect(sandbox.parentFsPath('f:') === '', 'parentFsPath f: -> kosong');
fsExpect(sandbox.parentFsPath('') === '', 'parentFsPath kosong -> kosong');
fsExpect(sandbox.parentFsPath('m:a/b/c') === 'm:a/b', 'parentFsPath naik 1 level (m:)');
fsExpect(sandbox.parentFsPath('m:a') === '', 'parentFsPath m: level 1 -> kosong');

const parts = sandbox.fsCrumbParts('f:/sdcard/Download/APK');
fsExpect(parts.length === 4, 'fsCrumbParts menghasilkan 4 bagian');
fsExpect(parts[0].path === '' && parts[parts.length - 1].label === 'APK', 'fsCrumbParts root + label terakhir');
fsExpect(parts[parts.length - 1].path === 'f:/sdcard/Download/APK', 'fsCrumbParts path terakhir benar');
const long = sandbox.collapseCrumbs(sandbox.fsCrumbParts('f:/a/b/c/d/e/f'));
fsExpect(long.length === 4 && long[1].dots === true, 'collapseCrumbs memendekkan breadcrumb panjang');

function fireWindow(type) {
  (sandbox.window._handlers[type] || []).forEach(function (h) {
    h({ preventDefault() {}, stopPropagation() {} });
  });
}
function fireEl(el, type) {
  (el._handlers[type] || []).forEach(function (h) {
    h({ preventDefault() {}, stopPropagation() {} });
  });
}

// Back-stack & popstate (Back Android/browser = naik folder)
vm.runInContext('fsPath = ""; fsBackStack.length = 0;', sandbox);
sandbox.location.hash = '#/files';
sandbox.fsNavigate('f:/sdcard');
sandbox.fsNavigate('f:/sdcard/Download');
fsExpect(vm.runInContext('fsBackStack.length', sandbox) === 2, 'back stack mencatat 2 folder');
fsExpect(vm.runInContext('fsPath', sandbox) === 'f:/sdcard/Download', 'fsPath mengikuti navigasi');
fireWindow('popstate');
fsExpect(vm.runInContext('fsPath', sandbox) === 'f:/sdcard', 'Back kembali ke folder sebelumnya');
fireWindow('popstate');
fsExpect(vm.runInContext('fsPath', sandbox) === '', 'Back kembali ke root storage');
fireWindow('popstate');
fsExpect(vm.runInContext('fsPath', sandbox) === '', 'Back di root tidak mengubah folder');

// Tombol Up & Home
vm.runInContext('fsPath = "f:/sdcard/Download"; fsBackStack.length = 0;', sandbox);
fireEl(elements['fsUpBtn'], 'click');
fsExpect(vm.runInContext('fsPath', sandbox) === 'f:/sdcard', 'tombol Up naik 1 folder');
fireEl(elements['fsHomeBtn'], 'click');
fsExpect(vm.runInContext('fsPath', sandbox) === '', 'tombol Home kembali ke root');

// Tombol Downloads di tab bar (guard regresi: tab Downloads sempat tanpa
// handler klik sehingga tidak bisa kembali dari File Manager/Galeri).
sandbox.location.hash = '#/files';
fireEl(elements['tabDownloads'], 'click');
fsExpect(sandbox.location.hash === '#/', 'tab Downloads kembali ke halaman download');

// Bilah status dihapus: applyServerStatus mempertahankan flag read-only
// dan banner pindah port tanpa elemen status-card.
vm.runInContext('serverReadOnly = false;', sandbox);
sandbox.applyServerStatus({ readOnly: true, appVersion: '1.0' });
fsExpect(vm.runInContext('serverReadOnly', sandbox) === true, 'applyServerStatus menetapkan serverReadOnly');
sandbox.location.port = '8082';
sandbox.applyServerStatus({ port: 8081, appVersion: '1.0' });
fsExpect(elements['connBannerText'].textContent.indexOf('Server moved to port 8081') >= 0,
  'applyServerStatus memunculkan banner pindah port');
delete sandbox.location.port;
vm.runInContext('serverReadOnly = false;', sandbox);

// Badge NEW untuk file yang baru di-upload
vm.runInContext('fsPath = "f:/sdcard"; fsNewBadges = {};', sandbox);
sandbox.fsMarkNew('f:/sdcard', 'video.mp4');
fsExpect(sandbox.fsIsNew('video.mp4') === true, 'badge NEW muncul untuk file baru');
fsExpect(sandbox.fsIsNew('lama.mp4') === false, 'badge NEW tidak muncul untuk file lain');

// --- Tes math zoom penampil foto (titik sentuh dipertahankan) ---
const mmImgEl = elements['mmImage'];
mmImgEl.offsetWidth = 800;
mmImgEl.offsetHeight = 600;
const mmBodyEl = elements['mmBody'];
mmBodyEl.clientWidth = 800;
mmBodyEl.clientHeight = 600;
mmBodyEl.getBoundingClientRect = function () { return { top: 0, left: 0, width: 800, height: 600 }; };
// Spy classList mediaModal: verifikasi tombol penampil disembunyikan saat
// zoom aktif dan muncul lagi saat kembali ke ukuran penuh.
const mmChromeLog = [];
elements['mediaModal'].classList = {
  add: function (c) { mmChromeLog.push('+' + c); },
  remove: function (c) { mmChromeLog.push('-' + c); },
  toggle: function (c) { mmChromeLog.push('~' + c); },
  contains: function () { return false; }
};
vm.runInContext('mmType = "image"; mmImgZoom = { s: 1, tx: 0, ty: 0 };', sandbox);
sandbox.mmImgZoomTo(400, 300, 2.5);
fsExpect(Math.abs(vm.runInContext('mmImgZoom.s', sandbox) - 2.5) < 0.001, 'zoom foto in 2.5x');
fsExpect(Math.abs(vm.runInContext('mmImgZoom.tx', sandbox) + 600) < 1, 'zoom foto menahan titik sentuh (tx)');
// Pan saat ter-zoom: clamp tidak boleh memaksa kembali ke tengah (regresi
// sebelumnya: gambar selalu dikunci di posisi tengah sehingga tidak bisa
// digeser untuk melihat area tertentu).
vm.runInContext('mmImgZoom.tx = -300; mmImgZoom.ty = -200;', sandbox);
sandbox.mmImgClamp();
fsExpect(Math.abs(vm.runInContext('mmImgZoom.tx', sandbox) + 300) < 1, 'pan foto bergeser ke kiri (tx dipertahankan)');
fsExpect(Math.abs(vm.runInContext('mmImgZoom.ty', sandbox) + 200) < 1, 'pan foto bergeser ke atas (ty dipertahankan)');
vm.runInContext('mmImgZoom.tx = -5000;', sandbox);
sandbox.mmImgClamp();
fsExpect(Math.abs(vm.runInContext('mmImgZoom.tx', sandbox) + 1200) < 1, 'pan foto dikunci di tepi (tx max)');
sandbox.mmImgZoomTo(400, 300, 1);
fsExpect(vm.runInContext('mmImgZoom.s', sandbox) === 1, 'zoom foto out reset ke 1');
fsExpect(vm.runInContext('mmImgZoom.tx', sandbox) === 0, 'zoom foto out tx kembali 0');
fsExpect(mmChromeLog.indexOf('+mm-chrome-hidden') >= 0, 'zoom aktif menyembunyikan tombol penampil');
fsExpect(mmChromeLog.indexOf('-mm-chrome-hidden') >= 0, 'kembali 1x menampilkan tombol penampil');

// Reset state agar tes upload di bawah tidak terpengaruh
vm.runInContext('fsPath = ""; fsBackStack.length = 0;', sandbox);

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
