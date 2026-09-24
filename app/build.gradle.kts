plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Nomor build dari GitHub Actions; lokal = 1. versionCode harus naik agar APK baru
// bisa dipasang di atas yang lama.
val buildNumber = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()
val versionPrefix = "0.2" // dibaca juga oleh .github/workflows/android.yml

android {
    namespace = "dev.retrobit.assistant"
    compileSdk = 35

    defaultConfig {
        applicationId = "dev.retrobit.assistant"
        minSdk = 31          // createOnDeviceSpeechRecognizer butuh API 31; HP target = Android 15
        targetSdk = 35
        versionCode = buildNumber
        versionName = "$versionPrefix.$buildNumber"
    }

    signingConfigs {
        // Default: kunci DEV yang di-commit (app/signing/README.md menjelaskan risikonya).
        // Jika secret RBIT_KEYSTORE_* diisi di GitHub, workflow memakai kunci itu.
        create("rbit") {
            storeFile = file(System.getenv("RBIT_KEYSTORE_PATH") ?: "signing/dev-insecure.p12")
            storePassword = System.getenv("RBIT_KEYSTORE_PASSWORD") ?: "rbitdev-insecure"
            keyAlias = System.getenv("RBIT_KEY_ALIAS") ?: "rbitassistant"
            keyPassword = System.getenv("RBIT_KEY_PASSWORD") ?: "rbitdev-insecure"
            storeType = "pkcs12"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("rbit")
        }
        debug {
            signingConfig = signingConfigs.getByName("rbit")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

// Katalog intent (satu-satunya sumber kebenaran ada di /intents) disalin ke assets/intents/.
abstract class CopyIntentsTask : DefaultTask() {
    @get:InputDirectory
    abstract val source: DirectoryProperty

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun copy() {
        val dest = outputDir.get().asFile.resolve("intents")
        dest.deleteRecursively()
        dest.mkdirs()
        source.get().asFile.listFiles()!!.filter { it.name.endsWith(".yaml") }.forEach {
            it.copyTo(dest.resolve(it.name), overwrite = true)
        }
    }
}

val copyIntents = tasks.register<CopyIntentsTask>("copyIntents") {
    source.set(rootProject.layout.projectDirectory.dir("intents"))
}

androidComponents {
    onVariants { variant ->
        variant.sources.assets?.addGeneratedSourceDirectory(copyIntents, CopyIntentsTask::outputDir)
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.security:security-crypto:1.0.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("org.yaml:snakeyaml:2.3")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
