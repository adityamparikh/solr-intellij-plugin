package org.apache.solr.ide.build

import java.io.File
import java.util.jar.JarEntry
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
import org.objectweb.asm.Handle
import org.objectweb.asm.Label
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type

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
 *
 * The task itself only orchestrates: [ArtifactScanner] does the one pass over the jars,
 * [ClassHierarchy] answers ancestry and inherited-attribute questions about what it found,
 * [FieldPropertyExtractor] handles the separate `FieldProperties` file, and [JavadocSummaries]
 * reads the fifth column — a one-sentence summary of each class's own Javadoc — from the line's
 * `-sources` jars, when those resolved. Each of those, and the ASM visitors they use, is a private
 * top-level class in this file — collaborators of the task rather than nested pieces of it, since
 * none of them touch Gradle or task state.
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

        /**
         * The `-sources` jars for those same artifacts, for the documentation column.
         *
         * May be empty — a module that publishes no sources, or a resolution that failed outright,
         * degrades to no documentation for the classes it would have covered, the same
         * decline-rather-than-guess rule the attribute pass already follows.
         *
         * Jetty and ZooKeeper are not the case to picture here, though this said they were: both
         * publish sources, and the run loop drops them by filename before their contents matter,
         * since they carry nothing a configset can name.
         */
        @get:Classpath
        abstract val sources: ConfigurableFileCollection
    }

    /** The Solr lines to catalog, one output file each. */
    @get:Nested
    abstract val solrLines: ListProperty<SolrLine>

    /** The resource root the catalog is written under; joins the main source set's resources. */
    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    /**
     * Registers the Solr [line] at [version], reading [artifacts] (anything `from()` accepts) and,
     * optionally, the matching [sources] jars for the documentation column.
     */
    fun solrLine(line: String, version: String, artifacts: Any, sources: Any = project.files()) {
        val input = project.objects.newInstance(SolrLine::class.java)
        input.line.set(line)
        input.version.set(version)
        input.artifacts.from(artifacts)
        input.sources.from(sources)
        solrLines.add(input)
    }

    private fun buildFieldProperties(line: String, version: String, jars: List<File>): String =
        buildString {
            appendLine("# Generated by :generateSolrCatalog - do not edit.")
            appendLine("# Solr line $line, read from $version.")
            appendLine("# The attribute names FieldProperties accepts on a <field>.")
            FieldPropertyExtractor.extract(jars).forEach { appendLine(it) }
        }

    /**
     * The request parameters and query parser names one line declares, as its own resource.
     *
     * **A separate file rather than more rows in the class catalog, because these are not classes.** The
     * catalog's reader builds a `SolrClassEntry` per row and resolves its first column through
     * `SolrClassKind`, so folding parameters in would mean teaching an enum of *class* kinds two
     * constants that are not kinds of class — and then answering, in every exhaustive `when` over it,
     * which Reference Guide page documents "the parameter kind". Two resources and two small readers
     * cost less than one type that means two things.
     */
    private fun buildParameters(line: String, version: String, jars: List<File>, sourceJars: List<File>): String {
        val scan = ArtifactScanner(analysisServices).scan(jars)

        // Grouped by declaring interface so each one's source file is read once rather than once per
        // constant: the summaries come from a `-sources` jar entry per interface, and there are twenty
        // interfaces behind several hundred parameters.
        val summaries = scan.parameters.values
            .groupBy { it.owner }
            .mapValues { (owner, origins) ->
                ConstantJavadocSummaries.extract(owner, origins.map { it.fieldName }.distinct(), sourceJars)
            }

        return buildString {
            appendLine("# Generated by :generateSolrCatalog - do not edit.")
            appendLine("# Solr line $line, read from $version.")
            appendLine("# kind\tname\towner\tdocumentation")
            for ((name, origin) in scan.parameters.toSortedMap()) {
                val summary = summaries[origin.owner]?.get(origin.fieldName).orEmpty()
                appendRow(this, "parameter", name, origin.owner, summary)
            }
            // The query parser Javadoc is a class comment, so it comes from the same extractor the class
            // catalog uses rather than the constant one.
            val parserDocumentation = JavadocSummaries.extract(scan.queryParsers.values.distinct(), sourceJars)
            for ((name, className) in scan.queryParsers.toSortedMap()) {
                appendRow(this, "queryParserName", name, className, parserDocumentation[className].orEmpty())
            }
        }
    }

    /** One row of the parameter resource, with the whitespace that would split it collapsed. */
    /**
     * The elements and attributes a `solrconfig.xml` may contain, from all three declarations.
     *
     * **A fourth file for the same reason there is a third**: these are not classes, and folding them
     * into the class catalog would mean a `kind` column carrying entries that are not kinds of class.
     *
     * The three sources overlap and the overlap is deliberate rather than deduplicated away, because
     * they disagree usefully: `SolrConfig.plugins` knows a `<deletionPolicy>` nests under
     * `<indexConfig>`, and the reading code knows Solr accepts several of them. An element named by
     * more than one source appears once, with the facts merged and the sources listed — a consumer
     * asking "may this element appear here" should not have to know which declaration answered.
     */
    private fun buildElements(line: String, version: String, jars: List<File>, scan: ScanResult): String {
        val fromPlugins = scan.pluginPaths.keys.map { path ->
            val (name, parent) = SolrConfigElements.split(path)
            SolrConfigElement(name, parent, SolrConfigElements.SINGLE, SolrConfigElements.FROM_PLUGINS)
        }
        val fromConfig = scan.configElementReads.map { (path, arity) ->
            val (name, parent) = SolrConfigElements.split(path)
            SolrConfigElement(
                name = name,
                parent = parent,
                arity = arity,
                source = SolrConfigElements.FROM_CONFIG,
                discontinued = SolrConfigElements.discontinuedBy(name, scan.configMessages).orEmpty(),
            )
        }
        val fromEditable = readEditableResource(jars)

        return buildString {
            appendLine("# Generated by :generateSolrCatalog - do not edit.")
            appendLine("# Solr line $line, read from $version.")
            appendLine("# The elements and attributes solrconfig.xml may contain.")
            appendLine("# name\tparent\tarity\tsources\tvalue type\tdiscontinued")
            SolrConfigElements.merge(fromPlugins + fromConfig + fromEditable).forEach {
                appendLine(
                    "${it.name}\t${it.parent}\t${it.arity}\t${it.source}\t${it.valueType}\t" +
                        it.discontinued.replace(ROW_BREAKING_WHITESPACE, " "),
                )
            }
        }
    }

    /**
     * `EditableSolrConfigAttributes.json`, read out of whichever jar ships it.
     *
     * Absent rather than fatal if no jar carries it: a Solr line that stopped shipping the resource
     * would lose the three subtrees it describes and keep everything the other two sources give,
     * which is a smaller vocabulary rather than a broken build.
     */
    private fun readEditableResource(jars: List<File>): List<SolrConfigElement> {
        for (jar in jars) {
            JarFile(jar).use { open ->
                open.getJarEntry(EDITABLE_ATTRIBUTES)?.let { entry ->
                    return SolrConfigElements.readEditable(
                        open.getInputStream(entry).use { it.readBytes().decodeToString() },
                    )
                }
            }
        }
        return emptyList()
    }

    private fun appendRow(out: StringBuilder, kind: String, name: String, owner: String, summary: String) {
        out.appendLine("$kind\t$name\t$owner\t${summary.replace(ROW_BREAKING_WHITESPACE, " ")}")
    }

    private fun buildCatalog(
        line: String,
        version: String,
        jars: List<File>,
        sourceJars: List<File>,
        scan: ScanResult,
    ): String {
        val hierarchy = ClassHierarchy(
            scan.superclasses,
            scan.interfaces,
            scan.declaredAttributes,
            scan.docValuesDefaults,
        )

        val fieldTypes = scan.superclasses.keys
            .filter { '$' !in it && it !in scan.abstractClasses }
            .filter { hierarchy.descendsFrom(it, "org/apache/solr/schema/FieldType") }
            .map { it.replace('/', '.') }
            .sorted()

        // Read once, against every top-level class this catalog is about to list, rather than once
        // per row: a sources jar holds one entry per class, so the lookup is by name either way, and
        // doing it here keeps the sources jars out of ArtifactScanner, which knows nothing about
        // documentation and should not have to.
        // One kind per element `solrconfig.xml` accepts, from the roots Solr declares. Concrete only:
        // `SolrRequestHandler` has `RequestHandlerBase` beneath it, which is real, abstract, and not
        // something a configset may name.
        val plugins = scan.pluginRoots.mapValues { (_, root) ->
            scan.superclasses.keys
                .filter { '$' !in it && it !in scan.abstractClasses }
                .filter { hierarchy.descendsFrom(it, root) }
                .map { it.replace('/', '.') }
                .sorted()
        }.filterValues { it.isNotEmpty() }.toSortedMap()

        val documentation = JavadocSummaries.extract(
            fieldTypes + scan.factories.values.flatten() + plugins.values.flatten(),
            sourceJars,
        )

        return buildString {
            appendLine("# Generated by :generateSolrCatalog - do not edit.")
            appendLine("# Solr line $line, read from $version.")
            appendLine("# kind\tclass\tshort name\tattributes\tdocumentation\ttraits")
            for (className in fieldTypes) {
                appendCatalogRow(this, "fieldType", className, hierarchy, documentation[className])
            }
            for ((kind, implementations) in scan.factories.toSortedMap()) {
                for (className in implementations.sorted()) {
                    appendCatalogRow(this, kind, className, hierarchy, documentation[className])
                }
            }
            for ((kind, implementations) in plugins) {
                for (className in implementations) {
                    appendCatalogRow(this, kind, className, hierarchy, documentation[className])
                }
            }
        }
    }

    private fun appendCatalogRow(
        out: StringBuilder,
        kind: String,
        className: String,
        hierarchy: ClassHierarchy,
        documentation: String?,
    ) {
        val attributes = hierarchy.attributesOf(className).joinToString(",")
        // A tab or newline in a summary would corrupt the row; [JavadocSummaries] already collapses
        // whitespace, but the literals are guarded here rather than trusted to stay out. A newline
        // is the worse of the two — a tab merely shifts the columns, a newline splits one class into
        // two rows and the second one parses as garbage.
        val summary = documentation.orEmpty().replace(ROW_BREAKING_WHITESPACE, " ")
        // Empty for every analysis factory, which has no property defaults to carry. Written for
        // all kinds anyway so the row shape stays uniform and the reader needs no special case.
        val traits = hierarchy.traitsOf(className).joinToString(",")
        out.appendLine("$kind\t$className\t${shortName(className)}\t$attributes\t$summary\t$traits")
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
            val relevantSources = input.sources.files.filter {
                it.name.startsWith("solr-") || it.name.startsWith("lucene-")
            }
            // One scan per line, shared by the two files that need it. The pass reads every class in
            // every Solr and Lucene artifact, so scanning again for the element vocabulary would
            // double the slowest part of a clean build to learn nothing new.
            val scan = ArtifactScanner(analysisServices).scan(relevant)
            File(directory, "solr-${input.line.get()}.tsv")
                .writeText(buildCatalog(input.line.get(), input.version.get(), relevant, relevantSources, scan))
            File(directory, "elements-${input.line.get()}.tsv")
                .writeText(buildElements(input.line.get(), input.version.get(), relevant, scan))
            File(directory, "field-properties-${input.line.get()}.txt")
                .writeText(buildFieldProperties(input.line.get(), input.version.get(), relevant))
            File(directory, "parameters-${input.line.get()}.tsv")
                .writeText(buildParameters(input.line.get(), input.version.get(), relevant, relevantSources))
        }
    }

    // The descriptor-parsing rules, on the companion so `GenerateSolrCatalogTaskTest` can pin
    // them without standing up a Gradle project. They are the part of the extraction that is
    // pure input-to-output; everything stateful stays in the collaborator classes below.
    internal companion object {

        /**
         * The resource typing the three subtrees Solr's config API can rewrite.
         *
         * At the jar root rather than under a package, which is why this is a bare name.
         */
        private const val EDITABLE_ATTRIBUTES = "EditableSolrConfigAttributes.json"

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

        // Whether a typed reader carries a default: a third parameter after the (Map, String)
        // every reader opens with. `get(args, name)` has none; `get(args, name, "English")` and
        // `getInt(args, name, 1)` each do. `require*` readers have two parameters and so never do,
        // which is what keeps a required attribute from also carrying a default.
        internal fun hasDefaultParameter(descriptor: String): Boolean =
            !descriptor.removePrefix(argumentMapReader).startsWith(")")

        // The class-level Javadoc comment text immediately preceding `class SimpleName`, or null
        // when none is found. Only the top-level class is ever asked for here: every row this
        // catalog writes is a top-level class, so a nested class's own comment -- read differently,
        // and attributed to a different name -- never needs to be told apart from it.
        internal fun classJavadocComment(source: String, simpleName: String): String? {
            // `(?:(?!\*/).)*?` rather than `.*?`: a lazy `.*?` under DOT_MATCHES_ALL still
            // backtracks across an intervening `*/` when that is what lets the rest of the pattern
            // match, so a file declaring a commented package-private class ahead of its public one
            // would hand the earlier class's comment to the later one. Refusing to cross a
            // terminator keeps each match inside a single comment block.
            val declaration = Regex(
                "/\\*\\*((?:(?!\\*/).)*?)\\*/\\s*(?:@\\w+(?:\\([^)]*\\))?\\s*)*" +
                    "(?:public\\s+|final\\s+|abstract\\s+|static\\s+)*class\\s+${Regex.escape(simpleName)}\\b",
                RegexOption.DOT_MATCHES_ALL,
            )
            return declaration.find(source)?.groupValues?.get(1)
        }

        // The comment's first sentence, in the sense the `javadoc` tool's own overview tables mean
        // it: prose only -- block tags and worked `<pre>` examples cut off, not merely truncated --
        // reduced to what precedes the first ". ". A comment with no sentence-ending period at all
        // reads in full rather than being discarded, since a one-line comment such as "Creates new
        // instances of X" is exactly the case worth keeping.
        internal fun summarizeJavadocComment(comment: String): String? {
            val body = comment.lineSequence()
                .map { it.trim().removePrefix("*").trim() }
                .joinToString("\n")
                .trim()
            if (body.isEmpty()) return null
            // Everything from the first block tag line onward -- `@since`, `@param`, `@lucene.spi`
            // -- is reference material for someone reading the whole comment, not a summary.
            val prose = body.substringBefore("\n@")
            val withLinks = Regex("\\{@link\\s+([^}\\s]+)(?:\\s+([^}]*))?}").replace(prose) { match ->
                val label = match.groupValues[2].trim()
                label.ifEmpty { match.groupValues[1].substringAfterLast('.').substringAfterLast('#') }
            }
            val withCode = Regex("\\{@code\\s+([^}]*)}").replace(withLinks) { it.groupValues[1].trim() }
            // Any inline tag this pass does not specifically render -- `{@value ...}` chief among
            // them -- is dropped rather than shown as a raw brace, on the same footing as a computed
            // default: a tag this pass cannot render correctly is one it declines to render at all.
            val withoutOtherTags = Regex("\\{@\\w+[^}]*}").replace(withCode, "")
            val withoutHtml = Regex("<[^>]+>").replace(withoutOtherTags, "")
            val collapsed = withoutHtml.replace(Regex("\\s+"), " ").trim()
            if (collapsed.isEmpty()) return null
            val sentenceEnd = collapsed.indexOf(". ")
            return if (sentenceEnd >= 0) collapsed.substring(0, sentenceEnd + 1) else collapsed
        }
    }
}

