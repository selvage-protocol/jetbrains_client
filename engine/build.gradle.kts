plugins {
    kotlin("jvm")
    application
}

// The corpus subject: `specification/runner/run_peer.py --subject` drives the installed launcher.
application {
    mainClass.set("dev.dontblameme.selvage.subject.Main")
    applicationDefaultJvmArgs = listOf("-XX:-UsePerfData", "-XX:TieredStopAtLevel=1")
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    testImplementation(kotlin("test"))
}

// The differential test drives real yjs through Node. Where yjs lives, which Node runs it and
// where the specification's vectors are come from Gradle properties first, then the environment;
// the test resolves the other defaults and fails with the reason when one is missing.
val passThrough =
    mapOf(
        "selvage.yjsNodeModules" to "SELVAGE_YJS_NODE_MODULES",
        "selvage.node" to "SELVAGE_NODE",
    )

// The specification whose vectors the tests replay: the one named, else the sibling checkout.
val specification: File? =
    providers
        .gradleProperty("selvage.specification")
        .orElse(providers.environmentVariable("SELVAGE_SPECIFICATION"))
        .map { file(it) }
        .orNull
        ?: generateSequence(rootDir.absoluteFile) { it.parentFile }
            .map { it.resolve("specification") }
            .firstOrNull { it.resolve("PROTOCOL.md").isFile }

tasks.test {
    useJUnitPlatform()
    maxParallelForks = 1
    inputs.dir("src/test/node")
    systemProperty("selvage.workspaceRoot", rootDir.absolutePath)
    // Keep the test JVM's temporary files in the build directory, not the system's.
    val testTmp =
        layout.buildDirectory
            .dir("tmp/test-jvm")
            .get()
            .asFile
    doFirst { testTmp.mkdirs() }
    systemProperty("java.io.tmpdir", testTmp.absolutePath)
    jvmArgs("-XX:-UsePerfData")
    passThrough.forEach { (property, variable) ->
        val value = providers.gradleProperty(property).orElse(providers.environmentVariable(variable)).orNull
        if (value != null) {
            systemProperty(property, value)
            inputs.property(property, value)
        }
    }
    // The vectors' contents, not just their path: a change to them re-runs the tests instead of
    // restoring an up-to-date or cached result.
    specification?.let {
        systemProperty("selvage.specification", it.absolutePath)
        inputs
            .dir(it.resolve("vectors"))
            .withPropertyName("specificationVectors")
            .withPathSensitivity(PathSensitivity.RELATIVE)
    }
    testLogging {
        events("failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}
