import java.io.File
import java.util.jar.JarFile
import kotlinx.kover.gradle.plugin.dsl.CoverageUnit
import org.jetbrains.intellij.platform.gradle.TestFrameworkType
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes

// ASM reads the Solr and Lucene class files that back the generated catalog. It sits on the
// buildscript classpath rather than being a project dependency because it is a tool this build
// runs, not a library the plugin ships.
buildscript {
    repositories { mavenCentral() }
    dependencies { classpath("org.ow2.asm:asm:9.9") }
}

plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.intellij.platform")
    id("org.jetbrains.changelog")
    id("org.jetbrains.kotlinx.kover")
    id("org.sonarqube")
    id("org.jetbrains.dokka")
}

kotlin {
    // JDK 21 is the floor set by the version-support policy (see specs/0002-solr-intellij-plugin.md
    // "Version-support policy"): Solr 10 requires Java 21, and the reference-data generator reflects
    // over Solr artifacts from every supported line. A JDK 21 toolchain also loads the previous
    // major's lower-baseline classes, so one toolchain spans all supported lines.
    jvmToolchain(21)
}

dependencies {
    testImplementation("junit:junit:4.13.2")

    // IntelliJ Platform Gradle Plugin Dependencies Extension - read more: https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin-dependencies-extension.html
    intellijPlatform {
        // IntelliJ IDEA is a single unified distribution as of 2025.3 (Community/Ultimate merged),
        // so we target the unified `intellijIdea(...)` artifact. XML PSI (configset parsing) is part
        // of platform core, so no extra bundledPlugin is needed yet. Phase 3 will add
        // bundledPlugin("com.intellij.java").
        intellijIdea("2026.2")
        testFramework(TestFrameworkType.Platform)
    }
}

// Open the demo fixture in the sandbox IDE instead of whatever project the sandbox happened to
// have open last. IntelliJ takes a project path as a positional argument; passing it through an
// argument provider rather than `args` keeps it lazy, so the path is resolved at execution time and
// the task stays compatible with the configuration cache.
//
// Overridable for the cases where the demo project is the wrong target — inspecting a real-world
// configset, or reproducing a bug report:
//
//   ./gradlew runIde -PrunIdeProject=/path/to/project    open something else
//   ./gradlew runIde -PrunIdeProject=                    open nothing; sandbox restores its own state
tasks.runIde {
    // Resolved through providers only. An earlier version called `file(path)` inside the argument
    // provider, which captures the Gradle `Project` — a script object reference the configuration
    // cache cannot serialize, so `runIde` failed the moment the cache had to be rebuilt.
    val requested = providers.gradleProperty("runIdeProject")
        .orElse(layout.projectDirectory.dir("demo").asFile.absolutePath)
        .map { path ->
            // A blank value is the documented opt-out, and a path to a directory that does not
            // exist must not become an argument the IDE tries to interpret as a file to create.
            if (path.isNotBlank() && File(path).isDirectory) listOf(path) else emptyList()
        }
    argumentProviders.add(CommandLineArgumentProvider { requested.get() })
}

// SonarCloud analysis. Runs from CI rather than SonarCloud's Automatic Analysis, because
// Automatic Analysis cannot ingest a coverage report — the two modes are mutually exclusive.
sonar {
    properties {
        property("sonar.projectKey", "adityamparikh_solr-intellij-plugin")
        property("sonar.organization", "adityamparikh")
        property("sonar.host.url", "https://sonarcloud.io")
        // Kover writes a JaCoCo-format XML report; this is the property that makes coverage
        // visible in SonarCloud at all.
        property(
            "sonar.coverage.jacoco.xmlReportPaths",
            layout.buildDirectory.file("reports/kover/report.xml").get().asFile.path,
        )
    }
}

