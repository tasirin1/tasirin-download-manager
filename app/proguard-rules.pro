# Aturan ProGuard/R8 rilis — dijaga seminimal mungkin.
# Rilis memakai R8 (isMinifyEnabled=true) jadi aturan berikut menjaga
# kelas yang dipakai lewat refleksi / nama kelas (JNI tidak ada).

# CrashLog & ServerLog memakai stack trace / SimpleBacktrace via refleksi
# pada nama class — jaga nama kelas agar output error mudah dibaca.
-keepnames class com.tasirin.httpdownloadmanager.util.CrashLog
-keepnames class com.tasirin.httpdownloadmanager.remote.ServerLog

# NanoHTTPD menentukan tipe MIME berdasar nama kelas internal & refleksi;
# keep seluruh paket milik nanohttpd.
-keep class fi.iki.elonen.** { *; }

# HttpControlServer & helper remote dipanggil lewat handler refleksi dari
# NanoHTTPD serve() — jaga entry-point supaya tidak diinlining.
-keep class com.tasirin.httpdownloadmanager.remote.HttpControlServer { *; }

# kotlinx.coroutines: main dispatcher & exception handler di-resolve lewat
# ServiceLoader / refleksi.
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler
