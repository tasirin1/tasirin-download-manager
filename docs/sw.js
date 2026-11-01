/* Tasirin Download Manager — service worker minimal.
   Network-first untuk navigasi (konten selalu fresh),
   stale-while-revalidate untuk aset statis (manifest + ikon) supaya
   halaman tetap installable + ringan dan aset yang diganti tetap
   sampai ke pengguna terpasang (issue #1090). */
const CACHE = 'tasirin-dm-v1';
const PRECACHE = [
  'site.webmanifest',
  'icons/icon-192.png',
  'icons/icon-512.png'
];

self.addEventListener('install', (event) => {
  event.waitUntil(
    caches.open(CACHE)
      .then((cache) => cache.addAll(PRECACHE.map((p) => './' + p)))
      .then(() => self.skipWaiting())
  );
});

self.addEventListener('activate', (event) => {
  event.waitUntil(
    caches.keys()
      .then((keys) => Promise.all(keys.filter((k) => k !== CACHE).map((k) => caches.delete(k))))
      .then(() => self.clients.claim())
  );
});

self.addEventListener('fetch', (event) => {
  const req = event.request;
  if (req.method !== 'GET') return;
  const url = new URL(req.url);
  if (url.origin !== location.origin) return;

  // Aset statis: stale-while-revalidate (issue #1090). Cache-first
  // murni + nama cache beku ('tasirin-dm-v1') membuat ikon/manifest
  // yang diganti di docs/ tak pernah sampai ke pengguna terpasang:
  // service worker hanya memperbarui diri saat sw.js berubah, dan
  // activate hanya menghapus cache LAIN. Kini cache disajikan
  // secepatnya, tetapi setiap fetch juga menyegarkan cache dari
  // jaringan di latar — aset baru mendarat pada kunjungan
  // berikutnya tanpa ritual naik versi nama cache.
  if (PRECACHE.some((p) => url.pathname.endsWith('/' + p))) {
    event.respondWith(
      caches.open(CACHE).then((cache) => cache.match(req).then((hit) => {
        const refresh = fetch(req).then((res) => {
          if (res && res.ok) cache.put(req, res.clone());
          return res;
        }).catch(() => hit);
        // Masa hidup event diperpanjang sampai refresh selesai
        // (issue #1109): tanpa waitUntil, browser boleh menghentikan
        // worker sesudah respons cache disajikan dan penyegaran
        // terputus diam-diam — kunjungan berikut tetap basi.
        event.waitUntil(refresh.catch(() => {}));
        return hit || refresh;
      }))
    );
    return;
  }

  // Navigasi: network-first, fallback cache
  if (req.mode === 'navigate') {
    event.respondWith(
      fetch(req).then((res) => {
        const copy = res.clone();
        caches.open(CACHE).then((c) => c.put(req, copy));
        return res;
      }).catch(() => caches.match(req).then((hit) => hit || caches.match('./')))
    );
  }
});
