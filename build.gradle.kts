plugins {
    // Update dependensi bertahap (aturan 12): AGP 9.0.1 -> 9.1.1 (minor).
    // AGP 9 memakai built-in Kotlin (KGP dibundel, tidak perlu deklarasi KGP).
    // Butuh Gradle >= 9.6; major berikutnya di-ignore Dependabot.
    id("com.android.application") version "9.1.1" apply false
}
