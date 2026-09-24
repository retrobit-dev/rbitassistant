// Versi dikunci di sini. Gradle sendiri dipasang oleh CI (gradle/actions/setup-gradle,
// lihat .github/workflows/android.yml) karena sandbox perencanaan tidak bisa membuat
// gradle-wrapper.jar. Build lokal: pasang Gradle 8.11.x lalu jalankan `gradle assembleRelease`.
plugins {
    id("com.android.application") version "8.7.3" apply false
    id("org.jetbrains.kotlin.android") version "2.0.21" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.21" apply false
}
