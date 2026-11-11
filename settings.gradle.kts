pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "carlink-native"
// :app only. The HUD icon companion lives in ./iconbridge as a *separate* Gradle
// project (see iconbridge/settings.gradle.kts) so Android Studio sync of this app
// is not broken by :iconbridge:prepareKotlinBuildScriptModel. Build it with:
//   ./gradlew -p iconbridge assembleRelease
include(":app")
