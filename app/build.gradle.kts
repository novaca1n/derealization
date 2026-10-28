import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Release key lives outside the repo (../keys/keystore.properties or $RELEASE_KEYS); without it release builds
// are signed with the debug key, which is fine for local builds.
val keyProps = Properties().apply {
    val f = file(System.getenv("RELEASE_KEYS") ?: "${rootDir}/../keys/keystore.properties")
    if (f.exists()) f.reader(Charsets.UTF_8).use { load(it) }   // UTF-8: the path may be non-ASCII
}

android {
    namespace = "dev.novacain.ntclocks"
    compileSdk = 37
    defaultConfig {
        applicationId = "dev.novacain.ntclocks"
        minSdk = 36          // только Nothing OS 4.1 (Android 16) — под неё и сделан модуль
        targetSdk = 36
        versionCode = 45
        versionName = "3.8"
    }
    signingConfigs {
        if (keyProps.isNotEmpty()) create("release") {
            storeFile = file(keyProps.getProperty("storeFile"))
            storePassword = keyProps.getProperty("storePassword")
            keyAlias = keyProps.getProperty("keyAlias")
            keyPassword = keyProps.getProperty("keyPassword")
        }
    }
    buildTypes {
        release {
            // без сжатия: классы модуля ищутся по именам изнутри SystemUI (рефлексия, xposed_init)
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }
    buildFeatures { compose = true }
    lint { checkReleaseBuilds = false }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(project(":faces"))
    implementation(project(":sysui"))
    compileOnly("de.robv.android.xposed:api:82")
    implementation(platform("androidx.compose:compose-bom:2026.09.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.13.0")
    // вырезка объекта для своей маски глубины — тот же ML Kit, что у «Обоев и стиля» (модель из Google Play services)
    implementation("com.google.android.gms:play-services-mlkit-subject-segmentation:16.0.0-beta1")
}
