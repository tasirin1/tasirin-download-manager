plugins {
    // Update dependensi bertahap (item 9): AGP 8.x -> 9.0.1.
    // AGP 9 memakai built-in Kotlin (KGP dibundel, tidak perlu deklarasi KGP).
    // Butuh Gradle >= 9.6; major berikutnya di-ignore Dependabot.
    id("com.android.application") version "9.0.1" apply false
}