/** The Lucene SPI files naming each kind of analysis component, and the catalog kind for each. */
private val analysisServices = mapOf(
    "org.apache.lucene.analysis.TokenizerFactory" to "tokenizer",
    "org.apache.lucene.analysis.TokenFilterFactory" to "tokenFilter",
    "org.apache.lucene.analysis.CharFilterFactory" to "charFilter",
)

// Solr's resource loader resolves `solr.X` against its own packages *and* Lucene's analysis
// packages, which is why one short form covers both populations.
private fun shortName(binaryName: String) = "solr." + binaryName.substringAfterLast('.')

/**
 * A one-sentence summary of each named class's own Javadoc, read from a Solr line's `-sources`
 * jars.
 *
 * Deliberately just the summary, never the full class comment or its worked examples: Javadoc is
 * not retained in bytecode at all, so this is the one fact in the catalog that cannot come from
 * the compiled artifacts everything else here reads. The plugin's own reasoning for linking to the
 * Reference Guide rather than copying it is why this stays a machine-read summary rather than
 * hand-copied Reference Guide prose -- a second body of documentation drifts out of sync on its
 * own schedule, and a summary read mechanically from the artifact Solr itself published for this
 * exact release cannot.
 *
 * A class absent from every sources jar it is looked up in -- an unresolved module, or one that
 * genuinely publishes no sources -- is simply missing from the result, the same decline-rather-
 * than-guess rule the attribute pass already follows.
 */
private object JavadocSummaries {
    fun extract(classNames: Collection<String>, jars: List<File>): Map<String, String> {
        val found = mutableMapOf<String, String>()
        val remaining = classNames.toMutableSet()
        for (jar in jars) {
            if (remaining.isEmpty()) break
            JarFile(jar).use { open ->
                val resolved = mutableSetOf<String>()
                for (className in remaining) {
                    val entry = open.getJarEntry("${className.replace('.', '/')}.java") ?: continue
                    val source = open.getInputStream(entry).bufferedReader().readText()
                    val comment = GenerateSolrCatalogTask.classJavadocComment(source, className.substringAfterLast('.'))
                        ?: continue
                    val summary = GenerateSolrCatalogTask.summarizeJavadocComment(comment) ?: continue
                    found[className] = summary
                    resolved += className
                }
                remaining -= resolved
            }
        }
        return found
    }
}

