rootProject.name = "selvage-jetbrains"

pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenCentral()
    }
}

// `engine` is the session layer with no IntelliJ dependency; the IDE adapter joins it as `plugin`.
include("engine")
