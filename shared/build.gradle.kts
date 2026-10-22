plugins { alias(libs.plugins.kotlin.jvm) }
kotlin {
    jvmToolchain(17)
    sourceSets.main {
        // One authoritative source for Android and Desktop; Android packaging stays unchanged.
        kotlin.srcDir("../app/src/main/kotlin")
        kotlin.include("com/yunx/app/data/network/**", "com/yunx/app/data/repository/*ResolveRepository.kt", "com/yunx/app/data/download/DownloadFailurePolicy.kt", "com/yunx/app/data/download/Hls*.kt")
        kotlin.exclude("**/XunleiDeviceFingerprint.kt")
    }
}
dependencies {
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
    implementation("org.json:json:20240303")
    testImplementation("junit:junit:4.13.2")
}
