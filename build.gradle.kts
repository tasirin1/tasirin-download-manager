plugins {
    // Update dependensi bertahap (item 9): AGP 8.5.2 -> 8.7.3 -> 8.13.2, Kotlin 2.4.10.
    // AGP 9.x menyusul manual bertahap (di-ignore Dependabot).
    id("com.android.application") version "8.13.2" apply false
    id("org.jetbrains.kotlin.android") version "2.4.10" apply false
}
