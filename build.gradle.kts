plugins {
    // Update dependensi bertahap (item 9): AGP 8.5.2 -> 8.7.3, Kotlin 1.9.24 -> 1.9.25.
    // AGP naik bertahap lewat CI (major 9.x di-ignore Dependabot, ditangani manual).
    id("com.android.application") version "8.7.3" apply false
    id("org.jetbrains.kotlin.android") version "2.4.10" apply false
}