/**
 * What one attribute read tells the catalog: the value [type] token, a literal [default] where the
 * bytecode carries one, and whether the reader marked it [required]. The default and the required
 * marker are the two facts the constructor-bytecode pass harvests beyond the name and the type,
 * and they are what the factory half of quick documentation and the restates-the-default
 * inspection consume.
 */
private data class AttributeFacts(
    val type: String,
    val default: String? = null,
    val required: Boolean = false,
)

/**
 * One attribute's facts, folded across every reading of it -- several within a class, then one per
 * ancestor up the hierarchy. Each fold declines a claim it cannot keep rather than picking a side:
 * a type read two ways resolves to `free`, two disagreeing defaults resolve to none, and one
 * optional reading anywhere makes the attribute optional. That is the same bet the type merge
 * already makes, extended to the two new facts. An optional reading with a default therefore beats
 * a required one, which is correct -- an attribute a subclass can omit is not required.
 */
private fun mergeFacts(existing: AttributeFacts?, incoming: AttributeFacts): AttributeFacts {
    if (existing == null) return incoming
    return AttributeFacts(
        type = GenerateSolrCatalogTask.mergeType(existing.type, incoming.type),
        default = when {
            existing.default == incoming.default -> existing.default
            existing.default == null -> incoming.default
            incoming.default == null -> existing.default
            else -> null
        },
        required = existing.required && incoming.required,
    )
}

/**
 * One attribute as the catalog's fourth column writes it: `name:type`, plus a trailing `!` when it
 * is required or `=default` when a literal default was found. The two markers are mutually
 * exclusive, and required wins if both are somehow set, because the reader that proves required
 * takes no default.
 */
private fun encodeAttribute(name: String, facts: AttributeFacts): String {
    val base = "$name:${facts.type}"
    return when {
        facts.required -> "$base!"
        facts.default != null -> "$base=${facts.default}"
        else -> base
    }
}

/**
 * The characters the fourth column's grammar uses, and which therefore may not sit in a default.
 *
 * The line terminators are here for the same reason as the column separators rather than a different
 * one: the catalog is one record per line, so a default carrying `\n` or `\r` would end the row
 * early and leave its remainder to be read as a class of its own.
 */
private val attributeDelimiters = setOf('\t', ',', ':', '=', '!', '\n', '\r')

/** Whitespace that would end a catalog row early, collapsed to a space before a summary is written. */
private val ROW_BREAKING_WHITESPACE = Regex("[\\t\\r\\n]")

/**
 * Everything one [ArtifactScanner] pass over a Solr line's jars discovers, before the class
 * hierarchy is walked: the superclass of every class seen, which are abstract, which analysis
 * factories each SPI file names, and which attributes each factory or field type reads out of its
 * own bytecode.
 */
private class ScanResult {
    val superclasses = mutableMapOf<String, String?>()

    // The interfaces each class declares directly. Needed because five of the elements
    // `solrconfig.xml` accepts are rooted at an *interface* -- `SolrRequestHandler`,
    // `QueryResponseWriter`, `SolrCache`, `SolrEventListener`, `Expressible` -- and a hierarchy that
    // followed only `superName` reported no implementations for any of them. That is the generator's
    // standing failure mode exactly: thirteen kinds appeared, `requestHandler` was not among them, and
    // nothing failed.
    val interfaces = mutableMapOf<String, List<String>>()
    val abstractClasses = mutableSetOf<String>()
    val factories = mutableMapOf<String, MutableSet<String>>()

    // Classes that override `enableDocValuesByDefault`, and the constant each returns. Only the
    // declaring class is recorded; resolving which one applies to a leaf is the hierarchy's job,
    // because `DenseVectorField` overrides its primitive ancestor's `true` back to `false` and only
    // the nearest declaration is the answer.
    val docValuesDefaults = mutableMapOf<String, Boolean>()

    // Class -> attribute name -> the facts one bytecode pass recorded about it.
    val declaredAttributes = mutableMapOf<String, MutableMap<String, AttributeFacts>>()

    // The `solrconfig.xml` element tags Solr declares, each with the root its `class` must implement.
    // Read from a declaration rather than inferred, so a renamed root leaves a missing tag here rather
    // than an empty kind nobody notices.
    val pluginRoots = mutableMapOf<String, String>()

    /**
     * The same plugin tags as [pluginRoots], keyed by the path Solr declares them under.
     *
     * Three of them nest — `indexConfig/deletionPolicy` and its siblings — and the class catalog has
     * nowhere to put that, so it keys on the bare name. The element vocabulary is where the nesting
     * is the point.
     */
    val pluginPaths = mutableMapOf<String, String>()

    /** Each element name `SolrConfig` reads from the tree, and the method that read it. */
    val configElementReads = mutableMapOf<String, String>()

    /** Every string constant in `SolrConfig`, which is where its discontinuation notices live. */
    val configMessages = mutableListOf<String>()

    // Request parameter name -> the params interface that declares it, for the interfaces
    // [SolrParameters.INTERFACES] admits. A name declared in two interfaces keeps the first seen; the
    // owner is documentation rather than identity, and `qf` meaning the same thing in `DisMaxParams`
    // and `SimpleParams` is not a conflict worth reporting.
    val parameters = mutableMapOf<String, ParameterOrigin>()

    // Registered query parser name -> the plugin class registered under it. This is what `defType`
    // accepts, which is not what the `queryParser` class rows carry.
    val queryParsers = mutableMapOf<String, String>()
}

/** One pass over a Solr line's resolved jars, filling a [ScanResult]. */
private class ArtifactScanner(private val analysisServices: Map<String, String>) {

    fun scan(jars: List<File>): ScanResult {
        val result = ScanResult()
        for (jar in jars) {
            JarFile(jar).use { open ->
                for (entry in open.entries()) {
                    when {
                        entry.name.endsWith(".class") -> visitClass(open, entry, result)
                        entry.name.startsWith("META-INF/services/") -> visitService(open, entry, result)
                    }
                }
            }
        }
        return result
    }

    private fun visitClass(open: JarFile, entry: JarEntry, result: ScanResult) {
        open.getInputStream(entry).use { input ->
            val reader = ClassReader(input)
            result.superclasses[reader.className] = reader.superName
            reader.interfaces?.takeIf { it.isNotEmpty() }?.let { result.interfaces[reader.className] = it.toList() }
            if (reader.access and Opcodes.ACC_ABSTRACT != 0) {
                result.abstractClasses += reader.className
            }
            // A field type is read as well as a factory, and the two differ in every mechanical
            // detail the attribute visitors apply below. Selected by package rather than by
            // ancestry because `superclasses` is still being filled by this very pass, so asking
            // "does this descend from FieldType" here would depend on jar entry order. Every field
            // type Solr ships lives in `org.apache.solr.schema` -- 33 of them on Solr 10, 35 on
            // Solr 9, and none anywhere else -- so the package is an exact prefilter. The
            // definitive ancestry test still runs afterwards, in [ClassHierarchy].
            if (reader.className == SolrConfigPlugins.DECLARING_CLASS) {
                val constants = mutableListOf<Any>()
                reader.accept(
                    PluginDeclarationClassVisitor(constants),
                    ClassReader.SKIP_FRAMES or ClassReader.SKIP_DEBUG,
                )
                result.pluginRoots += SolrConfigPlugins.pair(constants)
                result.pluginPaths += SolrConfigPlugins.paths(constants)
            }
            // The same class again, read for a different fact and therefore with a different visitor.
            // The pairing above walks one static initializer's constants; this walks every method
            // body, because the configuration tree is read where each field is assigned rather than
            // in one declaration.
            if (reader.className == SolrConfigElements.DECLARING_CLASS) {
                reader.accept(
                    ConfigElementClassVisitor(result.configElementReads, result.configMessages),
                    ClassReader.SKIP_FRAMES or ClassReader.SKIP_DEBUG,
                )
            }
            if (reader.className == SolrQueryParsers.DECLARING_CLASS) {
                val operands = mutableListOf<Any>()
                reader.accept(
                    InstantiationClassVisitor(operands),
                    ClassReader.SKIP_FRAMES or ClassReader.SKIP_DEBUG,
                )
                result.queryParsers += SolrQueryParsers.pair(operands)
            }
            // A params interface carries its names in `ConstantValue` attributes, so this needs no
            // initializer walk and no hierarchy — which is why it is matched by name here rather than
            // by ancestry afterwards, as the class routes are.
            if (reader.className in SolrParameters.DECLARING_CLASSES) {
                val simpleName = reader.className.substringAfterLast('/')
                val constants = mutableListOf<Pair<String, String>>()
                reader.accept(
                    ParameterConstantVisitor(constants),
                    ClassReader.SKIP_FRAMES or ClassReader.SKIP_DEBUG,
                )
                for ((fieldName, parameter) in SolrParameters.parametersOf(simpleName, constants)) {
                    result.parameters.putIfAbsent(
                        parameter,
                        ParameterOrigin(reader.className.replace('/', '.'), fieldName),
                    )
                }
            }
            val schemaClass = reader.className.startsWith("org/apache/solr/schema/")
            if (reader.className.endsWith("Factory") || schemaClass) {
                val found = sortedMapOf<String, AttributeFacts>()
                reader.accept(
                    AttributeExtractingClassVisitor(schemaClass, found),
                    ClassReader.SKIP_FRAMES or ClassReader.SKIP_DEBUG,
                )
                if (found.isNotEmpty()) result.declaredAttributes[reader.className] = found
            }
            if (schemaClass) {
                reader.accept(
                    DocValuesDefaultClassVisitor { result.docValuesDefaults[reader.className] = it },
                    ClassReader.SKIP_FRAMES or ClassReader.SKIP_DEBUG,
                )
            }
        }
    }

