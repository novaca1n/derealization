pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // классический API Xposed (его понимает LSPosed) — только для компиляции, в APK не попадает
        maven("https://api.xposed.info/")
    }
}
rootProject.name = "nt-clocks"
include(":app", ":faces", ":sysui")
