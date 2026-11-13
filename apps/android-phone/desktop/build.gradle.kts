import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.compose")
    id("org.jetbrains.kotlin.plugin.compose")
}

/** 版本号与 Android 端保持一致，打包后的窗口标题/关于页都读它。 */
val appVersion = (project.findProperty("VERSION_NAME") as String?)
    ?: System.getenv("VERSION_NAME")
    ?: "1.9.2"

/** 由 Gradle 生成，避免 Main.kt 里再手写一份版本常量（曾与包版本对不上）。 */
val generateAppVersion by tasks.registering {
    val outputDir = layout.buildDirectory.dir("generated/appVersion")
    val version = appVersion
    inputs.property("version", version)
    outputs.dir(outputDir)
    doLast {
        val file = outputDir.get()
            .file("com/moontvplus/phone/desktop/AppVersion.kt").asFile
        file.parentFile.mkdirs()
        file.writeText(
            """
            |package com.moontvplus.phone.desktop
            |
            |/** 由 :desktop:generateAppVersion 生成，来源是 VERSION_NAME / 包版本。 */
            |internal const val APP_VERSION = "$version"
            |""".trimMargin(),
        )
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }

    sourceSets.getByName("main").kotlin.srcDir(generateAppVersion)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

dependencies {
    implementation(project(":shared"))
    implementation(project(":ui"))
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation(compose.materialIconsExtended)
    implementation(compose.foundation)
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.9.0")
}

compose.desktop {
    application {
        mainClass = "com.moontvplus.phone.desktop.MainKt"

        nativeDistributions {
            targetFormats(TargetFormat.Msi, TargetFormat.Exe, TargetFormat.Dmg, TargetFormat.Deb)
            packageName = "MoonTVPlus"
            packageVersion = appVersion
            description = "MoonTVPlus 桌面客户端"
            vendor = "MoonTVPlus"

            windows {
                menu = true
                shortcut = true
                // 固定一个稳定 UUID，升级安装才能识别成同一个应用
                upgradeUuid = "0f3f4c4a-8f0e-4c9e-9b1a-6c1d2e3f4a5b"
            }
        }
    }
}
