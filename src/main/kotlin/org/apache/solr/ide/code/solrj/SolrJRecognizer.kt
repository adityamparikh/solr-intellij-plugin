package org.apache.solr.ide.code.solrj

import com.intellij.psi.PsiFile
import com.intellij.psi.util.PsiTreeUtil
import org.apache.solr.ide.code.SolrEndpointUsage
import org.apache.solr.ide.code.SolrFieldUsage
import org.apache.solr.ide.code.SolrUsageRecognizer
import org.apache.solr.ide.model.query.SolrQueryExpressions
import org.apache.solr.ide.model.query.SolrQueryFields
import org.jetbrains.uast.UAnnotation
import org.jetbrains.uast.UCallExpression
import org.jetbrains.uast.UDeclaration
import org.jetbrains.uast.UExpression
import org.jetbrains.uast.UFile
import org.jetbrains.uast.ULiteralExpression
import org.jetbrains.uast.UMethod
import org.jetbrains.uast.UVariable
import org.jetbrains.uast.UElement
import org.jetbrains.uast.UPolyadicExpression
import org.jetbrains.uast.UQualifiedReferenceExpression
import org.jetbrains.uast.UastCallKind
import org.jetbrains.uast.UastFacade
import org.jetbrains.uast.toUElementOfType
import org.jetbrains.uast.visitor.AbstractUastVisitor

/**
 * Reads field references out of SolrJ calls in Java and Kotlin.
 *
 * **Through UAST, so one implementation serves both languages.** PSI is language-specific — a
 * recognizer written against `PsiMethodCallExpression` sees nothing of Kotlin — while UAST's
 * `UCallExpression` is implemented by each JVM language. The plan settles this under Step 16 and
 * records why Groovy is not included: its UAST provider converts annotations and literals but not
 * calls, so half of this would be silently dark there.
 *
 * **Silence wherever the answer is not certain.** A call is read only when its receiver resolves to
 * SolrJ's own `SolrQuery`, its method is one that names fields, and its argument spells the name out
 * in the source. Anything else — a variable, an interpolated string, a project's own class with a
 * method of the same name, a method taking a count — produces nothing. That is precision over
 * recall, which is the correct trade for a feature whose failure mode is a warning on somebody's
 * working code.
 *
 * **The two languages are held level by a mirrored test suite, because the compiler will not do it.**
 * `SolrJRecognizerKotlinTest` repeats every case in the Java suite, silences included. Until it
 * existed the Kotlin half was entirely dark and the build was green: a Kotlin string is never a
 * literal but always a template, so the cast this once performed failed on every one of them.
 */
object SolrJRecognizer : SolrUsageRecognizer {

    /**
     * SolrJ itself, which every other recognized Solr library carries transitively.
     *
     * Narrower than the detector's own list on purpose: this recognizer reads `SolrQuery` and the
     * client builders, which are SolrJ's own types. A module carrying Camel's Solr component gets
     * this recognizer too, because `camel-solr` cannot resolve without SolrJ beneath it.
     */
    override val libraryCoordinates: List<String> = listOf("solr-solrj")

    /**
     * The field references [file] makes through SolrJ, in the order they are written.
     *
     * @param file a Java or Kotlin file
     * @return the field usages it contains, empty where it references none
     */
    override fun readFieldUsages(file: PsiFile): List<SolrFieldUsage> {
        // Both call readers share the one traversal `readThrough` exists to hold; annotations are
        // not calls and are collected after it, from the same file.
        val found = readThrough(file) { node, into ->
            readCall(node, into)
            readDocumentCall(node, into)
        }.toMutableList()
        readBeanAnnotations(file, found)
        return found
    }

    /**
     * Reads every `@Field` in [file], reached through PSI rather than through the UAST tree.
     *
     * **A Kotlin property's annotation is not in that tree, and this is the one place UAST does not
     * hide the difference.** In Java the annotation is a node the visitor reaches; in Kotlin it is
     * attached to the property, which has no Java counterpart, so neither `visitAnnotation` nor the
     * light class's `uAnnotations` ever offers it — the only annotation surfacing there is the
     * `@Nullable` the compiler synthesises. It converts perfectly well *on demand*, which is what
     * this does.
     *
     * **Which PSI classes to convert is asked of the platform rather than guessed.**
     * `getPossiblePsiSourceTypes` is how each language declares what converts to a `UAnnotation`, so
     * every form that language spells an annotation in is covered — including Kotlin's bracketed
     * `@[Foo Bar]` list, whose entries an earlier version of this missed entirely because it looked
     * for the `@` that opens an annotation and that form has one `@` for two annotations. Naming no
     * language's own PSI classes is the property being preserved; the language plugin names them.
     */
    private fun readBeanAnnotations(file: PsiFile, into: MutableList<SolrFieldUsage>) {
        val sourceTypes = UastFacade.getPossiblePsiSourceTypes(UAnnotation::class.java)
        val candidates = PsiTreeUtil.collectElements(file) { sourceTypes.contains(it.javaClass) }
        for (candidate in candidates) {
            val annotation = candidate.toUElementOfType<UAnnotation>() ?: continue
            readBeanAnnotation(annotation, into)
        }
    }

