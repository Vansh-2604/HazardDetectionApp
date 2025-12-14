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
    // 🔴 REMOVE versionCatalogs block completely
    // No version catalogs, no 'from(files(...))'
}

rootProject.name = "HazardDetectionApp"
include(":app")
