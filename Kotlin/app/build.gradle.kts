plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.setsuodu.feiq"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.setsuodu.feiq"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"

        // JPush
        manifestPlaceholders["JPUSH_PKGNAME"] = applicationId!!
        manifestPlaceholders["JPUSH_APPKEY"] = "056f5f11f3832f86529e86b4"   // ← 改成真实值
        manifestPlaceholders["JPUSH_CHANNEL"] = "developer-default"
        // 小米厂商通道（xiaomi 插件的 aar 里用 ${XIAOMI_APPID}/${XIAOMI_APPKEY} 占位，必须在这里提供）
        // 5.5.3 起不需要 "MI-" 前缀；下面是你原 Manifest 里的值，换成小米开放平台的真实值
        manifestPlaceholders["XIAOMI_APPID"] = "2882303761517422222"
        manifestPlaceholders["XIAOMI_APPKEY"] = "5111742222222"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }
    buildFeatures {
        compose = true
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.10.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    // WebSocket
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // Media3 / ExoPlayer
    val media3 = "1.5.1"
    implementation("androidx.media3:media3-exoplayer:$media3")
    implementation("androidx.media3:media3-ui:$media3")
    implementation("androidx.media3:media3-common:$media3")

    implementation("androidx.core:core-ktx:1.15.0")

    debugImplementation("androidx.compose.ui:ui-tooling")

    // ========== JPush（mavenCentral，仅小米通道）==========
    // JCore 由 jpush 自动拉取；厂商插件版本须与 JPush 版本一致
    implementation("cn.jiguang.sdk:jpush:6.2.1")
    implementation("cn.jiguang.sdk.plugin:xiaomi:6.2.1")
}