    /**
     * Reads a `@Field` binding, or declines to.
     *
     * **Matched by qualified name, which is not fussiness.** `Field` is among the most reused
     * annotation names on the JVM — JPA, Lucene and several serialization libraries each ship one —
     * so a simple-name match would report a field reference for every annotated property in a great
     * many projects that have never used Solr.
     *
     * **A bare `@Field` names the property it sits on**, because that is what SolrJ does with it:
     * its default value is the sentinel `#default`, and `DocumentObjectBinder` reads the member's
     * own name in its place. Declining to read that would be the more cautious choice and the wrong
     * one — the name still reaches Solr, so passing over it is a miss that surfaces as silence.
     *
     * A setter contributes the name it sets rather than its own, for the same reason: `setPrice` is
     * how SolrJ spells a binding to `price`.
     */
    private fun readBeanAnnotation(annotation: UAnnotation, into: MutableList<SolrFieldUsage>) {
        if (annotation.qualifiedName != SolrJQueryMethods.BEAN_FIELD_ANNOTATION) return

        val written = annotation.findAttributeValue(VALUE_ATTRIBUTE)
        val spelled = written?.let { constantTextOf(it) }
        if (spelled != null && spelled != NAMES_ITS_MEMBER) {
            val anchor = written.sourcePsi ?: return
            if (into.none { it.element == anchor }) {
                into += SolrFieldUsage(spelled, BOUND_AT_INDEX_TIME, anchor)
            }
            return
        }
        // A value that is present and not spelled out stops here, as everywhere else in this
        // recognizer: following a constant back to its assignment is what it declines to do.
        if (written != null && spelled == null) return

        // Absent, or SolrJ's `#default`: the member's own name, anchored to the member so a finding
        // underlines the name that is wrong rather than the binding that is fine.
        val member = memberHolding(annotation) ?: return
        val name = memberNameOf(member) ?: return
        val anchor = (member as? UDeclaration)?.uastAnchor?.sourcePsi ?: annotation.sourcePsi ?: return
        if (into.none { it.element == anchor }) {
            into += SolrFieldUsage(name, BOUND_AT_INDEX_TIME, anchor)
        }
    }

    /** The declaration an annotation sits on, whichever language wrote it. */
    private fun memberHolding(annotation: UAnnotation): UDeclaration? =
        generateSequence(annotation.sourcePsi?.parent) { it.parent }
            .mapNotNull { it.toUElementOfType<UDeclaration>() }
            .firstOrNull()

    /** The Solr field a bare `@Field` on [member] binds, or null where the member names none. */
    private fun memberNameOf(member: UElement): String? = when (member) {
        is UVariable -> member.name
        is UMethod -> member.name.removePrefix("set").removePrefix("get")
            .takeIf { it.isNotEmpty() && it != member.name }
            ?.replaceFirstChar { it.lowercaseChar() }
        else -> null
    }

    /**
     * The Solr servers [file] constructs a client against, in the order they are written.
     *
     * @param file a Java or Kotlin file
     * @return the endpoints it names, empty where it constructs no client from a spelled-out URL
     */
    override fun readEndpoints(file: PsiFile): List<SolrEndpointUsage> =
        readThrough(file) { node, into -> readClientConstruction(node, into) }

    /**
     * Walks [file]'s calls once, handing each to [read].
     *
     * Both halves of this recognizer are a visit over every call expression differing only in what
     * they do with one, so the traversal is written once. UAST is a view built over PSI on demand,
     * which makes building it the expensive part rather than the visiting.
     */
    private fun <T> readThrough(file: PsiFile, read: (UCallExpression, MutableList<T>) -> Unit): List<T> {
        val uFile = file.toUElementOfType<UFile>() ?: return emptyList()
        val found = mutableListOf<T>()
        uFile.accept(
            object : AbstractUastVisitor() {
                override fun visitCallExpression(node: UCallExpression): Boolean {
                    read(node, found)
                    return false
                }
            },
        )
        return found
    }

