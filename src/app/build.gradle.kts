import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("com.google.devtools.ksp")
}

val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    namespace = "com.crnogorski.trener"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.montelearn"
        minSdk = 34
        targetSdk = 35
        versionCode = 93
        versionName = "1.90"
        buildConfigField(
            "String",
            "ANTHROPIC_API_KEY",
            "\"${localProps.getProperty("ANTHROPIC_API_KEY") ?: ""}\""
        )
        // Токен для issue с жалобами. Мелкий (fine-grained), выданный на один
        // этот репозиторий с правом Issues: write — из APK он достаётся так же
        // легко, как ключ Anthropic, и шире права давать нельзя.
        buildConfigField(
            "String",
            "GITHUB_TOKEN",
            "\"${localProps.getProperty("GITHUB_TOKEN") ?: ""}\""
        )
        buildConfigField("String", "GITHUB_REPO", "\"voronkovlabs/android-russian-montenegrin\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

/**
 * Забирает жалобы с подключённого телефона в `app/build/complaints.jsonl`.
 *
 * Файл лежит в каталоге приложения на внешней памяти, поэтому root и разрешения
 * не нужны. Пишет его ComplaintStore; формат — по строке JSON на жалобу.
 */
tasks.register<Exec>("pullComplaints") {
    group = "reporting"
    description = "Скачивает complaints.jsonl с устройства"

    val adbName = if (System.getProperty("os.name").startsWith("Windows", true)) "adb.exe" else "adb"
    val adb = File(android.sdkDirectory, "platform-tools/$adbName")
    val appId = android.defaultConfig.applicationId
    val target = layout.buildDirectory.dir("complaints").get().asFile

    // Тянем каталог целиком, а не один файл: после «Очистить» рядом лежат
    // complaints-sent-<дата>.jsonl, и они тоже нужны.
    commandLine(
        adb.absolutePath,
        "pull",
        "/sdcard/Android/data/$appId/files/",
        target.absolutePath
    )
    // Каталога может не быть — это не повод валить сборку.
    isIgnoreExitValue = true

    doFirst { target.mkdirs() }
    doLast {
        val files = target.walkTopDown().filter { it.isFile && it.name.endsWith(".jsonl") }.toList()
        if (files.isEmpty()) {
            logger.lifecycle("Жалоб нет (или устройство не подключено).")
        } else {
            val total = files.sumOf { f -> f.readLines().count { it.isNotBlank() } }
            logger.lifecycle("Жалоб: $total в ${files.size} файле(ах) -> ${target.absolutePath}")
            files.forEach { logger.lifecycle("  ${it.name}") }
        }
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")

    // FileProvider для отправки файла жалоб через share.
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.navigation:navigation-compose:2.8.5")

    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")

    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
}
