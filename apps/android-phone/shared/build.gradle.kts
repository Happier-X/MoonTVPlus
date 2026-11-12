plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.multiplatform")
    id("org.jetbrains.kotlin.plugin.serialization")
}

kotlin {
    androidTarget {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }

    jvm("desktop") {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }

    sourceSets {
        val commonMain by getting {
            dependencies {
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
                implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
                // okio 是跨平台的，用它做文件存储
                implementation("com.squareup.okio:okio:3.6.0")
            }
        }

        /**
         * Android 与桌面共用的 JVM 源集。
         *
         * OkHttp / Retrofit 是 JVM 库，不能进 commonMain；把网络与仓库层放这里，
         * 两个平台依旧共享同一份实现。以后加 iOS 时，这里换成 Ktor 即可。
         */
        val jvmSharedMain by creating {
            dependsOn(commonMain)
            dependencies {
                api("com.squareup.okhttp3:okhttp:4.12.0")
                api("com.squareup.retrofit2:retrofit:2.11.0")
                implementation("com.squareup.retrofit2:converter-kotlinx-serialization:2.11.0")
                implementation("com.squareup.okhttp3:logging-interceptor:4.12.0")
            }
        }

        val androidMain by getting { dependsOn(jvmSharedMain) }
        val desktopMain by getting { dependsOn(jvmSharedMain) }

        val commonTest by getting {
            dependencies {
                implementation(kotlin("test"))
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
            }
        }

        val jvmSharedTest by creating {
            dependsOn(commonTest)
            dependencies {
                implementation(kotlin("test"))
            }
        }

        val androidUnitTest by getting { dependsOn(jvmSharedTest) }
        val desktopTest by getting { dependsOn(jvmSharedTest) }
    }
}

android {
    namespace = "com.moontvplus.shared"
    compileSdk = 35
    defaultConfig {
        minSdk = 23
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
