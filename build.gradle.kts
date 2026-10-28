plugins {
    // Update dependensi bertahap (aturan 12): toolchain aktif AGP 9.4.1
    // dan Gradle 9.8.2; angka pastinya di katalog versi dan wrapper.
    // AGP 9 memakai built-in Kotlin (KGP dibundel, tidak perlu deklarasi KGP).
    alias(libs.plugins.android.application) apply false
}
