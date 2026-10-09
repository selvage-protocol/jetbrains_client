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

version = "0.4.0"

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

// The jars in that folder too.
val pluginJars = setOf("jar", "instrumentedJar", "composedJar")
tasks
    .withType<AbstractArchiveTask>()
    .matching { it.name in pluginJars }
    .configureEach { archiveBaseName.set("selvage") }

// The IDE carries the Kotlin standard library; the plugin does not ship a second one.
configurations.named("runtimeClasspath") {
    exclude(group = "org.jetbrains.kotlin", module = "kotlin-stdlib")
}

intellijPlatform {
    // The zip, its top folder and so the folder the IDE installs the plugin into are named after
    // this, not after the Gradle module, so no other plugin built from a module called `plugin`
    // shares that folder.
    projectName = "selvage"
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
    // `publishPlugin` uploads an update to the JetBrains Marketplace with the token `release.yml`
    // hands it from the repository secret of the same name. It is read from the environment and
    // never from a file, and the Marketplace takes a plugin's first upload only by hand.
    publishing {
        token = providers.environmentVariable("JETBRAINS_MARKETPLACE_TOKEN")
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

// The two-IDE end-to-end test's driver (`scripts/e2e/run-two-instance.sh`): a second plugin, loaded
// only into the sandboxes that test starts and never into the plugin's own zip. It runs the
// commands the test sends and answers with what the IDE shows.
val e2e =
    sourceSets.create("e2e") {
        compileClasspath += sourceSets.main.get().output + sourceSets.main.get().compileClasspath
    }

val e2eJar =
    tasks.register<Jar>("e2eJar") {
        archiveBaseName.set("selvage-e2e")
        destinationDirectory.set(layout.buildDirectory.dir("e2e"))
        // Its own classes and descriptor only: the platform adds the plugin's patched descriptor to
        // every source set's resources, and a second `dev.dontblameme.selvage` is not loaded.
        from(e2e.output.classesDirs)
        from("src/e2e/resources")
        duplicatesStrategy = DuplicatesStrategy.FAIL
    }

// What the end-to-end test needs: the IDE it runs, the plugin's zip and the driver's jar.
tasks.register("prepareE2e") {
    dependsOn(tasks.named("buildPlugin"), e2eJar)
    val kit = layout.buildDirectory.file("e2e/kit.properties")
    val zip = tasks.named<Zip>("buildPlugin").flatMap { it.archiveFile }
    val driver = e2eJar.flatMap { it.archiveFile }
    outputs.file(kit)
    outputs.upToDateWhen { false }
    doLast {
        kit.get().asFile.writeText(
            "ide=${intellijPlatform.platformPath}\nplugin=${zip.get().asFile}\ndriver=${driver.get().asFile}\n",
        )
    }
}
