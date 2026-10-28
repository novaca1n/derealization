// Сами часы: рисование на Canvas, без зависимостей от SystemUI — их же показывает превью в приложении.
// Только Java: этот код грузится внутрь SystemUI, а там свой Kotlin, и чужая версия stdlib была бы риском.
plugins { id("com.android.library") }

// Fonts and pictures. The public set (src/main/res) has only openly licensed fonts (OFL, CC0, CC BY-SA). The crDroid clock
// styles also use ~30 fonts and 4 pictures that can't be redistributed; release builds get open stand-ins under the same names
// (src/oss-res), debug builds use the originals from ../keys/private-res if that folder exists on this machine.
// So a public release never contains them, whoever builds it.
val privateRes = file("${rootDir}/../keys/private-res")

android {
    namespace = "dev.novacain.ntclocks.faces"
    compileSdk = 37
    defaultConfig { minSdk = 36 }
    sourceSets {
        getByName("release").res.srcDir("src/oss-res/res")
        getByName("debug").res.srcDir(if (privateRes.exists()) privateRes else file("src/oss-res/res"))
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    // анимированные стили crDroid (Ovalium, Rectangle, Wallet). Внутри SystemUI берётся его собственная Lottie
    // (загрузчик модуля спрашивает родителя первым), в приложении-превью — эта.
    implementation("com.airbnb.android:lottie:6.7.1")
}
