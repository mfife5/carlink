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
    }
}

// Standalone nested project — intentionally NOT included from the repo-root
// settings.gradle.kts. Android Studio sync of the main app was failing on
// :iconbridge:prepareKotlinBuildScriptModel; keeping this out of the multi-project
// graph fixes IDE sync while still allowing CLI builds via:
//   ./gradlew -p iconbridge assembleRelease
rootProject.name = "iconbridge"
