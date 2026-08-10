import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")  // built-in Kotlin sejak AGP 9 (KGP dibundel)
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
        // Hanya bahasa id/in (Indonesia) + en yang ikut di APK; locale library
        // lain (ar, de, fr, es, ...) dibuang dari resources.arsc — hemat ukuran.
        resConfigs += listOf("en", "in", "id")
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

    lint {
        // Pengaman kompatibilitas API 21: kegagalan lint (mis. NewApi) menggagalkan build.
        abortOnError = true
        // Cetak daftar warning lengkap ke stdout supaya terlihat di log CI.
        textReport = true
        textOutput = File("stdout")
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
    implementation("androidx.core:core-splashscreen:1.2.0")
    implementation("androidx.documentfile:documentfile:1.1.0")
    implementation("org.nanohttpd:nanohttpd:2.3.1")
    implementation("com.google.zxing:core:3.5.4")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.recyclerview:recyclerview:1.4.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.2")
    implementation("androidx.lifecycle:lifecycle-livedata-ktx:2.8.2")
    implementation("androidx.activity:activity-ktx:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.5")
    testImplementation("junit:junit:4.13.2")
}