    private fun visitService(open: JarFile, entry: JarEntry, result: ScanResult) {
        val kind = analysisServices[entry.name.removePrefix("META-INF/services/")] ?: return
        val implementations = open.getInputStream(entry).bufferedReader()
            .readLines()
            .map { it.substringBefore('#').trim() }
            .filter { it.isNotEmpty() }
        result.factories.getOrPut(kind) { mutableSetOf() } += implementations
    }
}

/**
 * Visits one factory or field type class, collecting the attribute names it reads out of its own
 * bytecode into [found]. Reflection cannot see these: the names are string literals in the
 * constructor body, so they are neither fields nor annotations. This is the whole reason the
 * catalog is built from bytecode rather than reflected.
 */
private class AttributeExtractingClassVisitor(
    private val schemaClass: Boolean,
    private val found: MutableMap<String, AttributeFacts>,
) : ClassVisitor(Opcodes.ASM9) {
    override fun visitMethod(
        access: Int,
        methodName: String,
        descriptor: String,
        signature: String?,
        exceptions: Array<out String>?,
    ): MethodVisitor? {
        // A factory reads its arguments in its constructor, and nowhere else.
        //
        // A field type threads the map through whatever helper it likes. `init(IndexSchema, Map)`
        // is the entry point, but `CollationField` reads in `setup(ResourceLoader, Map)` and
        // `EnumFieldType` in a nested class's constructor, so naming the methods would be the same
        // losing game as naming the readers (below). Every method is visited instead.
        //
        // The direction of the error is why that is safe. A spurious attribute makes the plugin
        // *more* permissive -- it silences a warning it would otherwise raise -- while a missing
        // one turns a correct `language="en"` into an underline.
        val reads = methodName == "<init>" || schemaClass
        if (!reads) return null
        return AttributeReadingMethodVisitor(schemaClass, found)
    }
}

/**
 * Visits one method that may read arguments out of the `Map<String, String>` Solr passes a
 * factory or field type, recognizing a read by the shape of the call rather than by a list of
 * method names. An earlier revision listed seventeen method names instead, which had to be edited
 * whenever Solr added a reader -- and a reader that went unlisted dropped real attributes
 * silently. Under the unknown-attribute inspection this catalog feeds, a dropped attribute becomes
 * a warning on a *correct* file, so closing that path matters more than the tidiness.
 *
 * Three names from that old list are deliberately not matched here, and their absence is the point
 * rather than an oversight: `getLines`, `getWordSet` and `getSnowballWordSet` take a
 * `ResourceLoader`, not the argument map. They consume a filename that a real reader already
 * resolved -- `getWordSet(loader, get(args, "words"), ignoreCase)` -- so matching them harvested
 * whatever literal happened to be pending. The generated catalog is unchanged by their removal,
 * which is the assertion `SolrClassCatalogTest` makes.
 */
private class AttributeReadingMethodVisitor(
    private val schemaClass: Boolean,
    private val found: MutableMap<String, AttributeFacts>,
) : MethodVisitor(Opcodes.ASM9) {

    // Strings pushed since the last read, in order. The *first* is the attribute name: a reader
    // may take a default too, as in `get(args, "language", "English")`, and taking the most recent
    // literal picked up the default instead of the name.
    private val pending = mutableListOf<String>()

    // The value on top of the operand stack, tracked only closely enough to answer one question at
    // a read: was the default argument a compile-time literal, and if so what was its text? A
    // literal default sits in the argument slot as an `ldc` or an `iconst`/`bipush`; anything
    // computed -- a `getstatic` enum, a `toString()`, a local variable -- leaves a non-literal on
    // top, and a default the pass cannot read as a literal is recorded as absent rather than
    // guessed. `JapaneseTokenizerFactory`'s `mode`, whose default is `DEFAULT_MODE.toString()`, is
    // the case this declines.
    private var top = Pushed.COMPUTED

    override fun visitLdcInsn(value: Any?) {
        top = when (value) {
            is String -> {
                pending += value
                Pushed.literal(value)
            }
            is Int, is Long, is Float, is Double -> Pushed.literal(value.toString())
            else -> Pushed.COMPUTED
        }
    }

    override fun visitInsn(opcode: Int) {
        top = when (opcode) {
            Opcodes.ICONST_M1 -> Pushed.literal("-1")
            Opcodes.ICONST_0, Opcodes.LCONST_0 -> Pushed.literal("0")
            Opcodes.ICONST_1, Opcodes.LCONST_1 -> Pushed.literal("1")
            Opcodes.ICONST_2 -> Pushed.literal("2")
            Opcodes.ICONST_3 -> Pushed.literal("3")
            Opcodes.ICONST_4 -> Pushed.literal("4")
            Opcodes.ICONST_5 -> Pushed.literal("5")
            Opcodes.FCONST_0, Opcodes.DCONST_0 -> Pushed.literal("0.0")
            Opcodes.FCONST_1, Opcodes.DCONST_1 -> Pushed.literal("1.0")
            Opcodes.FCONST_2 -> Pushed.literal("2.0")
            else -> Pushed.COMPUTED
        }
    }

    override fun visitIntInsn(opcode: Int, operand: Int) {
        top = if (opcode == Opcodes.BIPUSH || opcode == Opcodes.SIPUSH) {
            Pushed.literal(operand.toString())
        } else {
            Pushed.COMPUTED
        }
    }

    // A variable load, a field read, a `new`, and a jump each leave something on top that is not a
    // compile-time literal. Marking the top computed after any of them is the safe direction: a
    // default the pass is unsure about is a default it declines.
    override fun visitVarInsn(opcode: Int, variable: Int) {
        top = Pushed.COMPUTED
    }

    override fun visitFieldInsn(opcode: Int, owner: String, name: String, descriptor: String) {
        top = Pushed.COMPUTED
    }

    override fun visitTypeInsn(opcode: Int, type: String) {
        top = Pushed.COMPUTED
    }

    override fun visitJumpInsn(opcode: Int, label: Label) {
        top = Pushed.COMPUTED
    }

    override fun visitMethodInsn(
        opcode: Int,
        owner: String,
        called: String,
        methodDescriptor: String,
        isInterface: Boolean,
    ) {
        val typedRead = isTypedRead(owner, methodDescriptor)
        val erasedRead = isErasedRead(owner, called)
        if (typedRead || erasedRead) {
            val valueType = if (typedRead) GenerateSolrCatalogTask.valueTypeOf(methodDescriptor) else "free"
            // `require*` marks the attribute required and takes no default; every other typed
            // reader with a third parameter carries one. An erased read -- `args.remove` or a
            // field type's `args.get` -- has neither.
            val required = typedRead && called.startsWith("require")
            val default = if (typedRead && !required && GenerateSolrCatalogTask.hasDefaultParameter(methodDescriptor)) {
                literalDefault(valueType)
            } else {
                null
            }
            recordAttribute(methodDescriptor, AttributeFacts(valueType, default, required))
            // Cleared only after a read, not after every call. An earlier version cleared on any
            // call, which lost the name whenever the default was computed by one --
            // `get(args, MODE, DEFAULT_MODE.toString())` dropped `mode` from the Japanese
            // tokenizer entirely.
            pending.clear()
        }
        // A read pushes its result and any other call pushes whatever it returns; either way the
        // top is no longer a literal by the time the next argument list starts.
        top = Pushed.COMPUTED
    }

    // The default argument is the last operand pushed before the read, so it is whatever is on top
    // now. A `bool` default arrives as the integer `0` or `1` -- `getBoolean` pushes `iconst` --
    // and reads back as `false`/`true`. A default carrying one of the fourth column's own
    // delimiters is declined rather than allowed to corrupt the row.
    private fun literalDefault(valueType: String): String? {
        val value = top.text ?: return null
        val text = if (valueType == "bool") {
            when (value) {
                "0" -> "false"
                "1" -> "true"
                else -> value
            }
        } else {
            value
        }
        return text.takeIf { candidate -> candidate.none { it in attributeDelimiters } }
    }

    // The owner guard keeps `Map.get` and `List.get` out: only a call on the factory hierarchy
    // reads an argument.
    private fun isFactoryOwner(owner: String) =
        owner.endsWith("Factory") || owner == "org/apache/lucene/analysis/AbstractAnalysisFactory"

    // Rule A: the call takes the argument map and then a name. The descriptor says so exactly, and
    // its return type says what the value is. Every reader on `AbstractAnalysisFactory` that takes
    // an attribute name has this signature, so the descriptor identifies one exactly.
    private fun isTypedRead(owner: String, methodDescriptor: String) =
        isFactoryOwner(owner) && methodDescriptor.startsWith(argumentMapReader)

    // Rule B: `args.remove("userDictionary")` is a real read -- a factory consuming an argument
    // this way still accepts it -- but `Map.remove` is erased to
    // `(Ljava/lang/Object;)Ljava/lang/Object;`, so it can never carry a type.
    //
    // `Map.get` joins it for field types only, and that is the whole mechanism by which they are
    // read: a field type calls `args.get("defaultCurrency")` on the plain map, never a typed
    // reader. Admitting it for factories too would undo the owner guard above, which exists to
    // keep every unrelated `Map.get` and `List.get` out.
    private fun isErasedRead(owner: String, called: String): Boolean {
        val erasedReaders = if (schemaClass) mapReaders else setOf("remove")
        return called in erasedReaders && owner == "java/util/Map"
    }

    private fun recordAttribute(methodDescriptor: String, facts: AttributeFacts) {
        // Only the literals this call could have consumed. Taking the head of everything pushed
        // since the last read let a stray literal -- an exception message, a builder append -- sit
        // in front of the real name and be read as the attribute.
        val consumed = pending.takeLast(GenerateSolrCatalogTask.stringLikeParameters(methodDescriptor))
        // Every Solr and Lucene attribute name is lowerCamelCase. The guard catches the defaults
        // that still reach here by paths the rules above do not cover.
        consumed.firstOrNull()
            ?.takeIf { it.isNotEmpty() && it[0].isLowerCase() }
            // `class` and `name` are how a component names its factory, not parameters the
            // factory accepts. The base class strips them with `args.remove`, which reads
            // identically to a real one.
            ?.takeIf { it != "class" && it != "name" }
            ?.let { found[it] = mergeFacts(found[it], facts) }
    }
}

