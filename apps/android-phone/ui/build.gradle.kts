plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.multiplatform")
    id("org.jetbrains.compose")
    id("org.jetbrains.kotlin.plugin.compose")
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
        /** 界面与视图状态：Android 与桌面共用同一套 Compose UI。 */
        val commonMain by getting {
            dependencies {
                api(project(":shared"))
                implementation(compose.runtime)
                implementation(compose.foundation)
                implementation(compose.material3)
                implementation(compose.materialIconsExtended)
                implementation(compose.ui)
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
            }
        }

        /**
         * 视图状态层：要读 `:shared` 的 AppGraph / OkHttp 客户端，
         * 这部分在 `:shared` 里是 jvmSharedMain，所以这里也建一层对应的中间源集。
         */
        val jvmSharedMain by creating {
            dependsOn(commonMain)
            dependencies {
                implementation(compose.material3)
            }
        }

        val desktopTest by getting {
            dependencies {
                implementation(kotlin("test-junit"))
                implementation(compose.desktop.uiTestJUnit4)
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.9.0")
                implementation("com.squareup.okhttp3:mockwebserver:4.12.0")
            }
        }

        /** Android 的海报加载用 Coil（复用已配好鉴权的 ImageLoader）。 */
        val androidMain by getting {
            dependsOn(jvmSharedMain)
            dependencies {
                implementation("io.coil-kt:coil-compose:2.7.0")
            }
        }

        /** 桌面端直接用 skiko 解码，图片走共享 OkHttp。 */
        val desktopMain by getting {
            dependsOn(jvmSharedMain)
            dependencies {
                implementation(compose.desktop.currentOs)
            }
        }
    }
}

android {
    namespace = "com.moontvplus.ui"
    compileSdk = 35
    defaultConfig {
        minSdk = 23
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
