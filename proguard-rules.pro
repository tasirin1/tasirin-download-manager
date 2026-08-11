# Aturan R8 minimal — sengaja ringan: komponen di manifest (activity,
# service, receiver) dan entri resource otomatis di-keep oleh AGP.
#
# R8 otomatis menulis daftar kode yang dibuang ke
# build/outputs/mapping/release/usage.txt setiap build rilis —
# dipakai untuk audit kode mati (jangan commit hasil auditnya).
#
# Jangan tambah -keep yang terlalu luas: memperbesar APK.

# Gabungkan interface implementasi yang identik (hemat sebagian kecil APK).
# Aman: aplikasi tidak bergantung pada identitas interface runtime.
-mergeinterfacesaggressively
