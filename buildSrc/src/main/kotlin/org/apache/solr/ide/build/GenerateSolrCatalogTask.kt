package org.apache.solr.ide.build

import java.io.File
import java.util.jar.JarFile
import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Classpath
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Nested
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes

/**
 * Reads each supported Solr line's artifacts into the shipped class catalog.
 *
 * A configset names Solr classes as strings — a field type's `class`, a tokenizer's, a filter's —
 * and the plugin cannot complete or explain any of them without knowing what exists. That list is
 * roughly 170 entries per Solr line and changes between lines, which is why it is generated here
 * rather than written down. The specification argues this out under "The factory catalog".
 *
 * It runs in the build, where reading Solr's artifacts is ordinary, and ships the result. ASM reads
 * the class files directly rather than loading them: loading Solr classes runs their static
 * initialisers, and nothing here needs a live class — only its name and its ancestry.
 *
 * Beside each catalog it writes `field-properties-<line>.txt`: the attribute names `FieldProperties`
 * accepts on a `<field>`, read from the same bytecode. The hand-maintained field table treats its
 * list as complete, and `SolrFieldPropertyDriftTest` holds that claim against this file — so a Solr
 * line growing a new field property fails the build by name instead of underlining a correct file.
 */
@CacheableTask
abstract class GenerateSolrCatalogTask : DefaultTask() {

    /** One supported Solr line, as the task consumes it. Register lines through [solrLine]. */
    abstract class SolrLine {
        /** The line key, e.g. `"10"`. Names the output file, `solr-10.tsv`. */
        @get:Input
        abstract val line: Property<String>

        /** The concrete version the artifacts resolve from, recorded in the file's header. */
        @get:Input
        abstract val version: Property<String>

        /** The line's resolved artifacts — Solr's own jars plus everything they pull in. */
        @get:Classpath
        abstract val artifacts: ConfigurableFileCollection
    }

    /** The Solr lines to catalog, one output file each. */
    @get:Nested
    abstract val solrLines: ListProperty<SolrLine>

    /** The resource root the catalog is written under; joins the main source set's resources. */
    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    /** Registers the Solr [line] at [version], reading [artifacts] (anything `from()` accepts). */
    fun solrLine(line: String, version: String, artifacts: Any) {
        val input = project.objects.newInstance(SolrLine::class.java)
        input.line.set(line)
        input.version.set(version)
        input.artifacts.from(artifacts)
        solrLines.add(input)
    }

    /** The Lucene SPI files naming each kind of analysis component, and the catalog kind for each. */
    private val analysisServices = mapOf(
        "org.apache.lucene.analysis.TokenizerFactory" to "tokenizer",
        "org.apache.lucene.analysis.TokenFilterFactory" to "tokenFilter",
        "org.apache.lucene.analysis.CharFilterFactory" to "charFilter",
    )

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
    private val argumentMapReader = "(Ljava/util/Map;Ljava/lang/String;"

    // How a field type consumes an argument, on the plain map. Three JDK method names is a small
    // hardcoded set, and acceptable where the seventeen Solr reader names were not: `java.util.Map`
    // has been stable for decades, where Solr's factory API is precisely the thing that moves.
    private val mapReaders = setOf("get", "remove", "containsKey")

    // Where Solr declares what a <field> accepts. `propertyNames` is the parser's whole
    // vocabulary, and `isPropertyIgnored` names the attributes it waves through without acting
    // on — `default`, and the two format selectors that only mean something on the type.
    // Everything else on a <field> throws `Invalid field property` at core load.
    private val fieldPropertiesClass = "org/apache/solr/schema/FieldProperties.class"

