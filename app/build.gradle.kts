import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

val whisperModelSize = 77_691_713L
val whisperModelSha256 = "be07e048e1e599ad46341c8d2a135645097a538221678b7acdd1b1919c6e1b21"
val whisperModelUrl = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-tiny.bin"
val whisperAssetDirectory = layout.buildDirectory.dir("generated/whisper-model-assets")
val whisperAssetFile = whisperAssetDirectory.map { it.file("models/ggml-tiny.bin") }

val prepareWhisperModel by tasks.registering {
    group = "build setup"
    description = "Fetches and verifies the offline Whisper model for packaging inside the APK."
    outputs.file(whisperAssetFile)

    doLast {
        val destination = whisperAssetFile.get().asFile
        fun sha256(file: java.io.File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().buffered().use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    digest.update(buffer, 0, count)
                }
            }
            return digest.digest().joinToString("") {
                (it.toInt() and 0xff).toString(16).padStart(2, '0')
            }
        }

        if (destination.isFile && destination.length() == whisperModelSize &&
            sha256(destination) == whisperModelSha256
        ) {
            logger.lifecycle("Verified bundled Whisper model already exists.")
            return@doLast
        }

        destination.parentFile.mkdirs()
        val temporary = File(destination.parentFile, destination.name + ".part")
        temporary.delete()
        val connection = (URL(whisperModelUrl).openConnection() as HttpURLConnection).apply {
            connectTimeout = 20_000
            readTimeout = 60_000
            instanceFollowRedirects = true
            requestMethod = "GET"
        }

        try {
            connection.connect()
            check(connection.responseCode in 200..299) {
                "Whisper model fetch failed (HTTP ${connection.responseCode})."
            }
            connection.inputStream.use { input ->
                temporary.outputStream().buffered().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                    }
                }
            }
            check(temporary.length() == whisperModelSize) {
                "Whisper model size verification failed: expected $whisperModelSize bytes, got ${temporary.length()}."
            }
            check(sha256(temporary) == whisperModelSha256) {
                "Whisper model SHA-256 verification failed; refusing to package the file."
            }
            check(temporary.renameTo(destination)) {
                "Could not move the verified Whisper model into generated APK assets."
            }
            logger.lifecycle("Whisper tiny model verified and ready to bundle in the APK.")
        } catch (error: Throwable) {
            temporary.delete()
            throw error
        } finally {
            connection.disconnect()
        }
    }
}

android {
    namespace = "com.clipforge.app"
    compileSdk = 35
    ndkVersion = "27.2.12479018"
    defaultConfig {
        applicationId = "com.clipforge.app"
        minSdk = 26
        targetSdk = 35
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        versionCode = 7
        versionName = "0.6.2"
        ndk {
            // CI emulator is x86_64; production APKs remain ARM64-only.
            abiFilters += if (providers.gradleProperty("clipforgeEmulatorTest").orNull == "true") {
                listOf("x86_64")
            } else {
                listOf("arm64-v8a")
            }
        }
        externalNativeBuild { cmake { cppFlags += listOf("-std=c++17") } }
    }
    buildTypes {
        debug { externalNativeBuild { cmake { arguments += listOf("-DCMAKE_BUILD_TYPE=Release") } } }
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            externalNativeBuild { cmake { arguments += listOf("-DCMAKE_BUILD_TYPE=Release") } }
        }
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    packaging.jniLibs.useLegacyPackaging = true
    buildFeatures { compose = true }
    externalNativeBuild { cmake { path = file("src/main/cpp/CMakeLists.txt"); version = "3.22.1" } }
    sourceSets.getByName("main").assets.srcDir(whisperAssetDirectory)
    androidResources { noCompress += "bin" }
}

tasks.named("preBuild").configure {
    dependsOn(prepareWhisperModel)
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    androidTestImplementation(composeBom)
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.10.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.work:work-runtime-ktx:2.10.0")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("io.github.junkfood02.youtubedl-android:library:0.18.1")
    implementation("io.github.junkfood02.youtubedl-android:ffmpeg:0.18.1")
    implementation("dev.ffmpegkit-maintained:ffmpeg-kit-full:8.1.9")
    implementation("com.google.mlkit:face-detection:16.1.7")
    implementation("com.google.android.gms:play-services-tasks:18.2.0")
    implementation("androidx.media3:media3-exoplayer:1.5.1")
    implementation("androidx.media3:media3-ui:1.5.1")
    implementation("androidx.media3:media3-transformer:1.5.1")
    implementation("androidx.media3:media3-effect:1.5.1")
    implementation("com.github.pedroSG94.RootEncoder:library:2.5.5")
    debugImplementation("androidx.compose.ui:ui-tooling")
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
}
