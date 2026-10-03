rootProject.name = "selvage-jetbrains"

pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    // The plugin declares the IntelliJ Platform's repositories itself; everything else resolves here.
    repositoriesMode.set(RepositoriesMode.PREFER_PROJECT)
    repositories {
        mavenCentral()
    }
}

// `engine` is the session layer with no IntelliJ dependency; `plugin` is the IDE adapter over it.
include("engine")
include("plugin")
