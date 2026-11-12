plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("com.google.devtools.ksp")
    id("com.google.dagger.hilt.android")
}

fun propOrEnv(propName: String, envName: String, defaultValue: String): String {
    return (project.findProperty(propName) as String?)
        ?: System.getenv(envName)
        ?: defaultValue
}

val rawBaseUrl = propOrEnv("BASE_URL", "BASE_URL", "")
val appDisplayName = propOrEnv("APP_NAME", "APP_NAME", "MoonTVPlus")
val versionNameValue = propOrEnv("VERSION_NAME", "VERSION_NAME", "1.0.0")
val versionCodeValue = propOrEnv("VERSION_CODE", "VERSION_CODE", "1").toIntOrNull() ?: 1
val minSdkValue = propOrEnv("MIN_SDK", "MIN_SDK", "23").toIntOrNull() ?: 23

fun escapeJavaString(value: String): String = value
    .replace("\\", "\\\\")
    .replace("\"", "\\\"")

fun escapeXmlString(value: String): String = value
    .replace("&", "&amp;")
    .replace("<", "&lt;")
    .replace(">", "&gt;")
    .replace("'", "\\'")
    .replace("\"", "\\\"")

android {
    namespace = "com.moontvplus.phone"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.moontvplus.phone"
        minSdk = minSdkValue
        targetSdk = 35
        versionCode = versionCodeValue
        versionName = versionNameValue

        buildConfigField("String", "BASE_URL", "\"${escapeJavaString(rawBaseUrl)}\"")
        // 服务端按 UA 判断是否为 App 客户端：包含 "moontvplus app" 时会把
        // 配置过的源 m3u8 改写成 /api/proxy-m3u8?url=（去广告代理）。
        buildConfigField(
            "String",
            "APP_UA",
            "\"MoonTVPlus App/${versionNameValue} (Android) moontvplus app\""
        )
        resValue("string", "app_name", escapeXmlString(appDisplayName))
    }

    buildFeatures {
        buildConfig = true
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }

    signingConfigs {
        create("release") {
            val storeFilePath = System.getenv("ANDROID_KEYSTORE_PATH")
            if (!storeFilePath.isNullOrBlank() && file(storeFilePath).exists()) {
                storeFile = file(storeFilePath)
                storePassword = System.getenv("ANDROID_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("ANDROID_KEY_ALIAS")
                keyPassword = System.getenv("ANDROID_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        debug {
            isDebuggable = true
        }
        release {
            isMinifyEnabled = false
            val storeFilePath = System.getenv("ANDROID_KEYSTORE_PATH")
            if (!storeFilePath.isNullOrBlank() && file(storeFilePath).exists()) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    lint {
        abortOnError = false
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.10.01")
    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.navigation:navigation-compose:2.8.4")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-process:2.8.7")
    implementation("androidx.core:core-ktx:1.13.1")

    implementation("androidx.datastore:datastore-preferences:1.1.1")
    implementation("androidx.webkit:webkit:1.12.1")

    implementation("com.google.dagger:hilt-android:2.52")
    ksp("com.google.dagger:hilt-compiler:2.52")
    implementation("androidx.hilt:hilt-navigation-compose:1.2.0")

    implementation(platform("com.squareup.okhttp3:okhttp-bom:4.12.0"))
    implementation("com.squareup.okhttp3:okhttp")
    implementation("com.squareup.okhttp3:logging-interceptor")
    implementation("com.squareup.retrofit2:retrofit:2.11.0")
    implementation("com.squareup.retrofit2:converter-kotlinx-serialization:2.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    implementation("androidx.media3:media3-exoplayer:1.4.1")
    implementation("androidx.media3:media3-exoplayer-hls:1.4.1")
    implementation("androidx.media3:media3-ui:1.4.1")
    implementation("androidx.media3:media3-datasource-okhttp:1.4.1")

    implementation("io.coil-kt:coil-compose:2.7.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
}