// Code coverage. SonarCloud cannot compute coverage itself — it ingests a report, so the XML
// variant must stay enabled for `sonar.coverage.jacoco.xmlReportPaths` to resolve.
kover {
    reports {
        total {
            xml {
                // Uploaded as an artifact by the Test job in .github/workflows/build.yml. Once
                // CI-based Sonar analysis replaces Automatic Analysis, this is the path
                // `sonar.coverage.jacoco.xmlReportPaths` will point at.
                xmlFile = layout.buildDirectory.file("reports/kover/report.xml")
            }
            html {
                onCheck = false
            }

            filters {
                excludes {
                    // Thin DynamicBundle wrapper: platform resource-bundle plumbing with no branches
                    // of our own, and exercising it meaningfully needs a running IDE.
                    classes("org.apache.solr.ide.SolrBundle*")
                }
            }

            // Coverage floor, enforced as part of `check`. Set at 80% against 100% actual: the
            // headroom is deliberate, so that adding UI and PSI code — which is awkward to unit
            // test — does not block a PR the moment it lands. Sonar's new-code gate is what
            // catches gradual erosion; this rule is the backstop against a sharp drop.
            verify {
                onCheck = true
                rule {
                    bound {
                        minValue = 80
                        coverageUnits = CoverageUnit.LINE
                    }
                }
            }
        }
    }
}
// API documentation. `reportUndocumented` emits a warning for every public declaration without
// KDoc and `failOnWarning` turns those warnings into a build failure, so undocumented public API
// cannot merge. Only the `main` source set is gated — tests are exempt by design.
dokka {
    moduleName = "Solr IntelliJ Plugin"

    dokkaPublications.html {
        failOnWarning = true
    }

    dokkaSourceSets.main {
        reportUndocumented = true

        // Module and package overviews (the landing page and per-package headers).
        includes.from("docs/Module.md")

        // Deep-link every documented symbol to its exact line on GitHub.
        sourceLink {
            localDirectory = file("src/main/kotlin")
            remoteUrl("${providers.gradleProperty("pluginRepositoryUrl").get()}/tree/main/src/main/kotlin")
            remoteLineSuffix = "#L"
        }
    }
}

// Documentation coverage joins the coverage floor as part of `check`, so both gates fire together
// on `./gradlew build` locally and in the CI step that runs `check` — no separate workflow to
// keep in sync.
tasks.check {
    dependsOn(tasks.dokkaGenerate)
}

// ---------------------------------------------------------------------------------------------
// The generated class catalog.
//
// A configset names Solr classes as strings — a field type's `class`, a tokenizer's, a filter's —
// and the plugin cannot complete or explain any of them without knowing what exists. That list is
// roughly 170 entries per Solr line and changes between lines, which is why it is generated here
// rather than written down. The specification argues this out under "The factory catalog".
//
// It runs in the build, where reading Solr's artifacts is ordinary, and ships the result. ASM reads
// the class files directly rather than loading them: loading Solr classes runs their static
// initialisers, and nothing here needs a live class — only its name and its ancestry.
// ---------------------------------------------------------------------------------------------

// The Solr lines this plugin supports, newest first. **This is the single place they are declared**,
// so adding a line or dropping an end-of-life one is one edit. The policy itself lives in the
// specification's "Version support" section: whatever Apache Solr has not declared EOL.
val supportedSolrLines = mapOf(
    "10" to "10.0.0",
    "9" to "9.10.1",
)

supportedSolrLines.forEach { (line, version) ->
    val configuration = configurations.create("solrArtifacts$line") {
        isCanBeConsumed = false
        isCanBeResolved = true
    }
    dependencies.add(configuration.name, "org.apache.solr:solr-core:$version")
    // Chinese (smartcn), ICU, Stempel and Morfologik ship in Solr's analysis-extras module rather
    // than in core, so resolving core alone produced a catalog with Japanese and Korean support and
    // no Chinese at all. A configset can name these, so the catalog has to know them.
    dependencies.add(configuration.name, "org.apache.solr:solr-analysis-extras:$version")
}

/** The Lucene SPI files naming each kind of analysis component, and the catalog kind for each. */
val analysisServices = mapOf(
    "org.apache.lucene.analysis.TokenizerFactory" to "tokenizer",
    "org.apache.lucene.analysis.TokenFilterFactory" to "tokenFilter",
    "org.apache.lucene.analysis.CharFilterFactory" to "charFilter",
)

