plugins {
    kotlin("jvm")
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    testImplementation(kotlin("test"))
}

// The differential test drives real yjs through Node, and the live test a real `selvaged`. Where
// yjs lives, which Node runs it and which `selvaged` to spawn come from Gradle properties first,
// then the environment; a test resolves the defaults and fails with the reason when one is missing.
val passThrough =
    mapOf(
        "selvage.yjsNodeModules" to "SELVAGE_YJS_NODE_MODULES",
        "selvage.node" to "SELVAGE_NODE",
        "selvage.selvaged" to "SELVAGE_SELVAGED",
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

fun Test.configureSuite() {
    maxParallelForks = 1
    inputs.dir("src/test/node")
    systemProperty("selvage.workspaceRoot", rootDir.absolutePath)
    // Keep the test JVM's temporary files in the build directory, not the system's.
    val testTmp =
        layout.buildDirectory
            .dir("tmp/$name-jvm")
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

tasks.test {
    useJUnitPlatform { excludeTags("live") }
    configureSuite()
}

// Against a real `selvaged` (SELVAGE_SELVAGED), which a sandboxed build has no network for.
val liveTest by tasks.registering(Test::class) {
    description = "Runs the engine against a real selvaged."
    group = "verification"
    testClassesDirs =
        sourceSets.test
            .get()
            .output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    useJUnitPlatform { includeTags("live") }
    outputs.upToDateWhen { false }
    shouldRunAfter(tasks.test)
    configureSuite()
}
