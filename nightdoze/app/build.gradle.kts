import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// release 签名凭据。工具脚本 tools/build_release.sh 生成此文件；不提交版本库。
// 文件不存在时（日常 debug 构建）静默跳过，release 包退化为未签名。
// 路径与口令不写死在本文件里，避免误提交。
val releaseSigningProps = rootProject.file("keystore.gradle.properties")
    .takeIf { it.exists() }
    ?.let { file -> Properties().apply { file.inputStream().use { load(it) } } }

android {
    namespace = "com.nightdoze.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.nightdoze.app"
        minSdk = 26       // Android 8.0：NotificationChannel / AudioManager 播放回调 的起点
        targetSdk = 34    // Android 14：specialUse 前台服务类型强制声明的起点
        versionCode = 1
        versionName = "0.1.0"

        ndk {
            // DataStore 带一个原生库 libdatastore_shared_counter.so，不过滤的话四个 ABI
            // 会被一起打进 APK（实测 8.65MB，其中一大半是三个用不上的架构）。
            // 侧载自用，真机是 arm64-v8a；要装 32 位老机器时再把 "armeabi-v7a" 加回来。
            abiFilters += "arm64-v8a"
        }
    }

    signingConfigs {
        if (releaseSigningProps != null) {
            create("release") {
                storeFile = rootProject.file(releaseSigningProps.getProperty("storeFile"))
                storePassword = releaseSigningProps.getProperty("storePassword")
                keyAlias = releaseSigningProps.getProperty("keyAlias")
                keyPassword = releaseSigningProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // 没有签名配置时保持未签名，不报错——让没有密钥的人也能跑 assembleRelease
            signingConfig = signingConfigs.findByName("release")
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

// 依赖刻意压到最小：无网络库、无图片库、无第三方 SDK（PRD 8 隐私要求）
dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.0")

    implementation(platform("androidx.compose:compose-bom:2024.06.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")

    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.2")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.2")

    implementation("androidx.datastore:datastore-preferences:1.1.1")
}
