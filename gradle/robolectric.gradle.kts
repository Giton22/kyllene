// Robolectric runs each test on an Android framework jar (android-all-instrumented, one per SDK level). Left alone it
// downloads that jar from Maven Central when a test class starts, outside Gradle and without retrying, so one network
// error failed every test class in CI. Here Gradle resolves the jars like any other dependency (its cache, its retries,
// and setup-gradle's cache in CI), and the host tests are given a robolectric-deps.properties that maps each jar to its
// file. With that property set Robolectric reads only the file and never goes online.
//
// Applied by the modules whose host tests use Robolectric. The jars are the catalogue's robolectric-android-all bundle.

// One configuration per jar: they are versions of one module, so a single configuration would keep only the newest.
val robolectricAndroidAll = project.extensions.getByType<VersionCatalogsExtension>().named("libs")
    .findBundle("robolectric-android-all").get().get()
    .map { jar ->
        configurations.create("robolectricAndroidAll${jar.version!!.substringBefore('-')}") {
            isCanBeConsumed = false
            isTransitive = false
            dependencies.add(jar)
        }
    }
val depsPath = "robolectric/robolectric-deps.properties"

val robolectricDeps = tasks.register("robolectricDepsProperties") {
    description = "Writes robolectric-deps.properties, which points Robolectric at the jars Gradle resolved."
    val artifacts = robolectricAndroidAll.map { it.incoming.artifacts.resolvedArtifacts }
    val out = layout.buildDirectory.file(depsPath)
    inputs.files(robolectricAndroidAll).withPropertyName("jars")
    outputs.file(out).withPropertyName("properties")
    doLast {
        val props = java.util.Properties()
        artifacts.flatMap { it.get() }.forEach { artifact ->
            val id = artifact.id.componentIdentifier as org.gradle.api.artifacts.component.ModuleComponentIdentifier
            props["${id.group}:${id.module}:${id.version}"] = artifact.file.absolutePath
        }
        // Properties escapes the keys' colons and the paths' backslashes; its date comment is left out so the file
        // only changes when a jar does.
        val text = java.io.StringWriter().also { props.store(it, null) }.toString()
        val lines = text.lineSequence().filter { it.isNotBlank() && !it.startsWith("#") }.sorted()
        out.get().asFile.writeText(lines.joinToString("\n", postfix = "\n"))
    }
}

tasks.withType<Test>().matching { it.name == "testAndroidHostTest" }.configureEach {
    dependsOn(robolectricDeps)
    // The jars' contents are the input, not where they sit, so the test results stay shareable through the build cache.
    inputs.files(robolectricAndroidAll).withPropertyName("robolectricAndroidAll")
        .withPathSensitivity(PathSensitivity.NONE)
    val depsFile = project.layout.buildDirectory.file(depsPath)
    jvmArgumentProviders.add(
        CommandLineArgumentProvider { listOf("-Drobolectric-deps.properties=${depsFile.get().asFile.absolutePath}") },
    )
}
