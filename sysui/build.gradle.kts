// Стыковка с SystemUI Nothing OS 4.1: реализация интерфейсов часов ровно по сигнатурам их SystemUI 16.0.0.
// libs/systemui-clock-api.jar — compile-only signatures (no code) of the ~85 SystemUI classes the clock plugin uses:
// the AOSP clock plugin API plus Nothing's additions. Generated from NothingSystemUI.apk with dex2jar -nc, then cut to
// the classes referenced from src (transitively).
plugins { id("com.android.library") }

android {
    namespace = "dev.novacain.ntclocks.sysui"
    compileSdk = 37
    defaultConfig { minSdk = 36 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(project(":faces"))
    compileOnly(files("libs/systemui-clock-api.jar"))
    // ConstraintSet for the clock layout — SystemUI ships its own copy at runtime
    compileOnly("androidx.constraintlayout:constraintlayout:2.2.1")
}
