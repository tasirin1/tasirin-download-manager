plugins {
    // Update dependensi bertahap (aturan 12): AGP 9.2.1 -> 9.3.1 (minor).
    // AGP 9 memakai built-in Kotlin (KGP dibundel, tidak perlu deklarasi KGP).
    // Butuh Gradle >= 9.6; major berikutnya di-ignore Dependabot.
    alias(libs.plugins.android.application) apply false
}