val generateSolrCatalog by tasks.registering {
    group = "build"
    description = "Reads each supported Solr line's artifacts into the shipped class catalog."

    val outputDirectory = layout.buildDirectory.dir("generated/solr-catalog-resources")
    outputs.dir(outputDirectory)

    // Everything the action touches is captured here as a plain value. A task action that calls a
    // function declared in this script, or reads one of its properties, holds the script object —
    // which the configuration cache cannot serialize. `runIde` above carries the same scar.
    val artifactsByLine = supportedSolrLines.keys.associateWith { line ->
        configurations.getByName("solrArtifacts$line") as FileCollection
    }
    artifactsByLine.forEach { (line, files) -> inputs.files(files).withPropertyName("solrArtifacts$line") }
    val versions = supportedSolrLines.toMap()
    val services = analysisServices.toMap()

    doLast {
        // Declared inside the action for the same reason: a local function captures nothing but its
        // enclosing locals, where a script-level one would drag the script in with it.
        // How an analysis factory reads its own attributes out of the argument map, recognized by
        // the shape of the call rather than by a list of method names. Reflection cannot see these:
        // the names are string literals in the constructor body, so they are neither fields nor
        // annotations. This is the whole reason the catalog is built from bytecode.
        //
        // Every reader on `AbstractAnalysisFactory` that takes an attribute name has the signature
        // `(Map<String, String>, String, ...)`, so the descriptor identifies one exactly. An earlier
        // revision listed seventeen method names instead, which had to be edited whenever Solr added
        // a reader -- and a reader that went unlisted dropped real attributes silently. Under the
        // unknown-attribute inspection this catalog now feeds, a dropped attribute becomes a warning
        // on a *correct* file, so closing that path matters more than the tidiness.
        //
        // Three names from that list are deliberately not matched here, and their absence is the
        // point rather than an oversight: `getLines`, `getWordSet` and `getSnowballWordSet` take a
        // `ResourceLoader`, not the argument map. They consume a filename that a real reader already
        // resolved -- `getWordSet(loader, get(args, "words"), ignoreCase)` -- so matching them
        // harvested whatever literal happened to be pending. The generated catalog is unchanged by
        // their removal, which is the assertion `SolrClassCatalogTest` makes.
        val argumentMapReader = "(Ljava/util/Map;Ljava/lang/String;"

        // The value type, taken from the JVM return descriptor. This is the compiler's own answer
        // about what the factory did with the string, which is why it is read here rather than
        // mapped from the method name: `getInt` returning `I` and a name-to-type table saying the
        // same thing are two records of one fact, and only one of them cannot drift.
        fun valueTypeOf(descriptor: String): String = when (descriptor.substringAfterLast(')')) {
            "I" -> "int"
            "Z" -> "bool"
            "F", "D" -> "float"
            // `Ljava/lang/String;`, `C`, `Ljava/util/Set;`, `Ljava/util/regex/Pattern;` and the
            // erased `Map.remove` all land here. `free` is a positive statement that any value is
            // legal, not "unknown, so guess" -- nothing typed `free` is ever value-checked.
            else -> "free"
        }

        // A name read as two different types somewhere in one inheritance chain resolves to `free`.
        // A conflict means the extraction did not fully understand the class, and this plugin
        // already prefers declining a claim to making a confident wrong one.
        fun mergeType(existing: String?, incoming: String): String =
            if (existing == null || existing == incoming) incoming else "free"

        // How many parameters of a call could hold a string. The attribute name is always the
        // first of them, so counting them lets the reader take the literals that belong to *this*
        // call rather than whatever happened to be pushed before it.
        fun stringLikeParameters(descriptor: String): Int {
            val parameters = descriptor.substringAfter('(').substringBefore(')')
            var index = 0
            var count = 0
            while (index < parameters.length) {
                when (parameters[index]) {
                    'L' -> {
                        val end = parameters.indexOf(';', index)
                        if (end < 0) return count
                        val type = parameters.substring(index + 1, end)
                        if (type == "java/lang/String" || type == "java/lang/Object") count++
                        index = end + 1
                    }
                    // An array prefix; the element type follows and is handled on the next pass.
                    '[' -> index++
                    else -> index++
                }
            }
            return count
        }

        fun buildCatalog(line: String, version: String, jars: List<File>): String {
            val superclasses = mutableMapOf<String, String?>()
            val abstractClasses = mutableSetOf<String>()
            val factories = mutableMapOf<String, MutableSet<String>>()
            // Class -> attribute name -> value type token.
            val declaredAttributes = mutableMapOf<String, MutableMap<String, String>>()

            for (jar in jars) {
                JarFile(jar).use { open ->
                    for (entry in open.entries()) {
                        val entryName = entry.name
                        if (entryName.endsWith(".class")) {
                            open.getInputStream(entry).use { input ->
                                val reader = ClassReader(input)
                                superclasses[reader.className] = reader.superName
                                if (reader.access and Opcodes.ACC_ABSTRACT != 0) {
                                    abstractClasses += reader.className
                                }
                                if (reader.className.endsWith("Factory")) {
                                    val found = sortedMapOf<String, String>()
                                    reader.accept(
                                        object : ClassVisitor(Opcodes.ASM9) {
                                            override fun visitMethod(
                                                access: Int,
                                                methodName: String,
                                                descriptor: String,
                                                signature: String?,
                                                exceptions: Array<out String>?,
                                            ): MethodVisitor? {
                                                if (methodName != "<init>") return null
                                                return object : MethodVisitor(Opcodes.ASM9) {
                                                    // Strings pushed since the last call, in order.
                                                    // The *first* is the attribute name: a reader
                                                    // may take a default too, as in
                                                    // `get(args, "language", "English")`, and
                                                    // taking the most recent literal picked up the
                                                    // default instead of the name.
                                                    private val pending = mutableListOf<String>()

                                                    override fun visitLdcInsn(value: Any?) {
                                                        if (value is String) pending += value
                                                    }

                                                    override fun visitMethodInsn(
                                                        opcode: Int,
                                                        owner: String,
                                                        called: String,
                                                        methodDescriptor: String,
                                                        isInterface: Boolean,
                                                    ) {
                                                        // The owner guard keeps `Map.get` and
                                                        // `List.get` out: only a call on the
                                                        // factory hierarchy reads an argument.
                                                        val onFactory = owner.endsWith("Factory") ||
                                                            owner == "org/apache/lucene/analysis/AbstractAnalysisFactory"
                                                        // Rule A: the call takes the argument map
                                                        // and then a name. The descriptor says so
                                                        // exactly, and its return type says what
                                                        // the value is.
                                                        val typedRead = onFactory &&
                                                            methodDescriptor.startsWith(argumentMapReader)
                                                        // Rule B: `args.remove("userDictionary")`
                                                        // is a real read -- a factory consuming an
                                                        // argument this way still accepts it -- but
                                                        // `Map.remove` is erased to
                                                        // `(Ljava/lang/Object;)Ljava/lang/Object;`,
                                                        // so it can never carry a type.
                                                        val erasedRead = called == "remove" &&
                                                            owner == "java/util/Map"
                                                        val isRead = typedRead || erasedRead
                                                        val valueType =
                                                            if (typedRead) valueTypeOf(methodDescriptor) else "free"
                                                        if (isRead) {
                                                            // Only the literals this call could
                                                            // have consumed. Taking the head of
                                                            // everything pushed since the last read
                                                            // let a stray literal -- an exception
                                                            // message, a builder append -- sit in
                                                            // front of the real name and be read as
                                                            // the attribute.
                                                            val consumed = pending.takeLast(
                                                                stringLikeParameters(methodDescriptor),
                                                            )
                                                            // Every Solr and Lucene attribute name
                                                            // is lowerCamelCase. The guard catches
                                                            // the defaults that still reach here by
                                                            // paths the rule above does not cover.
                                                            consumed.firstOrNull()
                                                                ?.takeIf { it.isNotEmpty() && it[0].isLowerCase() }
                                                                // `class` and `name` are how a
                                                                // component names its factory, not
                                                                // parameters the factory accepts.
                                                                // The base class strips them with
                                                                // `args.remove`, which reads
                                                                // identically to a real one.
                                                                ?.takeIf { it != "class" && it != "name" }
                                                                ?.let { found[it] = mergeType(found[it], valueType) }
                                                        }
                                                        // Cleared only after a read, not after
                                                        // every call. An earlier version cleared on
                                                        // any call, which lost the name whenever the
                                                        // default was computed by one --
                                                        // `get(args, MODE, DEFAULT_MODE.toString())`
                                                        // dropped `mode` from the Japanese
                                                        // tokenizer entirely.
                                                        if (isRead) pending.clear()
                                                    }
                                                }
                                            }
                                        },
                                        ClassReader.SKIP_FRAMES or ClassReader.SKIP_DEBUG,
                                    )
                                    if (found.isNotEmpty()) declaredAttributes[reader.className] = found
                                }
                            }
                        } else if (entryName.startsWith("META-INF/services/")) {
                            val kind = services[entryName.removePrefix("META-INF/services/")]
                            if (kind != null) {
                                val implementations = open.getInputStream(entry).bufferedReader()
                                    .readLines()
                                    .map { it.substringBefore('#').trim() }
                                    .filter { it.isNotEmpty() }
                                factories.getOrPut(kind) { mutableSetOf() } += implementations
                            }
                        }
                    }
                }
            }

            // Solr's resource loader resolves `solr.X` against its own packages *and* Lucene's
            // analysis packages, which is why one short form covers both populations.
            fun shortName(binaryName: String) = "solr." + binaryName.substringAfterLast('.')

            fun descendsFromFieldType(internalName: String): Boolean {
                var current: String? = superclasses[internalName]
                var depth = 0
                while (current != null && depth++ < 40) {
                    if (current == "org/apache/solr/schema/FieldType") return true
                    current = superclasses[current]
                }
                return false
            }

            val fieldTypes = superclasses.keys
                .filter { '$' !in it && it !in abstractClasses && descendsFromFieldType(it) }
                .map { it.replace('/', '.') }
                .sorted()

            // A factory inherits whatever its superclasses read. `EdgeNGramFilterFactory` declares
            // its own three; every analysis factory also accepts `luceneMatchVersion` from the root
            // of the hierarchy. Reporting only the leaf's own literals would omit real attributes.
            fun attributesOf(binaryName: String): List<String> {
                val internal = binaryName.replace('.', '/')
                val collected = sortedMapOf<String, String>()
                var current: String? = internal
                var depth = 0
                while (current != null && depth++ < 40) {
                    declaredAttributes[current]?.forEach { (name, type) ->
                        collected[name] = mergeType(collected[name], type)
                    }
                    current = superclasses[current]
                }
                return collected.map { (name, type) -> "$name:$type" }
            }

            return buildString {
                appendLine("# Generated by :generateSolrCatalog - do not edit.")
                appendLine("# Solr line $line, read from $version.")
                appendLine("# kind\tclass\tshort name\tattributes")
                for (className in fieldTypes) appendLine("fieldType\t$className\t${shortName(className)}\t")
                for ((kind, implementations) in factories.toSortedMap()) {
                    for (className in implementations.sorted()) {
                        val attributes = attributesOf(className).joinToString(",")
                        appendLine("$kind\t$className\t${shortName(className)}\t$attributes")
                    }
                }
            }
        }

        // Nested one level so the shipped resource path is `solr-catalog/solr-10.tsv` rather than
        // a bare file at the resource root, where it would collide with anything else generated.
        val directory = outputDirectory.get().asFile.resolve("solr-catalog")
        directory.mkdirs()
        for ((line, files) in artifactsByLine) {
            // Every Solr and Lucene artifact, not a hand-picked pair. An earlier version scanned
            // only `solr-core` and `lucene-analysis-common` and produced a list that looked right
            // at a glance while missing `StandardTokenizerFactory` — which lives in `lucene-core`
            // and is the most-used tokenizer there is — along with every language module. Solr's
            // own dependencies (Jetty, ZooKeeper) are still excluded, since they carry nothing a
            // configset can name.
            val relevant = files.files.filter {
                it.name.startsWith("solr-") || it.name.startsWith("lucene-")
            }
            File(directory, "solr-$line.tsv").writeText(buildCatalog(line, versions.getValue(line), relevant))
        }
    }
}

// The catalog ships with the plugin, so it is a resource of the main source set. This is what makes
// `processResources` depend on the generator, and therefore what makes a clean build regenerate it.
sourceSets.named("main") { resources.srcDir(generateSolrCatalog) }
