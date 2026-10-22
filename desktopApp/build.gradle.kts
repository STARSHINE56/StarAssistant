import org.jetbrains.compose.desktop.application.dsl.TargetFormat
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.compose.desktop)
    alias(libs.plugins.kotlin.compose)
}
kotlin { jvmToolchain(17) }
dependencies {
    implementation(project(":shared"))
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation(compose.materialIconsExtended)
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.9.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.json:json:20240303")
    implementation("net.java.dev.jna:jna-platform:5.15.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
}
compose.desktop {
    application {
        mainClass = "com.xingchen.desktop.MainKt"
        jvmArgs += listOf("-Dxingchen.version=${providers.gradleProperty("VERSION_NAME").orElse("1.0.0").get()}", "-Dfile.encoding=UTF-8", "-Dsun.java2d.uiScale.enabled=true")
        nativeDistributions {
            targetFormats(TargetFormat.Exe, TargetFormat.Msi)
            packageName = "XingChen-Assistant"
            packageVersion = providers.gradleProperty("VERSION_NAME").orElse("1.0.0").get()
            description = "XingChen Assistant: share link parsing and downloads"
            vendor = "STARSHINE56"
            modules("java.desktop", "java.net.http", "jdk.unsupported", "java.prefs", "jdk.crypto.ec")
            windows {
                iconFile.set(project.file("src/main/resources/xingchen.ico"))
                menuGroup = "XingChen Assistant"
                shortcut = true
                menu = true
                perUserInstall = true
                upgradeUuid = "8e98b39f-ea5f-43f3-b264-4c9cb89b8244"
            }
        }
    }
}
