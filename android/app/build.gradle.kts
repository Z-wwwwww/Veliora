plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// 自签名密钥由 scripts/build-apk.sh 首次构建时生成（不入库）
val localKeystore = rootProject.file("keystore/veliora.jks")

android {
    namespace = "org.veliora.television"
    compileSdk = 35

    defaultConfig {
        applicationId = "org.veliora.television"
        minSdk = 26
        targetSdk = 35
        // versionCode 只增不减：电视上已装过 versionCode 2 的包，回退会导致无法覆盖安装
        versionCode = 4
        versionName = "1.0.1"
    }

    signingConfigs {
        create("release") {
            if (localKeystore.exists()) {
                storeFile = localKeystore
                storePassword = "veliora"
                keyAlias = "veliora"
                keyPassword = "veliora"
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // keystore 缺失时回退 debug 签名，保证 assembleRelease 总能出包
            signingConfig = if (localKeystore.exists())
                signingConfigs.getByName("release")
            else
                signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("androidx.webkit:webkit:1.12.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    // 原生播放器：MediaCodec 硬解直通，4K/HEVC 不受 WebView MSE 限制
    implementation("androidx.media3:media3-exoplayer:1.5.1")
    implementation("androidx.media3:media3-exoplayer-hls:1.5.1")
    implementation("androidx.media3:media3-ui:1.5.1")
}
