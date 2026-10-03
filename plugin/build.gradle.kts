import org.jetbrains.intellij.platform.gradle.IntelliJPlatformType
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

val ideVersion = providers.gradleProperty("selvage.ideVersion").getOrElse("2026.2.3")

kotlin {
    jvmToolchain(21)
    compilerOptions {
        // Implementing a platform interface adds no bridges to its default methods, which the
        // Plugin Verifier would read as this plugin calling and overriding them.
        jvmDefault.set(org.jetbrains.kotlin.gradle.dsl.JvmDefaultMode.NO_COMPATIBILITY)
    }
}

dependencies {
    implementation(project(":engine"))
    intellijPlatform {
        intellijIdea(ideVersion)
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
    // Indexing the settings page for search starts a whole IDE; the page has five fields.
    buildSearchableOptions = false
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
    // The Plugin Verifier against the IDE the plugin targets.
    pluginVerification {
        ides {
            create(IntelliJPlatformType.IntellijIdea, ideVersion)
        }
    }
}

// Where the tests find what they compare against or spawn: Gradle properties first, then the
// environment. The vocabulary and bridge pins read the sibling VS Code client by default.
val passThrough =
    mapOf(
        "selvage.selvaged" to "SELVAGE_SELVAGED",
        "selvage.node" to "SELVAGE_NODE",
        "selvage.vscodeClient" to "SELVAGE_VSCODE_CLIENT",
    )

fun Test.configureSuite() {
    maxParallelForks = 1
    systemProperty("selvage.workspaceRoot", rootDir.absolutePath)
    inputs.dir("src/test/node")
    val testTmp =
        layout.buildDirectory
            .dir("tmp/$name-jvm")
            .get()
            .asFile
    doFirst { testTmp.mkdirs() }
    systemProperty("java.io.tmpdir", testTmp.absolutePath)
    jvmArgs("-XX:-UsePerfData")
    // No display: the test IDE runs headless, which also keeps it off the host's X server.
    systemProperty("java.awt.headless", "true")
    maxHeapSize = "2g"
    providers.environmentVariable("SELVAGE_TEST_LIBRARY_PATH").orNull?.let { environment("LD_LIBRARY_PATH", it) }
    passThrough.forEach { (property, variable) ->
        val value = providers.gradleProperty(property).orElse(providers.environmentVariable(variable)).orNull
        if (value != null) {
            systemProperty(property, value)
            inputs.property(property, value)
        }
    }
    testLogging {
        events("failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}

// The platform configures `test` for a test IDE, so the live run is the same task, selected by
// `-Pselvage.live`: the adapter hosting in a test IDE against a real `selvaged` (SELVAGE_SELVAGED).
val live = providers.gradleProperty("selvage.live").isPresent

tasks.test {
    if (live) {
        include("**/Live*")
        outputs.upToDateWhen { false }
    } else {
        exclude("**/Live*")
    }
    configureSuite()
}
