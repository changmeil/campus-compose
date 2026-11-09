plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.bhu.campus"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.bhu.campus"
        minSdk = 26
        targetSdk = 37
        versionCode = 81
        versionName = "5.17.6"
    }

    signingConfigs {
        create("local") {
            storeFile = rootProject.file("keystore/campus.jks")
            storePassword = "campus123"
            keyAlias = "campus"
            keyPassword = "campus123"
            enableV3Signing = true
            enableV4Signing = true
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.getByName("local")
        }
        debug {
            signingConfig = signingConfigs.getByName("local")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.core:core-ktx:1.19.1")

    // 本地识别相册里的二维码（模型随 APK 打包，不联网、不依赖 GMS）
    implementation("com.google.mlkit:barcode-scanning:17.3.0")

    val miuix = "0.9.4"
    implementation("top.yukonga.miuix.kmp:miuix-ui:$miuix")
    implementation("top.yukonga.miuix.kmp:miuix-icons:$miuix")

    // 内置的 Xposed 模块（拼多多驿站直通）。只编译期引用，运行时由 LSPosed 注入，
    // 所以用 compileOnly —— 打进 APK 反而会和 LSPosed 的类冲突。
    compileOnly("de.robv.android.xposed:api:82")
}
