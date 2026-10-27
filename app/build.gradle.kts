plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "io.github.webtvremote"
    compileSdk = 35

    defaultConfig {
        applicationId = "io.github.webtvremote"
        minSdk = 30
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"
    }

    // Release builds are signed with a keystore from env vars when present (CI),
    // otherwise with the debug key so a sideloadable APK is always produced.
    val releaseKeystore = System.getenv("WTR_KEYSTORE")?.let { file(it) }?.takeIf { it.exists() }
    signingConfigs {
        if (releaseKeystore != null) {
            create("release") {
                storeFile = releaseKeystore
                storePassword = System.getenv("WTR_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("WTR_KEY_ALIAS")
                keyPassword = System.getenv("WTR_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            // The injector class is started by name through app_process, so keep
            // minification off rather than maintaining keep rules for it.
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName(if (releaseKeystore != null) "release" else "debug")
        }
    }

    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    packaging {
        resources.excludes += setOf("META-INF/*.kotlin_module", "META-INF/versions/**")
    }
}

dependencies {
    implementation("org.nanohttpd:nanohttpd:2.3.1")
    implementation("dev.mobile:dadb:1.2.10")
    implementation("com.google.zxing:core:3.5.3")
}
