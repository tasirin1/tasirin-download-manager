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
const blocks = [...html.matchAll(/<script[^>]*>([\s\S]*?)<\/script>/g)];
// Blok utama dipilih dari ISINYA, bukan indeksnya (issue #612): pola
// di atas menangkap semua tag script termasuk yang beratribut dan
// eksternal, jadi satu <script src=...> sebelum blok utama akan
// menggeser indeks dan uji meledak dengan TypeError yang menunjuk
// fungsi hilang — bukan penyebab sebenarnya. Skrip eksternal
// berbadan kosong tak akan pernah memuat penanda ini.
// Jangkarnya DEFINISI di awal baris, bukan teks penanda di mana pun
// (issue #627): komentar/dokumentasi yang menyebut nama fungsinya di
// blok lebih awal dulu bisa membajak find() sehingga blok bootstrap
// yang tereksekusi dan uji meledak dengan TypeError menyesatkan
// yang sama seperti pra-#612. Di antara blok yang memuat definisi
// sungguhan, pilih yang terbesar (blok utama).
const defRe = /^\s*function startFsUpload\s*\(/m;
const mainBlock = blocks
  .filter((b) => defRe.test(b[1]))
  .sort((a, b) => b[1].length - a[1].length)[0];
if (!mainBlock) {
  console.error('FAIL: blok <script> utama (memuat startFsUpload) tidak ditemukan di remote.src.html');
  process.exit(1);
}
const main = mainBlock[1];

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

// Stub snapshot/SSE (issue #1406): jalur snapshot (pollSnapshot,
// refresh, SSE) dulu tak teruji sama sekali — fetch menggantung dan
// EventSource bisu, jadi skrip yang rusak di jalur itu tetap hijau.
// fetch kini menyelesaikan JSON kalengan untuk /api/snapshot dan
// /api/downloads (URL lain tetap menggantung seperti sebelumnya);
// resolver /api/downloads ditahan agar tes bisa menyelesaikan
// snapshot yang basi SESUDAH SSE yang lebih baru diterapkan.
const eventSources = [];
const downloadsResolvers = [];
function snapItem(id, name) {
  return {
    id: id, fileName: name, url: 'https://example.com/' + name,
    addedAt: 1700000000000, state: 'COMPLETED', bytesDownloaded: 1000,
    totalBytes: 1000, progress: 100, speedBps: 0, etaSeconds: 0,
    speedLimitKbps: 0, finishedAt: 1700000001000, priority: 0,
    retryCount: 0, checksumVerified: false, progressPercentOverride: -1
  };
}
function jsonRes(obj) {
  return {
    ok: true, status: 200,
    json: () => Promise.resolve(obj),
    text: () => Promise.resolve(JSON.stringify(obj))
  };
}
function stubFetch(url) {
  const u = String(url);
  if (u.indexOf('/api/snapshot') >= 0) {
    return Promise.resolve(jsonRes({ items: [snapItem('snap1', 'snap.mp4')] }));
  }
  if (u.indexOf('/api/downloads') >= 0) {
    return new Promise((resolve) => { downloadsResolvers.push(resolve); });
  }
  return new Promise(() => {});
}

class FakeXHR {
  constructor() { this.upload = {}; this.headers = {}; }
  open(m, u) { this.method = m; this.url = u; }
  setRequestHeader(name, value) { this.headers[name] = value; }
  send(body) {
    requests.push({
      method: this.method,
      url: this.url,
      bodySize: body && body.size ? body.size : 0,
      headers: this.headers
    });
    const u = new URL(this.url, 'http://x');
    const id = u.searchParams.get('id');
    const verify = u.searchParams.get('verify');
    const self = this;
    if (this.method === 'GET') {
      // upload_verify wajib membawa tanda tangan status dari respons chunk.
      if (!verify) {
        this.status = 200;
        this.responseText = JSON.stringify({ ok: false });
        queueMicrotask(() => self.onload && self.onload());
        return;
      }
      this.status = 200;
      this.responseText = JSON.stringify({ ok: true, name: 'test.bin' });
      queueMicrotask(() => self.onload && self.onload());
      return;
    }
    const chunk = parseInt(u.searchParams.get('chunk'), 10);
    const chunks = parseInt(u.searchParams.get('chunks'), 10);
    const verifyToken = 'verify-' + id + '-' + chunk;
    if (chunk === chunks - 1) {
      this.status = 200;
      this.responseText = JSON.stringify({
        ok: true, pending: true, name: 'test.bin', verify: verifyToken
      });
    } else {
      this.status = 200;
      this.responseText = JSON.stringify({ ok: true, name: 'test.bin', verify: verifyToken });
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
  EventSource: Object.assign(function (url) {
    this.url = url;
    this.readyState = 1;
    this.onopen = null;
    this.onmessage = null;
    this.onerror = null;
    this.addEventListener = function () {};
    this.close = function () { this.readyState = 2; };
    eventSources.push(this);
  }, { CONNECTING: 0, OPEN: 1, CLOSED: 2 }),
  fetch: stubFetch,
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

// Tombol Home (tombol Up dihapus atas keputusan owner — ikonnya
// tertukar dengan Upload File; naik folder lewat breadcrumb/back.
// Helper parentFsPath ikut dihapus pada issue #583: pemanggil
// terakhirnya adalah tombol Up itu, jadi tak ada pengguna nyata).
vm.runInContext('fsPath = "f:/sdcard/Download"; fsBackStack.length = 0;', sandbox);
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


// --- Tes snapshot + SSE (issue #1406) ---
// Tiga asersi: (1) pollSnapshot menerapkan snapshot kalengan tanpa
// error, (2) pesan SSE diterapkan dan render-nya tidak melempar
// (sseFailCount tetap 0), (3) snapshot basi yang selesai belakangan
// (refresh yang fetch-nya ditahan) TIDAK menimpa state SSE yang lebih
// baru — penjaga snapSeq/snapApplied bekerja.
let snapshotDone = false;
async function runSnapshotTests() {
  sandbox.location.hash = '#/';
  await sandbox.pollSnapshot();
  let names = vm.runInContext('lastItems.map(function (i) { return i.fileName; }).join(",")', sandbox);
  fsExpect(names === 'snap.mp4', 'pollSnapshot menerapkan snapshot kalengan (dapat: ' + names + ')');

  const es = eventSources[eventSources.length - 1];
  fsExpect(!!es && typeof es.onmessage === 'function', 'EventSource bootstrap terhubung dan onmessage terpasang');
  es.onmessage({ data: JSON.stringify({ items: [snapItem('sse1', 'sse-new.mp4')] }) });
  fsExpect(vm.runInContext('sseFailCount', sandbox) === 0, 'render pesan SSE tidak melempar');
  names = vm.runInContext('lastItems.map(function (i) { return i.fileName; }).join(",")', sandbox);
  fsExpect(names === 'sse-new.mp4', 'pesan SSE menerapkan state terbaru (dapat: ' + names + ')');

  // refresh() dimulai (seq kecil), SSE kedua tiba lebih dulu
  // (snapApplied melompat), lalu fetch refresh dilepas dengan data
  // basi — hasilnya wajib dibuang.
  const pendingRefresh = sandbox.refresh().catch(function () {});
  await new Promise(function (r) { setTimeout(r, 20); });
  es.onmessage({ data: JSON.stringify({ items: [snapItem('sse2', 'sse-newer.mp4')] }) });
  const release = downloadsResolvers[downloadsResolvers.length - 1];
  if (release) release(jsonRes({ items: [snapItem('stale1', 'stale.mp4')] }));
  await pendingRefresh;
  await new Promise(function (r) { setTimeout(r, 20); });
  names = vm.runInContext('lastItems.map(function (i) { return i.fileName; }).join(",")', sandbox);
  fsExpect(names === 'sse-newer.mp4', 'snapshot basi tidak menimpa state SSE terbaru (dapat: ' + names + ')');
  snapshotDone = true;
}
runSnapshotTests().catch(function (e) {
  console.error('FAIL: tes snapshot/SSE melempar: ' + (e && e.message));
  process.exit(1);
});

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

// Vonis menunggu SINYAL selesai, bukan jendela tetap 200 ms (issue
// #1091): polling singkat menilai begitu pesan Done muncul, dengan
// batas keras 5 detik untuk vonis gagal — runner CI yang terbebani
// tak lagi gagal palsu, dan runner cepat tak membayar 200 ms penuh.
const verdictDeadline = Date.now() + 5000;
const verdictTimer = setInterval(() => {
  const doneMsg = msgs.some((m) => m.text.indexOf('Done: 2 files uploaded') === 0);
  if (!(doneMsg && snapshotDone) && Date.now() < verdictDeadline) return;
  clearInterval(verdictTimer);
  verdict();
}, 25);

function verdict() {
  if (!snapshotDone) {
    console.error('FAIL: tes snapshot/SSE belum selesai dalam batas waktu');
    process.exit(1);
  }
  const uploads = requests.filter((r) => r.method === 'POST' && r.url.includes('/api/upload'));
  const csrfReady = uploads.every((r) => r.headers['X-Requested-With'] === 'XMLHttpRequest');
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
  if (!csrfReady) {
    console.error('FAIL: upload POST tidak membawa header anti-CSRF');
    process.exit(1);
  }
  // Aseri urutan/identitas chunk + verify per berkas (issue #1070):
  // vonis lama hanya menghitung POST dan pesan Done, jadi regresi
  // yang mengirim chunk 0 berulang / salah urut / id tertukar antar
  // berkas tetap lulus walau server nyata merakit berkas korup.
  const CHUNK = (() => { try { return vm.runInContext('UPLOAD_CHUNK', sandbox); } catch (e) { return 2 * 1024 * 1024; } })();
  const expectSize = { 'a.txt': 1000, 'b.bin': 5 * 1024 * 1024 + 12345 };
  const byId = {};
  uploads.forEach((r, i) => {
    const u = new URL(r.url, 'http://x');
    const id = u.searchParams.get('id');
    (byId[id] = byId[id] || []).push({ r, i, u });
  });
  const ids = Object.keys(byId);
  if (ids.length !== 2) {
    console.error('FAIL: diharapkan 2 id upload berbeda, dapat ' + ids.length);
    process.exit(1);
  }
  for (const id of ids) {
    const list = byId[id];
    const name = list[0].u.searchParams.get('name');
    const chunks = parseInt(list[0].u.searchParams.get('chunks'), 10);
    const wantChunks = Math.ceil(expectSize[name] / CHUNK);
    if (chunks !== wantChunks || list.length !== chunks) {
      console.error('FAIL: ' + name + ': jumlah chunk ' + list.length + '/' + chunks + ', diharapkan ' + wantChunks);
      process.exit(1);
    }
    let sum = 0;
    list.forEach((e, k) => {
      const ci = parseInt(e.u.searchParams.get('chunk'), 10);
      const off = parseInt(e.u.searchParams.get('offset'), 10);
      if (ci !== k) {
        console.error('FAIL: ' + name + ': chunk ke-' + k + ' berlabel ' + ci + ' (duplikat/salah urut)');
        process.exit(1);
      }
      if (off !== ci * CHUNK) {
        console.error('FAIL: ' + name + ': offset chunk ' + ci + ' = ' + off + ', diharapkan ' + (ci * CHUNK));
        process.exit(1);
      }
      if (e.u.searchParams.get('name') !== name || e.u.searchParams.get('id') !== id) {
        console.error('FAIL: ' + name + ': identitas chunk tidak konsisten dalam satu id');
        process.exit(1);
      }
      const wantSize = ci === chunks - 1 ? expectSize[name] - ci * CHUNK : CHUNK;
      if (e.r.bodySize !== wantSize) {
        console.error('FAIL: ' + name + ': ukuran chunk ' + ci + ' = ' + e.r.bodySize + ', diharapkan ' + wantSize);
        process.exit(1);
      }
      sum += e.r.bodySize;
    });
    if (sum !== expectSize[name]) {
      console.error('FAIL: ' + name + ': total byte ' + sum + ' != ukuran berkas ' + expectSize[name]);
      process.exit(1);
    }
    // Tepat satu GET upload_verify per berkas, membawa token dari
    // respons chunk terakhir, dan terjadi SESUDAH chunk terakhir.
    const vers = requests.map((r, i) => ({ r, i })).filter((e) =>
      e.r.method === 'GET' && e.r.url.includes('/api/upload_verify') &&
      new URL(e.r.url, 'http://x').searchParams.get('id') === id);
    if (vers.length !== 1) {
      console.error('FAIL: ' + name + ': GET upload_verify terjadi ' + vers.length + 'x, diharapkan 1x');
      process.exit(1);
    }
    const vtok = new URL(vers[0].r.url, 'http://x').searchParams.get('verify');
    if (vtok !== 'verify-' + id + '-' + (chunks - 1)) {
      console.error('FAIL: ' + name + ': token verify ' + vtok + ' bukan token chunk terakhir');
      process.exit(1);
    }
    if (vers[0].i < list[list.length - 1].i) {
      console.error('FAIL: ' + name + ': verify terjadi sebelum chunk terakhir terkirim');
      process.exit(1);
    }
  }
  console.log('SMOKE OK: upload 2 file selesai (' + uploads.length + ' chunk POST terlindungi)');
  process.exit(0);
}
