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
rootProject.name = "Doorprints"
include(":app")
// Kotlin Multiplatform module with the platform-neutral logic (domain rules, DTOs, Ktor API client). See shared/README.md.
include(":shared")
// Compose Multiplatform UI (theme, shared composables and UI rules) for Android and, later, iOS (ADR-23). See ui/README.md.
include(":ui")