    /**
     * Reads one call, or declines to.
     *
     * Three conditions, each of which alone would let a wrong finding through: the method must be
     * one that names fields, the receiver must resolve to SolrJ's own class rather than something
     * else carrying a method of that name, and the argument must be a literal. A variable holding
     * the query is where following values through the program would begin, and this deliberately
     * does not — the specification calls that best-effort by nature and chooses silence.
     */
    private fun readCall(call: UCallExpression, into: MutableList<SolrFieldUsage>) {
        // Which calls name fields is decided in one place, because completion asks the same question
        // and two spellings of it would let the two surfaces disagree about where a name belongs.
        val method = SolrJFieldPositions.fieldNamingMethod(call) ?: return

        val arguments = if (method.readsOnlyFirstArgument) call.valueArguments.take(1) else call.valueArguments
        for (argument in arguments) {
            val text = constantTextOf(argument) ?: continue
            val anchor = argument.sourcePsi ?: continue
            for (name in namesIn(method, text)) {
                into += SolrFieldUsage(name, method.parameter, anchor)
            }
        }
    }

    /**
     * Reads a field name out of a document being built, or declines to.
     *
     * **Checked by receiver, not by method name, and the two disagree here.** `SolrInputDocument`
     * carries an `addField` and so does `SolrQuery`; a query's names something to return and a
     * document's names something to store, so they are read separately even though they are spelled
     * the same. Only the first argument is a name — the second is the value, and reading it would
     * report every string an application indexes as a field.
     *
     * Indexing into an undeclared field is the more expensive of the two mistakes this recognizer
     * catches. A collection running the default update chain adds the field to the deployed schema
     * rather than refusing the document, answers `status: 0`, and leaves the configset in the
     * repository and the schema on the server quietly disagreeing.
     */
    private fun readDocumentCall(call: UCallExpression, into: MutableList<SolrFieldUsage>) {
        if (call.methodName !in DOCUMENT_FIELD_METHODS) return
        val owner = call.resolve()?.containingClass?.qualifiedName ?: return
        if (owner !in SOLR_DOCUMENT_CLASSES) return

        val argument = call.valueArguments.firstOrNull() ?: return
        val name = constantTextOf(argument) ?: return
        val anchor = argument.sourcePsi ?: return
        into += SolrFieldUsage(name, BOUND_AT_INDEX_TIME, anchor)
    }

    /**
     * The string an argument spells out in the source, or null where it does not spell one out.
     *
     * **Not `evaluateString`, which is the obvious answer and the wrong one.** UAST's evaluator
     * follows a Kotlin `val` to its assignment while declining a Java local and even a Java
     * `static final`, so a recognizer built on it reads a variable in one language and not the
     * other — the disagreement that writing against UAST was chosen to prevent.
     *
     * What is wanted is narrower: the argument is read only where the source spells the name out.
     * A Java string literal arrives as [ULiteralExpression]; a Kotlin one never does, because Kotlin
     * models every string as a template that *may* interpolate, so `"categry:books"` arrives as a
     * polyadic expression holding a single literal operand. Both spell the name out.
     *
     * **The recursion is what keeps the two languages honest, and it is not optional.** A flat check
     * — a polyadic whose operands are all literals — would read Java's `"categry" + ":books"`, whose
     * operands are two literals, and decline Kotlin's identical source, whose operands are two
     * templates. That is the same defect this function was written to remove, one level down.
     * Descending through operands asks the same question of every level, so a concatenation of
     * spelled-out parts reads the same in both languages and an interpolated part stops it in both.
     *
     * @param argument one argument of a call already known to name fields
     * @return the string it spells out, or null to say nothing about this call
     */
    private fun constantTextOf(argument: UExpression): String? {
        if (argument is ULiteralExpression) return argument.value as? String
        if (argument !is UPolyadicExpression) return null
        // A single unreadable operand discards the whole argument rather than the part: half a field
        // name is not a field name, and reporting `books` out of `"$prefix:books"` would warn about
        // a field the developer never wrote.
        val parts = argument.operands.map { constantTextOf(it) ?: return null }
        return parts.joinToString("")
    }

