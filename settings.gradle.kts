pluginManagement {
    repositories {
        google()
        gradlePluginPortal()
        mavenCentral()
    }
}
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}
rootProject.name = "android-field-radio"
include(":core-audio", ":core-profile", ":core-publish", ":core-stt", ":core-session", ":core-library", ":app")
