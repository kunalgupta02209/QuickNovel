// https://developer.android.com/build#settings-file
pluginManagement {
    repositories {
        gradlePluginPortal()
        google()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        mavenLocal()
        maven("https://jitpack.io")
        // vendored sherpa-onnx.aar for on-device neural TTS (FAIL_ON_PROJECT_REPOS forbids flatDir in the app module)
        flatDir { dirs("app/libs") }
    }
}

rootProject.name = "QuickNovel"
include(":app")