    // The accepted names, read from the two places FieldProperties keeps them. `propertyNames`
    // is filled in the static initialiser as one `ldc`/`aastore` pair per name — nothing else in
    // that initialiser stores a string into an array — and `isPropertyIgnored` holds its names as
    // the literals it compares against. Reading the strings out of exactly those two methods is
    // the same bet the catalog makes everywhere: the bytecode cannot drift from what Solr does,
    // where a copied list can.
    private fun acceptedFieldProperties(jars: List<File>): Set<String> {
        val names = sortedSetOf<String>()
        for (jar in jars) {
            JarFile(jar).use { open ->
                val entry = open.getJarEntry(fieldPropertiesClass) ?: return@use
                open.getInputStream(entry).use { input ->
                    ClassReader(input).accept(
                        object : ClassVisitor(Opcodes.ASM9) {
                            override fun visitMethod(
                                access: Int,
                                methodName: String,
                                descriptor: String,
                                signature: String?,
                                exceptions: Array<out String>?,
                            ): MethodVisitor? {
                                val storesTheArray = methodName == "<clinit>"
                                val comparesIgnored = methodName == "isPropertyIgnored"
                                if (!storesTheArray && !comparesIgnored) return null
                                return object : MethodVisitor(Opcodes.ASM9) {
                                    private var pending: String? = null

                                    override fun visitLdcInsn(value: Any?) {
                                        if (value !is String) return
                                        if (comparesIgnored) names += value else pending = value
                                    }

                                    override fun visitInsn(opcode: Int) {
                                        if (opcode == Opcodes.AASTORE) {
                                            pending?.let { names += it }
                                            pending = null
                                        }
                                    }
                                }
                            }
                        },
                        ClassReader.SKIP_FRAMES or ClassReader.SKIP_DEBUG,
                    )
                }
            }
        }
        return names
    }

    private fun buildFieldProperties(line: String, version: String, jars: List<File>): String =
        buildString {
            appendLine("# Generated by :generateSolrCatalog - do not edit.")
            appendLine("# Solr line $line, read from $version.")
            appendLine("# The attribute names FieldProperties accepts on a <field>.")
            acceptedFieldProperties(jars).forEach { appendLine(it) }
        }

    private fun buildCatalog(line: String, version: String, jars: List<File>): String {
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
                            // A field type is read as well as a factory, and the two differ in
                            // every mechanical detail below. Selected by package rather than by
                            // ancestry because `superclasses` is still being filled by this very
                            // loop, so asking "does this descend from FieldType" here would
                            // depend on jar entry order. Every field type Solr ships lives in
                            // `org.apache.solr.schema` -- 33 of them on Solr 10, 35 on Solr 9,
                            // and none anywhere else -- so the package is an exact prefilter.
                            // The definitive ancestry test still runs, after the loop.
                            val schemaClass = reader.className.startsWith("org/apache/solr/schema/")
                            if (reader.className.endsWith("Factory") || schemaClass) {
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
                                            // A factory reads its arguments in its constructor,
                                            // and nowhere else.
                                            //
                                            // A field type threads the map through whatever
                                            // helper it likes. `init(IndexSchema, Map)` is the
                                            // entry point, but `CollationField` reads in
                                            // `setup(ResourceLoader, Map)` and `EnumFieldType`
                                            // in a nested class's constructor, so naming the
                                            // methods would be the same losing game as naming
                                            // the readers. Every method is visited instead.
                                            //
                                            // The direction of the error is why that is safe.
                                            // A spurious attribute makes the plugin *more*
                                            // permissive -- it silences a warning it would
                                            // otherwise raise -- while a missing one turns a
                                            // correct `language="en"` into an underline. The
                                            // `Map` owner, lowercase-initial and not-class-or-
                                            // name guards below still apply to everything found.
                                            val reads = methodName == "<init>" || schemaClass
                                            if (!reads) return null
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
                                                    //
                                                    // `Map.get` joins it for field types only,
                                                    // and that is the whole mechanism by which
                                                    // they are read: a field type calls
                                                    // `args.get("defaultCurrency")` on the plain
                                                    // map, never a typed reader. Admitting it
                                                    // for factories too would undo the owner
                                                    // guard above, which exists to keep every
                                                    // unrelated `Map.get` and `List.get` out.
                                                    val erasedReaders =
                                                        if (schemaClass) mapReaders else setOf("remove")
                                                    val erasedRead = called in erasedReaders &&
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
                        val kind = analysisServices[entryName.removePrefix("META-INF/services/")]
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

            // A class may hand the argument map to a nested helper and let *it* do the reading,
            // in which case the literals are recorded against the helper and the enclosing class
            // looks like it accepts nothing. `EnumFieldType` is the case that forced this:
            // `enumsConfig` and `enumName` are read in `EnumFieldType$EnumMapping`, so before
            // this the catalog said `EnumFieldType` took no attributes at all -- and under the
            // unknown-attribute rule that turns a correct `enumsConfig="..."` into a warning.
            fun merge(owner: String) {
                declaredAttributes[owner]?.forEach { (name, type) ->
                    collected[name] = mergeType(collected[name], type)
                }
                val nested = "$owner$"
                for ((className, attributes) in declaredAttributes) {
                    if (!className.startsWith(nested)) continue
                    attributes.forEach { (name, type) ->
                        collected[name] = mergeType(collected[name], type)
                    }
                }
            }

            var current: String? = internal
            var depth = 0
            while (current != null && depth++ < 40) {
                merge(current)
                current = superclasses[current]
            }
            return collected.map { (name, type) -> "$name:$type" }
        }

        return buildString {
            appendLine("# Generated by :generateSolrCatalog - do not edit.")
            appendLine("# Solr line $line, read from $version.")
            appendLine("# kind\tclass\tshort name\tattributes")
            for (className in fieldTypes) {
                val attributes = attributesOf(className).joinToString(",")
                appendLine("fieldType\t$className\t${shortName(className)}\t$attributes")
            }
            for ((kind, implementations) in factories.toSortedMap()) {
                for (className in implementations.sorted()) {
                    val attributes = attributesOf(className).joinToString(",")
                    appendLine("$kind\t$className\t${shortName(className)}\t$attributes")
                }
            }
        }
    }

    /** Writes one catalog file per registered line under `outputDirectory`. */
    @TaskAction
    fun generate() {
        // Nested one level so the shipped resource path is `solr-catalog/solr-10.tsv` rather than
        // a bare file at the resource root, where it would collide with anything else generated.
        val directory = outputDirectory.get().asFile.resolve("solr-catalog")
        directory.mkdirs()
        for (input in solrLines.get()) {
            // Every Solr and Lucene artifact, not a hand-picked pair. An earlier version scanned
            // only `solr-core` and `lucene-analysis-common` and produced a list that looked right
            // at a glance while missing `StandardTokenizerFactory` — which lives in `lucene-core`
            // and is the most-used tokenizer there is — along with every language module. Solr's
            // own dependencies (Jetty, ZooKeeper) are still excluded, since they carry nothing a
            // configset can name.
            val relevant = input.artifacts.files.filter {
                it.name.startsWith("solr-") || it.name.startsWith("lucene-")
            }
            File(directory, "solr-${input.line.get()}.tsv")
                .writeText(buildCatalog(input.line.get(), input.version.get(), relevant))
            File(directory, "field-properties-${input.line.get()}.txt")
                .writeText(buildFieldProperties(input.line.get(), input.version.get(), relevant))
        }
    }

    // The descriptor-parsing rules, on the companion so `GenerateSolrCatalogTaskTest` can pin
    // them without standing up a Gradle project. They are the part of the extraction that is
    // pure input-to-output; everything stateful stays on the instance.
    internal companion object {

        // The value type, taken from the JVM return descriptor. This is the compiler's own answer
        // about what the factory did with the string, which is why it is read here rather than
        // mapped from the method name: `getInt` returning `I` and a name-to-type table saying the
        // same thing are two records of one fact, and only one of them cannot drift.
        internal fun valueTypeOf(descriptor: String): String = when (descriptor.substringAfterLast(')')) {
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
        internal fun mergeType(existing: String?, incoming: String): String =
            if (existing == null || existing == incoming) incoming else "free"

        // How many parameters of a call could hold a string. The attribute name is always the
        // first of them, so counting them lets the reader take the literals that belong to *this*
        // call rather than whatever happened to be pushed before it.
        internal fun stringLikeParameters(descriptor: String): Int {
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
    }
}