/** The top of the operand stack, reduced to what a default read needs: its literal text, or none. */
private class Pushed private constructor(val text: String?) {
    companion object {
        /** Something the pass cannot read as a compile-time literal -- a call result, a field, a var. */
        val COMPUTED = Pushed(null)

        /** A compile-time literal with the given [text]. */
        fun literal(text: String) = Pushed(text)
    }
}

// The shape a typed reader's descriptor starts with -- see [AttributeReadingMethodVisitor.isTypedRead].
private const val argumentMapReader = "(Ljava/util/Map;Ljava/lang/String;"

// How a field type consumes an argument on the plain map. Three JDK method names is a small
// hardcoded set, and acceptable where the seventeen Solr reader names were not:
// `java.util.Map` has been stable for decades, where Solr's factory API is precisely the thing
// that moves.
private val mapReaders = setOf("get", "remove", "containsKey")

/**
 * The class hierarchy and declared attributes one [ArtifactScanner] pass discovered, queried by
 * ancestry: whether a class descends from another, and the full set of attributes it accepts once
 * every ancestor's own declarations are merged in.
 */
private class ClassHierarchy(
    private val superclasses: Map<String, String?>,
    private val interfaces: Map<String, List<String>> = emptyMap(),
    private val declaredAttributes: Map<String, Map<String, AttributeFacts>>,
    private val docValuesDefaults: Map<String, Boolean> = emptyMap(),
) {

    /**
     * The traits of a field type that decide what two of its properties default to.
     *
     * Hierarchy questions only. Whether `omitNorms` is on for a primitive type additionally depends
     * on the schema version, and that arithmetic stays in the model — the generator's half is which
     * classes carry which trait, which is the part that enumerates and changes with Solr.
     */
    fun traitsOf(binaryName: String): List<String> {
        val internal = binaryName.replace('.', '/')
        val traits = mutableListOf<String>()
        // `PrimitiveFieldType.init` sets OMIT_NORMS above schema version 1.4.
        if (isOrDescendsFrom(internal, PRIMITIVE_FIELD_TYPE)) traits += "primitive"
        // `AbstractSpatialPrefixTreeFieldType.init` sets it unconditionally, so a spatial type is
        // omitNorms-by-default at every schema version and is not reachable from the check above.
        if (isOrDescendsFrom(internal, SPATIAL_PREFIX_TREE_FIELD_TYPE)) traits += "spatialPrefixTree"
        // `SortableTextField.init` sets DOC_VALUES outside the version gate in `setArgs`, so it has
        // doc values by default even on a schema too old for the general rule.
        if (isOrDescendsFrom(internal, SORTABLE_TEXT_FIELD)) traits += "sortableText"
        if (resolvedDocValuesDefault(internal)) traits += "docValuesByDefault"
        return traits
    }

    private fun isOrDescendsFrom(internalName: String, ancestor: String): Boolean =
        internalName == ancestor || descendsFrom(internalName, ancestor)

    /**
     * What `enableDocValuesByDefault` returns for this class, taking the nearest declaration.
     *
     * `DenseVectorField` is why this walks rather than asking whether any ancestor returns true: it
     * descends from `PrimitiveFieldType`, which returns true, and overrides that back to false.
     */
    private fun resolvedDocValuesDefault(internalName: String): Boolean {
        var current: String? = internalName
        var depth = 0
        while (current != null && depth++ < 40) {
            docValuesDefaults[current]?.let { return it }
            current = superclasses[current]
        }
        return false
    }

    fun descendsFrom(internalName: String, ancestor: String): Boolean {
        // Breadth-first over both edges, because an implementation may reach its root either way and
        // five of `solrconfig.xml`'s elements are rooted at an interface. The visited set is what keeps
        // a diamond -- routine once interfaces are followed -- from being walked repeatedly, and the
        // bound survives as a guard against a malformed hierarchy rather than as a depth limit.
        val seen = mutableSetOf(internalName)
        val queue = ArrayDeque<String>()
        superclasses[internalName]?.let { queue += it }
        interfaces[internalName]?.let { queue += it }
        var steps = 0
        while (queue.isNotEmpty() && steps++ < 4_000) {
            val current = queue.removeFirst()
            if (current == ancestor) return true
            if (!seen.add(current)) continue
            superclasses[current]?.let { queue += it }
            interfaces[current]?.let { queue += it }
        }
        return false
    }

    // A factory inherits whatever its superclasses read. `EdgeNGramFilterFactory` declares its own
    // three; every analysis factory also accepts `luceneMatchVersion` from the root of the
    // hierarchy. Reporting only the leaf's own literals would omit real attributes.
    fun attributesOf(binaryName: String): List<String> {
        val internal = binaryName.replace('.', '/')
        val collected = sortedMapOf<String, AttributeFacts>()

        var current: String? = internal
        var depth = 0
        while (current != null && depth++ < 40) {
            mergeDeclared(current, collected)
            current = superclasses[current]
        }
        return collected.map { (name, facts) -> encodeAttribute(name, facts) }
    }

    // A class may hand the argument map to a nested helper and let *it* do the reading, in which
    // case the literals are recorded against the helper and the enclosing class looks like it
    // accepts nothing. `EnumFieldType` is the case that forced this: `enumsConfig` and `enumName`
    // are read in `EnumFieldType$EnumMapping`, so before this the catalog said `EnumFieldType`
    // took no attributes at all -- and under the unknown-attribute rule that turns a correct
    // `enumsConfig="..."` into a warning.
    private fun mergeDeclared(owner: String, collected: MutableMap<String, AttributeFacts>) {
        declaredAttributes[owner]?.forEach { (name, facts) ->
            collected[name] = mergeFacts(collected[name], facts)
        }
        val nested = "$owner$"
        for ((className, attributes) in declaredAttributes) {
            if (!className.startsWith(nested)) continue
            attributes.forEach { (name, facts) ->
                collected[name] = mergeFacts(collected[name], facts)
            }
        }
    }
}

/** Where Solr declares what a `<field>` accepts, in `FieldProperties`. */
private const val fieldPropertiesClass = "org/apache/solr/schema/FieldProperties.class"

