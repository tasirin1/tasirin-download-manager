plugins {
    // Update dependensi bertahap (item 9): AGP 8.x -> 9.0.1, Kotlin 2.4.10.
    // AGP 9.x butuh Gradle >= 9.6; major berikutnya di-ignore Dependabot.
    id("com.android.application") version "9.0.1" apply false
    id("org.jetbrains.kotlin.android") version "2.4.10" apply false
}
