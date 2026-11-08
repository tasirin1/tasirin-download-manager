/* Tasirin Download Manager — service worker minimal.
   Network-first untuk navigasi (konten selalu fresh),
   stale-while-revalidate untuk aset statis (manifest + ikon) supaya
   halaman tetap installable + ringan dan aset yang diganti tetap
   sampai ke pengguna terpasang (issue #1090). */
const CACHE = 'tasirin-dm-v1';
const PRECACHE = [
  // Dokumen root ikut di-precache (issue #1232): tanpanya fallback
  // offline navigasi caches.match('./') bisa kosong pada kunjungan
  // offline pertama yang dikendalikan SW, sebelum sempat ada
  // navigasi online yang menulisnya ke cache. Entri '.' di bawah
  // dipetakan pemasang menjadi './.' yang ternormalisasi ke URL
  // dokumen root — kunci yang sama dengan yang dicari fallback.
  '.',
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
  // Entri '.' dikecualikan dari uji SWR (issue #1318): pathname URL
  // selalu ternormalisasi sehingga tak pernah berakhiran '/.' —
  // cabang untuk '.' tak bisa cocok. Entri itu sendiri TETAP di
  // PRECACHE: ia melayani precache dokumen root untuk fallback
  // offline navigasi (#1232), bukan jalur SWR ini.
  if (PRECACHE.some((p) => p !== '.' && url.pathname.endsWith('/' + p))) {
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
        // Hanya respons OK yang masuk cache (issue #1175): tanpa
        // penjaga ini halaman galat sesaat (404/500 origin) menimpa
        // salinan baik dan disajikan sebagai fallback offline
        // berikutnya. Cabang aset statis di atas sudah menjaga sama.
        if (res && res.ok) {
          const copy = res.clone();
          // Masa hidup event diperpanjang sampai tulis cache selesai
          // (issue #1204, pola #1109 di cabang statis): tanpa
          // waitUntil, browser boleh menghentikan worker sesudah
          // respons disajikan dan salinan terbaru hilang diam-diam,
          // sehingga fallback offline tetap menyajikan versi lama.
          event.waitUntil(caches.open(CACHE).then((c) => c.put(req, copy)));
        }
        return res;
      }).catch(() => caches.match(req).then((hit) => hit || caches.match('./')))
    );
  }
});
