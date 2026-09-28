plugins {
    kotlin("jvm")
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    testImplementation(kotlin("test"))
}

// The differential test drives real yjs through Node. Where yjs lives, which Node runs it and
// where the specification's vectors are come from Gradle properties first, then the environment;
// the test resolves the defaults and fails with the reason when one is missing.
val passThrough =
    mapOf(
        "selvage.yjsNodeModules" to "SELVAGE_YJS_NODE_MODULES",
        "selvage.node" to "SELVAGE_NODE",
        "selvage.specification" to "SELVAGE_SPECIFICATION",
    )

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
        val value = providers.gradleProperty(property).orNull ?: System.getenv(variable)
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
