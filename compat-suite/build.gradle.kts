import java.util.Properties

plugins {
    base
}

/**
 * Writes the two plugin.yml files and compat-fixture.properties for one era. Neither plugin.yml
 * declares folia-supported: the suite tests unmodified Bukkit/Paper plugins.
 */
abstract class GenerateFixtureDescriptors : DefaultTask() {
    @get:Input abstract val fixtureId: Property<String>
    @get:Input abstract val label: Property<String>
    @get:Input abstract val api: Property<String>
    @get:Input abstract val release: Property<String>
    @get:Input abstract val apiVersion: Property<String>
    @get:Input abstract val layers: Property<String>
    @get:OutputDirectory abstract val outputDir: DirectoryProperty

    @TaskAction
    fun generate() {
        val id = fixtureId.get()
        val out = outputDir.get().asFile
        out.deleteRecursively()
        val apiLine = apiVersion.get().takeIf { it.isNotBlank() }?.let { listOf("api-version: '$it'") } ?: emptyList()

        File(out, "fixture").apply { mkdirs() }.let { dir ->
            File(dir, "plugin.yml").writeText((listOf(
                "name: CompatFixture-$id",
                "version: '1'",
                "main: fun.bm.lecithin.compatsuite.fixture.CompatFixturePlugin",
                "description: Lecithin plugin compatibility suite fixture ($id)",
            ) + apiLine + listOf(
                "commands:",
                "  cfx-$id:",
                "    description: compat suite control (phase <name> | sink <tag>)",
            )).joinToString("\n", postfix = "\n"))
            File(dir, "compat-fixture.properties").writeText(listOf(
                "fixture.id=$id",
                "fixture.label=${label.get()}",
                "fixture.api=${api.get()}",
                "fixture.release=${release.get()}",
                "fixture.apiVersion=${apiVersion.get()}",
                "fixture.layers=${layers.get()}",
            ).joinToString("\n", postfix = "\n"))
        }
        File(out, "victim").apply { mkdirs() }.let { dir ->
            File(dir, "plugin.yml").writeText((listOf(
                "name: CfxVictim-$id",
                "version: '1'",
                "main: fun.bm.lecithin.compatsuite.victim.VictimPlugin",
                "description: Lifecycle victim for the compat suite ($id)",
            ) + apiLine).joinToString("\n", postfix = "\n"))
        }
    }
}

val fixtureOutput = layout.buildDirectory.dir("fixtures")
val fixtureProjects = subprojects.filter { it.path.startsWith(":fixtures:") }

configure(fixtureProjects) {
    apply(plugin = "java")
    val props = Properties().apply { file("fixture.properties").reader(Charsets.UTF_8).use { load(it) } }
    fun prop(key: String): String = props.getProperty(key)?.trim() ?: error("$path/fixture.properties: missing '$key'")
    val id = name
    val layers = prop("layers").split(',').map { it.trim() }.filter { it.isNotEmpty() }
    require("base" in layers) { "$path: every era must list the 'base' layer" }

    val sourceSets = the<SourceSetContainer>()
    sourceSets.named("main") {
        java.setSrcDirs(layers.map { rootProject.file("fixture-common/src/$it/java") } + file("src/main/java"))
        resources.setSrcDirs(emptyList<File>())
    }
    dependencies {
        add("compileOnly", prop("api"))
    }
    tasks.withType<JavaCompile>().configureEach {
        options.release.set(prop("release").toInt())
        options.encoding = "UTF-8"
        options.compilerArgs.addAll(listOf("-Xlint:-options", "-Xlint:-deprecation", "-Xlint:-removal"))
    }

    val descriptors = tasks.register<GenerateFixtureDescriptors>("fixtureDescriptors") {
        fixtureId.set(id)
        label.set(prop("label"))
        api.set(prop("api"))
        release.set(prop("release"))
        apiVersion.set(props.getProperty("apiVersion", "").trim())
        this.layers.set(layers.joinToString(","))
        outputDir.set(layout.buildDirectory.dir("generated/compat-descriptors"))
    }
    val classes = sourceSets["main"].output
    val fixtureJar = tasks.register<Jar>("fixtureJar") {
        archiveFileName.set("CompatFixture-$id.jar")
        destinationDirectory.set(fixtureOutput)
        from(classes) { exclude("fun/bm/lecithin/compatsuite/victim/**") }
        from(descriptors.flatMap { it.outputDir.dir("fixture") })
    }
    val victimJar = tasks.register<Jar>("victimJar") {
        archiveFileName.set("CfxVictim-$id.jar")
        destinationDirectory.set(fixtureOutput)
        from(classes) { include("fun/bm/lecithin/compatsuite/victim/**") }
        from(descriptors.flatMap { it.outputDir.dir("victim") })
    }
    tasks.named("assemble") { dependsOn(fixtureJar, victimJar) }
}

project(":runner") {
    apply(plugin = "java")
    dependencies {
        add("implementation", "com.google.code.gson:gson:2.13.1")
        add("testImplementation", "org.junit.jupiter:junit-jupiter:5.13.4")
        add("testRuntimeOnly", "org.junit.platform:junit-platform-launcher")
    }
    tasks.withType<JavaCompile>().configureEach {
        options.release.set(21)
        options.encoding = "UTF-8"
    }
    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
    }
}

val fixtures = tasks.register("fixtures") {
    group = "compat suite"
    description = "Build every era's fixture and victim jar into build/fixtures"
    dependsOn(fixtureProjects.map { "${it.path}:fixtureJar" }, fixtureProjects.map { "${it.path}:victimJar" })
}

val runnerClasspath = project(":runner").the<SourceSetContainer>()["main"].runtimeClasspath

/** Maps -Pcompat.<name>=value onto runner options. */
fun JavaExec.passCompatProperties(vararg names: String) {
    for (name in names) {
        providers.gradleProperty("compat.$name").orNull?.let { args("--$name", it) }
    }
}

tasks.register<JavaExec>("runSuite") {
    group = "compat suite"
    description = "Boot every target server with the fixtures, drive the phases, write results + differential report"
    dependsOn(fixtures)
    classpath = runnerClasspath
    mainClass.set("fun.bm.lecithin.compatsuite.runner.SuiteMain")
    workingDir = projectDir
    args("run", "--suite", "suite.properties", "--fixtures", "build/fixtures", "--out", "build/compat-runs")
    passCompatProperties("targets", "lecithinJar", "paperJar", "java", "runId")
}

tasks.register<JavaExec>("reportSuite") {
    group = "compat suite"
    description = "Re-generate the differential report of an existing run (-Pcompat.run=<run dir>)"
    classpath = runnerClasspath
    mainClass.set("fun.bm.lecithin.compatsuite.runner.SuiteMain")
    workingDir = projectDir
    args("report", "--suite", "suite.properties")
    passCompatProperties("run")
}
