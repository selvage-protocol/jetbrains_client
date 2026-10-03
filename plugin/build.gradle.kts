import org.jetbrains.intellij.platform.gradle.TestFrameworkType

plugins {
    kotlin("jvm")
    id("org.jetbrains.intellij.platform") version "2.19.0"
}

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

version = "0.1.0"

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(project(":engine"))
    intellijPlatform {
        intellijIdea(providers.gradleProperty("selvage.ideVersion").orElse("2026.2.3"))
        testFramework(TestFrameworkType.Platform)
        pluginVerifier()
    }
    testImplementation("junit:junit:4.13.2")
}

// The IDE carries the Kotlin standard library; the plugin does not ship a second one.
configurations.named("runtimeClasspath") {
    exclude(group = "org.jetbrains.kotlin", module = "kotlin-stdlib")
}

intellijPlatform {
    pluginConfiguration {
        id = "dev.dontblameme.selvage"
        name = "Selvage"
        version = project.version.toString()
        vendor {
            name = "Selvage Protocol"
            url = "https://selvage.dontblameme.dev"
        }
        ideaVersion {
            sinceBuild = "262"
            untilBuild = provider { null }
        }
    }
    pluginVerification {
        ides {
            recommended()
        }
    }
}