/**
 * Reads the attribute names `FieldProperties` accepts on a `<field>`: `propertyNames` is the
 * parser's whole vocabulary, and `isPropertyIgnored` names the attributes it waves through without
 * acting on -- `default`, and the two format selectors that only mean something on the type.
 * Everything else on a `<field>` throws `Invalid field property` at core load.
 *
 * The names are read from the two places `FieldProperties` keeps them: `propertyNames` is filled
 * in the static initialiser as one `ldc`/`aastore` pair per name -- nothing else in that
 * initialiser stores a string into an array -- and `isPropertyIgnored` holds its names as the
 * literals it compares against. Reading the strings out of exactly those two methods is the same
 * bet the catalog makes everywhere: the bytecode cannot drift from what Solr does, where a copied
 * list can.
 */
private object FieldPropertyExtractor {
    fun extract(jars: List<File>): Set<String> {
        val names = sortedSetOf<String>()
        for (jar in jars) {
            JarFile(jar).use { open ->
                val entry = open.getJarEntry(fieldPropertiesClass) ?: return@use
                open.getInputStream(entry).use { input ->
                    ClassReader(input).accept(
                        FieldPropertyClassVisitor(names),
                        ClassReader.SKIP_FRAMES or ClassReader.SKIP_DEBUG,
                    )
                }
            }
        }
        return names
    }
}

/** Visits `<clinit>` (the `propertyNames` array) and `isPropertyIgnored`; nothing else stores a name. */
private class FieldPropertyClassVisitor(private val names: MutableSet<String>) : ClassVisitor(Opcodes.ASM9) {
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
        return FieldPropertyMethodVisitor(comparesIgnored, names)
    }
}

/** The internal names the trait checks walk to. */
private const val PRIMITIVE_FIELD_TYPE = "org/apache/solr/schema/PrimitiveFieldType"
private const val SPATIAL_PREFIX_TREE_FIELD_TYPE = "org/apache/solr/schema/AbstractSpatialPrefixTreeFieldType"
private const val SORTABLE_TEXT_FIELD = "org/apache/solr/schema/SortableTextField"

/**
 * Records what a field type's `enableDocValuesByDefault` returns, where it returns a constant.
 *
 * Only a body that is exactly "push a boolean, return it" is recorded. Solr's six overrides are all
 * that shape, and declining anything else keeps a computed answer from being read as a literal one —
 * the same rule the attribute extractor applies to defaults.
 */
private class DocValuesDefaultClassVisitor(
    private val record: (Boolean) -> Unit,
) : ClassVisitor(Opcodes.ASM9) {

    override fun visitMethod(
        access: Int,
        methodName: String,
        descriptor: String,
        signature: String?,
        exceptions: Array<out String>?,
    ): MethodVisitor? {
        if (methodName != "enableDocValuesByDefault" || descriptor != "()Z") return null
        return ConstantBooleanMethodVisitor(record)
    }
}

private class ConstantBooleanMethodVisitor(private val record: (Boolean) -> Unit) : MethodVisitor(Opcodes.ASM9) {
    private var pushed: Boolean? = null
    private var constantOnly = true

    override fun visitInsn(opcode: Int) {
        when (opcode) {
            Opcodes.ICONST_0 -> pushed = false
            Opcodes.ICONST_1 -> pushed = true
            Opcodes.IRETURN -> if (constantOnly) pushed?.let(record)
            else -> constantOnly = false
        }
    }

    override fun visitVarInsn(opcode: Int, variable: Int) {
        constantOnly = false
    }

    override fun visitFieldInsn(opcode: Int, owner: String, name: String, descriptor: String) {
        constantOnly = false
    }

    override fun visitMethodInsn(
        opcode: Int,
        owner: String,
        name: String,
        descriptor: String,
        isInterface: Boolean,
    ) {
        constantOnly = false
    }

    override fun visitJumpInsn(opcode: Int, label: Label) {
        constantOnly = false
    }

    override fun visitIntInsn(opcode: Int, operand: Int) {
        constantOnly = false
    }

    override fun visitLdcInsn(value: Any?) {
        constantOnly = false
    }
}

