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
// :app        — jbr1.carlinkit (Play-distributed display / protocol app)
// :iconbridge — jbr1.carlinkit.hudicons (sideload-only HUD/cluster icon provider)
include(":app")
include(":iconbridge")