    /**
     * Reads one client construction, or declines to.
     *
     * **Recognized by shape rather than by a list of class names, because the list would be wrong
     * within one Solr line.** `Http2SolrClient` and `HttpSolrClient` exist in 9 and are gone in 10;
     * `HttpJdkSolrClient` spans both. What is stable across them is the arrangement: a `Builder`
     * nested inside a class in SolrJ's `impl` package whose name ends in `SolrClient`, constructed
     * from the base URL. A recognizer holding names would go quiet on the next line the way the one
     * holding a single `SolrQuery` package once did.
     *
     * `CloudSolrClient.Builder` is excluded by the same rule that includes the others without
     * naming it: it is constructed from a list of ZooKeeper hosts rather than a URL, so no argument
     * spells out an endpoint and nothing is reported.
     */
    private fun readClientConstruction(call: UCallExpression, into: MutableList<SolrEndpointUsage>) {
        if (call.kind != UastCallKind.CONSTRUCTOR_CALL) return
        val builder = call.resolve()?.containingClass?.qualifiedName ?: return
        if (!namesAClientBuilder(builder)) return

        val argument = call.valueArguments.singleOrNull() ?: return
        val url = constantTextOf(argument) ?: return
        val anchor = argument.sourcePsi ?: return
        into += SolrEndpointUsage(url, usernameOf(call), anchor)
    }

    /**
     * Whether [qualifiedName] is a Solr client's nested `Builder`.
     *
     * @param qualifiedName the fully qualified name of the constructed class
     * @return true for `org.apache.solr.client.solrj.impl.<something>SolrClient.Builder`
     */
    private fun namesAClientBuilder(qualifiedName: String): Boolean =
        qualifiedName.startsWith(CLIENT_IMPL_PACKAGE) &&
            qualifiedName.endsWith(".Builder") &&
            qualifiedName.removeSuffix(".Builder").endsWith("SolrClient")

    /**
     * The identity a builder chain connects as, or null where it does not say.
     *
     * The credential is not an argument of the construction but a later link in the same chain —
     * `Builder(url).withBasicAuthCredentials(user, password)` — so finding it means walking outward
     * from the construction rather than into it. `withBasicAuthCredentials` is declared on
     * `HttpSolrClientBuilderBase` in both supported lines, which is why the method name alone is
     * enough here and the class check on the construction has already been made.
     *
     * Only the username is read. The password's own KDoc on [SolrEndpointUsage] records why.
     */
    private fun usernameOf(construction: UCallExpression): String? {
        var node: UElement? = construction.uastParent
        while (node is UQualifiedReferenceExpression) {
            val selector = node.selector as? UCallExpression
            if (selector?.methodName == BASIC_AUTH_METHOD) {
                return selector.valueArguments.firstOrNull()?.let { constantTextOf(it) }
            }
            node = node.uastParent
        }
        return null
    }

    /**
     * The names one argument holds, according to the shape its method declares.
     *
     * Each branch delegates to the grammar that owns it rather than re-deriving one here: a query
     * expression to [SolrQueryExpressions], a field list to [SolrQueryFields], and a single name
     * through the same exclusions a list applies to each element — which is why `addField("*")`
     * yields nothing, a glob being legal in an `fl` and not a field anyone declared.
     */
    private fun namesIn(method: SolrJQueryMethod, text: String): List<String> = when (method.shape) {
        SolrJArgumentShape.QUERY_EXPRESSION -> SolrQueryExpressions.fieldNamesIn(text)
        SolrJArgumentShape.FIELD_LIST -> SolrQueryFields.namesIn(method.parameter, text).map { it.name }
        SolrJArgumentShape.FIELD_NAME -> listOfNotNull(SolrQueryFields.fieldNameOrNull(text))
    }

    /** Where SolrJ keeps its client implementations, in every line the plugin supports. */
    private const val CLIENT_IMPL_PACKAGE = "org.apache.solr.client.solrj.impl."

    /** The builder method that names the user a client connects as. */
    private const val BASIC_AUTH_METHOD = "withBasicAuthCredentials"

    /**
     * The document classes whose field-naming methods are read.
     *
     * `SolrInputDocument` is the one a client builds by hand. Spelled in full for the same reason
     * the query classes are: a receiver check that matched on simple name would read any class
     * anywhere carrying an `addField`.
     */
    private val SOLR_DOCUMENT_CLASSES = setOf("org.apache.solr.common.SolrInputDocument")

    /** The document methods whose first argument is a field name. */
    private val DOCUMENT_FIELD_METHODS = setOf("addField", "setField")

    /** The attribute that carries the written name. */
    private const val VALUE_ATTRIBUTE = "value"

    /** SolrJ's sentinel for "use the member's own name", spelled exactly as SolrJ spells it. */
    private const val NAMES_ITS_MEMBER = "#default"

    /**
     * What a `@Field` asks of a field, where the query methods name a request parameter.
     *
     * A binding is not a request parameter and pretending otherwise would put `@Field` where `fq`
     * and `sort` live, which are answers to a different question: those say what the field must be
     * able to *do*, and this says only that it must exist.
     */
    private const val BOUND_AT_INDEX_TIME = "@Field"
}