private class FieldPropertyMethodVisitor(
    private val comparesIgnored: Boolean,
    private val names: MutableSet<String>,
) : MethodVisitor(Opcodes.ASM9) {
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

/**
 * The elements `solrconfig.xml` accepts, and the superclass each one's `class` attribute must name.
 *
 * **A fourth technique, and the only one that reads a declaration rather than inferring a population.**
 * The analysis kinds come from Lucene's SPI files and the field types from a hardcoded superclass; both
 * answer "what exists". `SolrConfig` instead *declares* what `solrconfig.xml` may contain, as
 * `public static final List<SolrPluginInfo> plugins`, and each entry pairs the element tag with the
 * `Class<?>` its implementations must extend. Reading that list is how the catalog stops hand-writing a
 * table of roots — a table that was found to be both fragile and, more to the point, short.
 *
 * **The pairs are plain constants, which is what makes this tractable.** Solr builds each entry with
 * `new SolrPluginInfo(SomeRoot.class, "someTag", …)`, so the class and the tag reach the constructor as
 * `LDC` operands in that order and nothing else in the initializer loads a constant of either kind:
 * `PluginOpts` values arrive by `getstatic`, and the reader-functions by `invokedynamic`. On both
 * supported Solr lines the initializer holds exactly 23 class constants and 23 strings, strictly
 * alternating.
 */
internal object SolrConfigPlugins {

    /** The class whose static initializer declares the plugin list. */
    const val DECLARING_CLASS = "org/apache/solr/core/SolrConfig"

    /**
     * Pairs each element tag with the internal name of the root its `class` attribute must implement.
     *
     * Pure, and separated from the bytecode reading so it can be tested without a jar: [constants] is
     * the `LDC` operands of the static initializer in order, ASM's `Type` for a class constant and
     * `String` for a tag.
     *
     * A tag arriving without a class before it is dropped rather than guessed. That cannot happen while
     * Solr writes the constructor with the class first, and the alternative — pairing a tag with
     * whatever class came last, however far back — is how this would silently mis-attribute a root if
     * the initializer ever gained an unrelated string.
     *
     * @param constants the initializer's constants, in bytecode order
     * @return tag to root internal name, tags cleaned of the path syntax Solr writes some of them with
     */
    fun pair(constants: List<Any>): Map<String, String> = paths(constants).mapKeys { cleanTag(it.key) }

    /**
     * The element name a reader writes, from the path Solr declares it under.
     *
     * Three of the tags are paths rather than bare names — `indexConfig/deletionPolicy`,
     * `updateHandler/updateLog`, and `//listener` for an element Solr accepts at any depth. The catalog
     * is keyed by the element name as written, so the last segment is the part it wants; the nesting is
     * a separate fact and belongs to whatever describes element structure, not to a table of classes.
     */
    private fun cleanTag(tag: String) = tag.substringAfterLast('/')

    /**
     * The same pairing, keyed by the path Solr writes rather than by the bare element name.
     *
     * [pair] drops the nesting because a table of classes has nowhere to put it. The element
     * vocabulary is where it is the point, so this keeps what that one discards; both read the same
     * constants, so there is no second interpretation of the initializer to keep in step.
     *
     * @param constants the initializer's constants, in bytecode order
     * @return path as Solr declares it, to root internal name
     */
    fun paths(constants: List<Any>): Map<String, String> {
        val roots = linkedMapOf<String, String>()
        var pendingRoot: String? = null
        for (constant in constants) {
            when (constant) {
                is Type -> pendingRoot = constant.internalName
                is String -> {
                    val root = pendingRoot ?: continue
                    roots[constant] = root
                    pendingRoot = null
                }
            }
        }
        return roots
    }
}

/**
 * Collects each element name `SolrConfig` reads from its tree, and every message it carries.
 *
 * **A literal beside a call, which is the technique the factory attributes already use.** The name
 * arrives as an `LDC` and the arity as the method invoked immediately after it, so the pass keeps one
 * pending literal and consumes it at the next call — the same shape as
 * [AttributeReadingMethodVisitor], narrowed to a closed set of readers.
 *
 * The messages are collected wholesale rather than filtered here, because deciding which of them
 * retires an element is a rule about words and belongs beside the other rules in
 * [SolrConfigElements].
 */
private class ConfigElementClassVisitor(
    private val reads: MutableMap<String, String>,
    private val messages: MutableList<String>,
) : ClassVisitor(Opcodes.ASM9) {

    override fun visitMethod(
        access: Int,
        name: String?,
        descriptor: String?,
        signature: String?,
        exceptions: Array<out String>?,
    ): MethodVisitor = ConfigElementMethodVisitor(reads, messages)
}

/** Pairs a pending literal with the reader called next; see [ConfigElementClassVisitor]. */
private class ConfigElementMethodVisitor(
    private val reads: MutableMap<String, String>,
    private val messages: MutableList<String>,
) : MethodVisitor(Opcodes.ASM9) {

    private var pending: String? = null

    override fun visitLdcInsn(value: Any?) {
        // Cleared before the kind is tested, not after. A non-string `LDC` is as much an operation
        // between a name and its reader as any of the instructions below, and returning early left
        // the previous string pending across it.
        if (value !is String) {
            pending = null
            return
        }
        // Every string is a candidate message; only the last one before a call is a candidate name.
        messages += value
        pending = value
    }

    override fun visitMethodInsn(
        opcode: Int,
        owner: String?,
        name: String?,
        descriptor: String?,
        isInterface: Boolean,
    ) {
        val literal = pending
        pending = null
        val arity = name?.let { SolrConfigElements.arityOf(it) } ?: return
        if (literal == null) return
        // A name already read wins on its first reading. Solr reads several elements twice — once to
        // build them and once to report them — and the second call is sometimes the weaker one, so
        // keeping the first is what preserves `childRequired` on `luceneMatchVersion`.
        reads.putIfAbsent(literal, arity)
    }

    // Everything below clears the pending literal, and the omissions are the rule rather than an
    // oversight. A name reaches its reader either immediately — `get("dataDir")` — or with one
    // `invokedynamic` in between, which is the lambda `childRequired` takes as its second argument.
    // Anything else between the two means the string was pushed for something other than this call,
    // and the pass that did not clear here put a discontinuation notice in the vocabulary as an
    // element name, because the notice was the last literal loaded before an unrelated read.

    override fun visitInsn(opcode: Int) {
        pending = null
    }

    override fun visitVarInsn(opcode: Int, varIndex: Int) {
        pending = null
    }

    override fun visitFieldInsn(opcode: Int, owner: String?, name: String?, descriptor: String?) {
        pending = null
    }

    override fun visitTypeInsn(opcode: Int, type: String?) {
        pending = null
    }

    override fun visitIntInsn(opcode: Int, operand: Int) {
        pending = null
    }

    override fun visitJumpInsn(opcode: Int, label: Label?) {
        pending = null
    }

    /**
     * The one instruction that is transparent, and only in the shape that earned it.
     *
     * `childRequired` takes a lambda as its second argument, which reaches the call as an
     * `invokedynamic` between the name and its reader — so clearing unconditionally here would lose
     * every required child. String concatenation arrives the same way and means the opposite: a
     * `makeConcatWithConstants` has *consumed* the literal to build a message, and letting it through
     * is how a message becomes an element name. The return type tells the two apart.
     */
    override fun visitInvokeDynamicInsn(
        name: String?,
        descriptor: String?,
        bootstrapMethodHandle: Handle?,
        vararg bootstrapMethodArguments: Any?,
    ) {
        val returns = descriptor?.substringAfterLast(')').orEmpty()
        if (returns == "Ljava/lang/String;" || returns == "Ljava/lang/CharSequence;") {
            pending = null
        }
    }
}

/** Collects the static initializer's constants, in order, for [SolrConfigPlugins.pair]. */
private class PluginDeclarationClassVisitor(
    private val constants: MutableList<Any>,
) : ClassVisitor(Opcodes.ASM9) {

    override fun visitMethod(
        access: Int,
        name: String,
        descriptor: String,
        signature: String?,
        exceptions: Array<out String>?,
    ): MethodVisitor? = if (name == "<clinit>") ConstantCollectingMethodVisitor(constants) else null
}

/** Records every `LDC` operand it sees, leaving the pairing to [SolrConfigPlugins.pair]. */
private class ConstantCollectingMethodVisitor(
    private val constants: MutableList<Any>,
) : MethodVisitor(Opcodes.ASM9) {

    override fun visitLdcInsn(value: Any?) {
        if (value is Type || value is String) constants += value
    }
}

/**
 * The request parameters a `solrconfig.xml` handler can carry defaults for, read from the interfaces
 * Solr declares them in.
 *
 * **A third technique, and the simplest of the three.** The class routes walk a hierarchy, and the
 * plugin roots read a static initializer; a parameter name is a `public static final String`, so javac
 * stores it in a `ConstantValue` attribute on the field itself. ASM hands it to `visitField` directly —
 * no initializer to walk, and no expression to evaluate, because javac has already folded the composed
 * ones: `MM_AUTORELAX` arrives as `mm.autoRelax` and not as `MM + ".autoRelax"`.
 *
 * **What is hard here is not reading the constants but deciding which of them are parameters.** A params
 * interface is a bag of related strings, and `CommonParams` in particular mixes genuine parameters with
 * request paths, response keys, and the values other parameters accept. Three mechanical rules and one
 * curated list do the separating, in that order of preference — see [isParameterValue] and
 * [EXCLUDED_BY_NAME].
 */
internal object SolrParameters {

    /**
     * The classes whose constants a `solrconfig.xml` reader may write, by internal name.
     *
     * **Internal names rather than a package, because the vocabulary is not confined to one.** Most of
     * it lives in `org.apache.solr.common.params`, but `defType` — the single most asked-about parameter
     * value in the file — is declared in `org.apache.solr.search.QueryParsing` in `solr-core`, along
     * with `q.op` and `sow`. A package-scoped pass produced a resource with 340 parameters and no
     * `defType` in it, which is the generator's standing failure mode: a plausible list, no error.
     */

    /**
     * **An allowlist rather than a whole package, because the package is not all one thing.** Beside
     * the query and handler parameters sit `CollectionParams`, `CollectionAdminParams`,
     * `CoreAdminParams`, `ConfigSetParams` and `CommonAdminParams` — the vocabularies of Solr's
     * *administrative* APIs, which are sent to `/admin/collections` and never appear in a configset.
     * Offering `replicationFactor` inside a `<lst name="defaults">` would be offering a parameter the
     * handler there has never read.
     *
     * The package also holds `SolrParams` and its implementations — `ModifiableSolrParams`,
     * `MapSolrParams`, `DefaultSolrParams` — which are classes that *carry* parameters rather than
     * interfaces that name them, and hold no naming constants at all.
     */
    val DECLARING_CLASSES: Set<String> = setOf(
        // The core query vocabulary: `q`, `fl`, `rows`, `sort`, `df`, `fq`.
        "org/apache/solr/common/params/CommonParams",
        // `qf`, `pf`, `mm`, `ps`, `bq`, `bf` — the dismax and edismax families.
        "org/apache/solr/common/params/DisMaxParams",
        "org/apache/solr/common/params/SimpleParams",
        // The components, each of which reads its own prefixed family out of the same request.
        "org/apache/solr/common/params/FacetParams",
        "org/apache/solr/common/params/HighlightParams",
        "org/apache/solr/common/params/GroupParams",
        "org/apache/solr/common/params/MoreLikeThisParams",
        "org/apache/solr/common/params/SpellingParams",
        "org/apache/solr/common/params/StatsParams",
        "org/apache/solr/common/params/TermsParams",
        "org/apache/solr/common/params/TermVectorParams",
        "org/apache/solr/common/params/SpatialParams",
        "org/apache/solr/common/params/ExpandParams",
        "org/apache/solr/common/params/QueryElevationParams",
        "org/apache/solr/common/params/CursorMarkParams",
        "org/apache/solr/common/params/ShardParams",
        "org/apache/solr/common/params/AnalysisParams",
        // `/update`'s parameters, which an update handler carries defaults for in the same shape.
        "org/apache/solr/common/params/UpdateParams",
        // `newSearcher` and `firstSearcher`, which are what a `<listener event=…>` names.
        "org/apache/solr/common/params/EventParams",
        // Not a params interface at all, and the reason this list is keyed by internal name:
        // `QueryParsing` is where `defType`, `q.op` and `sow` are declared.
        "org/apache/solr/search/QueryParsing",
    )

    /**
     * Constants in `CommonParams` that are not parameter names, each excluded for a stated reason.
     *
     * **This list is curation, and curation goes stale** — a Solr line adding another response key to
     * this interface will leak it, and nothing will fail. It is bounded to the one interface where the
     * mixing happens, and every entry here is a *value* some other parameter accepts rather than a
     * name anything reads.
     */
    val EXCLUDED_BY_NAME: Set<String> = setOf(
        // The four values `debug` accepts. `debug=timing` is real; a `timing` parameter is not.
        "QUERY", "TIMING", "RESULTS", "TRACK",
        // The ping handler's `action` values, and `action` itself, which the admin APIs read.
        "ACTION", "PING", "ENABLE", "DISABLE", "STATUS",
    )

    /**
     * Whether [value] is spelled like a parameter name at all.
     *
     * Three rules, all mechanical, which is why they are preferred to naming constants one by one:
     *
     * - **A value containing `/` is a request path**, not a parameter: `PING_HANDLER` is
     *   `/admin/ping` and `APISPEC_LOCATION` is `apispec/`.
     * - **A value with no lowercase letter is a value or a response key**, not a name. Solr's
     *   parameters are lowercase, camel case or dotted; `OK`, `FAILURE`, `RESPONSE_TIME` and
     *   `SimpleParams`' `AND`/`OR`/`NOT` operators are none of those. **This loses `NOW` and `TZ`,
     *   which genuinely are query parameters** — recorded rather than special-cased, because the
     *   catalog is a completion and documentation source and not a membership test, so a name missing
     *   from it costs a suggestion while a response key present in it is visible noise.
     * - **A value ending in `.` is a prefix, not a name.** Six interfaces declare one — `tv.`, `mlt.`,
     *   `terms.`, `spellcheck.`, `spellcheck.collateParam.`, `group.topgroups.` — as the stem their own
     *   parameters are built from, and each appeared in the first generated resource as a parameter a
     *   reader could select and would then have to finish by hand.
     * - **A blank value is nothing at all.**
     *
     * The names that survive but should not are the cost of preferring rules to curation, and the
     * bound on it is that this list is a completion and documentation source rather than a membership
     * test — nothing is ever reported wrong for being absent from it.
     */
    fun isParameterValue(value: String): Boolean =
        value.isNotBlank() &&
            '/' !in value &&
            !value.endsWith('.') &&
            value.any { it.isLowerCase() }

    /**
     * The parameters [constants] declares, as name to owning interface.
     *
     * Pure, and separated from the bytecode reading so the selection rules can be tested against input
     * no jar would produce.
     *
     * @param simpleName the declaring interface's simple name, which decides whether the curated
     *   exclusions apply
     * @param constants the interface's `String` constants, as field name to value
     * @return the surviving field-name-to-parameter-name pairs, in the order given. The field name is
     *   carried because a parameter's documentation is the Javadoc comment on the constant, which is
     *   found by field name and not by the value it holds
     */
    fun parametersOf(simpleName: String, constants: List<Pair<String, String>>): List<Pair<String, String>> =
        constants
            .filterNot { (fieldName, _) -> simpleName == "CommonParams" && fieldName in EXCLUDED_BY_NAME }
            .filter { (_, value) -> isParameterValue(value) }
            .distinctBy { (_, value) -> value }
}

/**
 * The registered names `defType` accepts, read from the map `QParserPlugin` builds of them.
 *
 * **Not the shape [SolrConfigPlugins] reads, though it looks like it should be.** `SolrConfig.plugins`
 * names each root as a *class constant*, so an `LDC` visitor sees the whole declaration.
 * `QParserPlugin` instead *instantiates* each plugin into a map: the registered name arrives by `LDC`
 * and the class by **`NEW`**, which is a type instruction and not a constant load — so a collector
 * overriding only `visitLdcInsn` would see 45 strings, no classes, and pair nothing. The operands also
 * arrive name-first rather than class-first.
 *
 * This is what `defType` accepts, and what a `<queryParser name=…>` overrides: `edismax`, not
 * `solr.ExtendedDismaxQParserPlugin`. The class is carried alongside so each name can be documented
 * from the Javadoc of the plugin that implements it.
 */
internal object SolrQueryParsers {

    /** The class whose static initializer builds the registry. */
    const val DECLARING_CLASS = "org/apache/solr/search/QParserPlugin"

    /**
     * Pairs each registered name with the binary name of the plugin registered under it.
     *
     * Pure, and tested without a jar. [operands] is the initializer's names and instantiated types in
     * bytecode order: a `String` for each `LDC` and a [Type] for each `NEW`.
     *
     * A name arriving without a class after it is dropped rather than guessed, for the same reason the
     * plugin-root pairing drops an unpaired tag: the alternative is silently attributing a name to
     * whatever class happened to be instantiated nearby.
     *
     * @param operands the initializer's string constants and instantiated types, in order
     * @return registered name to plugin binary name, in declaration order
     */
    fun pair(operands: List<Any>): Map<String, String> {
        val parsers = linkedMapOf<String, String>()
        var pendingName: String? = null
        for (operand in operands) {
            when (operand) {
                is String -> pendingName = operand
                is Type -> {
                    val name = pendingName ?: continue
                    parsers[name] = operand.internalName.replace('/', '.')
                    pendingName = null
                }
            }
        }
        return parsers
    }
}

/**
 * Collects a params interface's `String` constants, as field name to value.
 *
 * Reads the `ConstantValue` attribute ASM passes to [visitField], so nothing here walks an initializer.
 * A field whose value is absent — a non-constant, or a `Set` such as `CommonParams.ADMIN_PATHS` — and
 * one whose value is not a `String` — `START_DEFAULT` is an `int` — are both simply not collected.
 */
private class ParameterConstantVisitor(
    private val constants: MutableList<Pair<String, String>>,
) : ClassVisitor(Opcodes.ASM9) {

    override fun visitField(
        access: Int,
        name: String,
        descriptor: String,
        signature: String?,
        value: Any?,
    ): org.objectweb.asm.FieldVisitor? {
        if (value is String) constants += name to value
        return null
    }
}

/**
 * Collects a static initializer's string constants *and* the types it instantiates, in order, for
 * [SolrQueryParsers.pair].
 *
 * The `NEW` override is the whole difference from [ConstantCollectingMethodVisitor]: a plugin the
 * initializer constructs rather than names does not appear as a constant at all.
 */
private class InstantiationCollectingMethodVisitor(
    private val operands: MutableList<Any>,
) : MethodVisitor(Opcodes.ASM9) {

    override fun visitLdcInsn(value: Any?) {
        if (value is String) operands += value
    }

    override fun visitTypeInsn(opcode: Int, type: String) {
        if (opcode == Opcodes.NEW) operands += Type.getObjectType(type)
    }
}

/** Reads [InstantiationCollectingMethodVisitor] over a class's static initializer. */
private class InstantiationClassVisitor(
    private val operands: MutableList<Any>,
) : ClassVisitor(Opcodes.ASM9) {

    override fun visitMethod(
        access: Int,
        name: String,
        descriptor: String,
        signature: String?,
        exceptions: Array<out String>?,
    ): MethodVisitor? = if (name == "<clinit>") InstantiationCollectingMethodVisitor(operands) else null
}

/**
 * Where a parameter name came from: the params interface declaring it, and the constant field holding
 * it. The field name is what a Javadoc lookup needs, since a parameter's documentation is the comment
 * on the constant rather than on anything named after the parameter itself.
 */
internal data class ParameterOrigin(val owner: String, val fieldName: String)

/**
 * The one-sentence summary of each named *constant's* Javadoc, read from a Solr line's `-sources` jars.
 *
 * **The same extractor as [JavadocSummaries], pointed one level down.** A class's documentation is the
 * comment on the class; a parameter's is the comment on the `public static final String` holding it, so
 * only the declaration pattern differs. The summarizing, the inline-tag rendering and the
 * decline-rather-than-guess rule are shared, which is what keeps a parameter's popup reading like a
 * class's.
 *
 * Returns thin summaries and that is the ceiling, not a defect: `qf` reads *query and init param for
 * query fields* and `df` reads *default query field*, which is exactly what a reader who does not know
 * the parameter needs, and nothing more is written down in the artifact.
 */
internal object ConstantJavadocSummaries {

    /**
     * Summaries for the constants of one interface, by field name.
     *
     * @param className the declaring interface's binary name
     * @param fieldNames the constants to look up
     * @param jars the line's `-sources` jars
     * @return field name to summary, omitting any constant with no comment and any interface with no
     *   published sources
     */
    fun extract(className: String, fieldNames: Collection<String>, jars: List<File>): Map<String, String> {
        val source = readSource(className, jars) ?: return emptyMap()
        val found = mutableMapOf<String, String>()
        for (fieldName in fieldNames) {
            val comment = constantJavadocComment(source, fieldName) ?: continue
            val summary = GenerateSolrCatalogTask.summarizeJavadocComment(comment) ?: continue
            found[fieldName] = summary
        }
        return found
    }

    private fun readSource(className: String, jars: List<File>): String? {
        for (jar in jars) {
            JarFile(jar).use { open ->
                val entry = open.getJarEntry("${className.replace('.', '/')}.java")
                if (entry != null) return open.getInputStream(entry).bufferedReader().readText()
            }
        }
        return null
    }

    /**
     * The Javadoc comment immediately preceding the constant [fieldName]'s declaration.
     *
     * The modifier list is optional throughout, because a constant in an interface may be written with
     * every modifier, with some, or with none: `DisMaxParams` writes `public static String QF` while
     * `CommonParams` writes a bare `String Q`. A pattern that required `static final` would have found
     * the documentation for one interface and silently none for the other.
     *
     * The comment body refuses to cross a comment terminator, for the reason
     * [GenerateSolrCatalogTask.classJavadocComment] gives at length: a lazy `.` repetition under
     * `DOT_MATCHES_ALL` backtracks straight through one, which would hand an earlier constant's comment
     * to a later undocumented one.
     */
    internal fun constantJavadocComment(source: String, fieldName: String): String? {
        val declaration = Regex(
            "/\\*\\*((?:(?!\\*/).)*?)\\*/\\s*(?:@\\w+(?:\\([^)]*\\))?\\s*)*" +
                "(?:public\\s+|static\\s+|final\\s+)*String\\s+${Regex.escape(fieldName)}\\s*=",
            RegexOption.DOT_MATCHES_ALL,
        )
        return declaration.find(source)?.groupValues?.get(1)
    }
}
