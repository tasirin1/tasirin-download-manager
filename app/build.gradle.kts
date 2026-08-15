import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")  // built-in Kotlin sejak AGP 9 (KGP dibundel)
    id("jacoco")                  // laporan cakupan unit test (toolVersion default Gradle)
}

android {
    namespace = "com.tasirin.httpdownloadmanager"
    // compileSdk 36 / targetSdk 36 (Android 16): unblocks lifecycle 2.11 / activity 1.13.
    // Perilaku runtime baru aktif saat targetSdk naik — lihat AGENTS.md.
    compileSdk = 36

    defaultConfig {
        applicationId = "com.tasirin.httpdownloadmanager"
        minSdk = 21
        // targetSdk 36: Android 5 (minSdk 21) tetap didukung penuh.
        // Android 5–10: WRITE_EXTERNAL_STORAGE + legacy storage bekerja penuh.
        // Android 11+: MANAGE_EXTERNAL_STORAGE ("Akses semua file").
        // targetSdk 35+: boot-start download lewat JobScheduler (BootResumeJobService)
        // karena dataSync FGS dilarang start dari BOOT_COMPLETED; edge-to-edge
        // ditangani applyEdgeToEdge di 4 aktivitas; predictive back default aktif
        // (tidak ada onBackPressed custom, pakai OnBackPressedDispatcher AndroidX).
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
    }

    // UI memakai Inggris (default values/); locale library lain (ar, de, fr,
    // es, ...) dibuang dari resources.arsc — hemat ukuran.
    androidResources {
        localeFilters += listOf("en")
    }

    signingConfigs {
        create("release") {
            val storeFileProp = project.findProperty("storeFile") as String?
            val storePasswordProp = project.findProperty("storePassword") as String?
            val keyAliasProp = project.findProperty("keyAlias") as String?
            val keyPasswordProp = project.findProperty("keyPassword") as String?
            if (!storeFileProp.isNullOrBlank() && !storePasswordProp.isNullOrBlank() &&
                !keyAliasProp.isNullOrBlank() && !keyPasswordProp.isNullOrBlank()
            ) {
                storeFile = rootProject.file(storeFileProp)  // resolve dari root repo (workflow menaruh keystore.jks di sana)
                storePassword = storePasswordProp
                keyAlias = keyAliasProp
                keyPassword = keyPasswordProp
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            val signing = signingConfigs.getByName("release")
            if (signing.storeFile != null && signing.storeFile!!.exists()) {
                signingConfig = signing
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        // Kotlin 2.4 me-resolve forEach ke default method Java (Iterable#forEach, API 24).
        // Desugaring membuatnya aman di minSdk 21 (Android 5+) tanpa mengubah kode.
        isCoreLibraryDesugaringEnabled = true
    }

    buildFeatures {
        viewBinding = true
    }

    packaging {
        resources.excludes += "META-INF/**"
    }

    lint {
        // Pengaman kompatibilitas API 21: kegagalan lint (mis. NewApi) menggagalkan build.
        abortOnError = true
        // Laporan lint selalu dibuat otomatis; warning tampil sebagai annotation CI.
        // Sengaja dinonaktifkan:
        // - OldTargetApi: targetSdk 36 sengaja (naik 37 menyusul setelah uji manual
        //   Android 16 sesuai peta jalan AGENTS.md), bukan bug.
        // - GradleDependency: update dependensi dikelola Dependabot (PR lewat CI).
        disable += setOf("OldTargetApi", "GradleDependency")
    }
}

kotlin {
    // DSL baru (KGP 2.x): menggantikan kotlinOptions yang dihapus di Kotlin 2.4+.
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.documentfile:documentfile:1.1.0")
    implementation("org.nanohttpd:nanohttpd:2.3.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.recyclerview:recyclerview:1.4.0")
    implementation("androidx.activity:activity-ktx:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.5")
    testImplementation("junit:junit:4.13.2")
    // zxing hanya untuk verifikasi decode QR di unit test — tidak ikut ke APK
    // (encoder QR asli ada di util/QrEncoder.kt).
    testImplementation("com.google.zxing:core:3.5.4")
    // org.json asli untuk unit test JVM (android.jar hanya stub). Test-only:
    // tidak ikut ke APK.
    testImplementation("org.json:json:20240303")
}

// --- JaCoCo coverage (unit test JVM) ---
// Ekstensi jacoco (plugin Gradle) menempel di semua task Test; exec data
// ditulis ke build/jacoco/testDebugUnitTest.exec. (AGP 9 tidak lagi
// mendukung blok testOptions.unitTests.all { jacoco { ... } }.)
tasks.withType<Test>().configureEach {
    extensions.configure<JacocoTaskExtension>("jacoco") {
        isIncludeNoLocationClasses = true
        // Hindari ClassNotFoundException di agent saat JDK memuat kelas hidden
        // (GeneratedSerializationConstructorAccessor) pada Java 17+.
        excludes = (excludes ?: emptyList()) + "jdk.internal.*"
    }
}

val jacocoExecData = layout.buildDirectory.file("jacoco/testDebugUnitTest.exec")
// AGP 9 (built-in Kotlin): kelas ada di built_in_kotlinc + javac, bukan
// tmp/kotlin-classes seperti AGP 8 + KGP.
val jacocoClassDirs = files(
    layout.buildDirectory.dir("intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes"),
    layout.buildDirectory.dir("intermediates/javac/debug/compileDebugJavaWithJavac/classes")
)

tasks.register<JacocoReport>("jacocoTestReport") {
    dependsOn("testDebugUnitTest")
    group = "verification"
    description = "Laporan cakupan unit test (JaCoCo)."
    reports {
        xml.required.set(true)
        html.required.set(false)
        csv.required.set(false)
    }
    classDirectories.setFrom(jacocoClassDirs)
    executionData.setFrom(jacocoExecData)
    sourceDirectories.setFrom(files("src/main/java"))
}

tasks.register<JacocoCoverageVerification>("jacocoTestCoverageVerification") {
    dependsOn("testDebugUnitTest")
    group = "verification"
    description = "Gagalkan build bila cakupan garis di bawah ambang (lihat CI)."
    executionData.setFrom(jacocoExecData)
    classDirectories.setFrom(jacocoClassDirs)
    sourceDirectories.setFrom(files("src/main/java"))
    violationRules {
        rule {
            limit {
                counter = "LINE"
                value = "COVEREDRATIO"
                minimum = "0.05".toBigDecimal()
            }
        }
    }
}
