package org.apache.solr.ide.configset.completion

import com.intellij.codeInsight.completion.CompletionContributor
import com.intellij.codeInsight.completion.CompletionParameters
import com.intellij.codeInsight.completion.CompletionProvider
import com.intellij.codeInsight.completion.CompletionResultSet
import com.intellij.codeInsight.completion.CompletionType
import com.intellij.codeInsight.lookup.LookupElement
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.patterns.PlatformPatterns
import com.intellij.patterns.XmlPatterns
import com.intellij.psi.PsiElement
import com.intellij.psi.util.parentOfType
import com.intellij.psi.xml.XmlAttribute
import com.intellij.psi.xml.XmlAttributeValue
import com.intellij.psi.xml.XmlTag
import com.intellij.util.ProcessingContext
import org.apache.solr.ide.configset.parsing.SolrConfigsetReader
import org.apache.solr.ide.model.SolrClassCatalog
import org.apache.solr.ide.model.SolrClassKind
import org.apache.solr.ide.model.SolrFieldModel
import org.apache.solr.ide.model.SolrFieldProperties
import org.apache.solr.ide.model.SolrMatchAnalysis
import org.apache.solr.ide.model.SolrSchemaVersion
import org.apache.solr.ide.model.SolrVersionSelection
import org.apache.solr.ide.configset.activation.SolrSchemaTags
import org.apache.solr.ide.configset.documentation.SolrSchemaElements

/**
 * Completes the attribute values in a configset whose valid answers are knowable.
 *
 * Only positions whose valid set is genuinely closed are completed: the declared field types, the
 * declared fields, and `true`/`false` for the boolean properties. Where the set is open — a
 * `positionIncrementGap` accepts any integer, a field's `name` is the author's to choose — nothing
 * is contributed and the platform's own behaviour is left alone. Offering a partial list where any
 * value is legal would be worse than offering none, because a list implies the answers not on it
 * are wrong.
 *
 * **Other contributors are left to run.** An earlier revision called `stopHere` after contributing,
 * on the reasoning that a complete set cannot be improved on. Nothing was ever observed adding to
 * these positions, so it suppressed nothing — but `stopHere` silences every later contributor
 * including ones this plugin never anticipated, which is a large promise to make in exchange for a
 * guard against something that does not happen.
 */
class SolrConfigsetCompletionContributor : CompletionContributor() {

    /**
     * Runs while the project is still indexing.
     *
     * Completion here is answered from the configset's own text, never from an index, so the
     * platform's default of withholding this until indexing finishes would disable a working
     * feature during exactly the minutes a reader is first opening files.
     */
    override fun isDumbAware(): Boolean = true

    init {
        extend(
            CompletionType.BASIC,
            PlatformPatterns.psiElement().inside(XmlPatterns.xmlAttributeValue()),
            SolrAttributeValueCompletionProvider(),
        )
        // Element and attribute *names*, which answer "what may I write here at all" — the
        // question value completion never asks, and the one that matters to a reader who has not
        // learned the vocabulary yet.
        extend(CompletionType.BASIC, PlatformPatterns.psiElement().inside(XmlPatterns.xmlTag()), SolrSchemaVocabularyCompletionProvider())
    }
}

/**
 * Supplies the values for whichever attribute the caret sits in.
 *
 * Reads the tag and attribute names rather than the value being typed, so the dummy identifier the
 * platform inserts at the caret never has to be reasoned about.
 */
private class SolrAttributeValueCompletionProvider : CompletionProvider<CompletionParameters>() {

    override fun addCompletions(
        parameters: CompletionParameters,
        context: ProcessingContext,
        result: CompletionResultSet,
    ) {
        val file = parameters.originalFile
        val model = SolrConfigsetReader.getInstance(file.project).modelFor(file) ?: return
        val value = parameters.position.parentOfType<XmlAttributeValue>() ?: return
        val attribute = value.parentOfType<XmlAttribute>() ?: return
        val tag = attribute.parentOfType<XmlTag>() ?: return

        result.addAllElements(suggestionsFor(tag.name, attribute.name, model))
    }

    private fun suggestionsFor(tagName: String, attributeName: String, model: SolrFieldModel): List<LookupElement> = when {
        tagName in SolrSchemaTags.FIELD && attributeName == "type" -> fieldTypes(model)
        tagName == SolrSchemaTags.COPY_FIELD && attributeName in SolrSchemaTags.COPY_FIELD_ENDS -> fieldNames(model)
        tagName == SolrSchemaTags.ANALYZER && attributeName == "type" -> ANALYZER_PHASES
        attributeName == "class" -> classNames(tagName, model)
        // Every remaining answerable position is a property of a field or of a type. Scoping it to
        // those two tags is what keeps `<copyField synonymQueryStyle="">` — which means nothing —
        // from being offered the three values a field type would accept there.
        tagName in SolrSchemaTags.FIELD || tagName in SolrSchemaTags.FIELD_TYPE ->
            propertyValues(attributeName, model.schemaVersion)
        else -> emptyList()
    }

    /**
     * The declared field types, each showing what fields of it would match.
     *
     * The capability is the useful half of the suggestion: choosing between `string` and
     * `text_general` is a decision about matching, and the list is where that decision is made.
     */
    private fun fieldTypes(model: SolrFieldModel): List<LookupElement> =
        model.fieldTypes.values.map { it.effective }.map { type ->
            val capability = SolrMatchAnalysis.of(type)
            LookupElementBuilder.create(type.name)
                .withTypeText(if (capability.confident) capability.summary else type.className)
        }

    /**
     * The declared fields, and the dynamic patterns, each showing its type.
     *
     * Dynamic patterns are offered because a `copyField` may legitimately name one — `dest="*_t"`
     * is how a schema copies into a family of fields — and they are italicised so a pattern is not
     * mistaken for a field that exists.
     */
    private fun fieldNames(model: SolrFieldModel): List<LookupElement> =
        model.fields.values.map { it.effective }.map { field ->
            LookupElementBuilder.create(field.name).withTypeText(field.type)
        } + model.dynamicFields.values.map { it.effective }.map { dynamic ->
            LookupElementBuilder.create(dynamic.pattern).withTypeText(dynamic.field.type).withItemTextItalic(true)
        }

    /**
     * The classes legal in this element's `class` attribute, from the generated catalog.
     *
     * Which population applies is decided by the tag, because `class` means four different things
     * in a schema. Offering a tokenizer where a field type belongs would be offering an error, and
     * a reader learning the vocabulary from the list has no way to know it was wrong.
     *
     * The `solr.`-prefixed short form is inserted and the fully qualified name shown beside it:
     * that is the spelling real configsets use, and the long one is what tells you where the class
     * actually comes from — `LowerCaseFilterFactory` being Lucene's rather than Solr's is not
     * otherwise visible.
     */
    private fun classNames(tagName: String, model: SolrFieldModel): List<LookupElement> {
        val kind = SolrClassKind.forTag(tagName) ?: return emptyList()
        val version = model.luceneMatchVersion?.let { SolrVersionSelection.fromLuceneMatchVersion(it) }
            ?: SolrVersionSelection.DEFAULT
        return SolrClassCatalog.of(kind, version).map { entry ->
            LookupElementBuilder.create(entry.shortName).withTypeText(entry.className)
        }
    }

    /**
     * The values a property accepts, with the one Solr would have used marked as the default.
     *
     * Empty where any value is legal: `positionIncrementGap` takes any integer, and a list there
     * would imply the values not on it are wrong. Where the set *is* closed — two values for a
     * boolean, three for `synonymQueryStyle` — marking the default is the useful half. "This is
     * what you already have" is usually what a reader is trying to work out, and it is the question
     * a list of identical-looking values cannot otherwise answer.
     *
     * Where the default depends on the field type — `omitNorms` is true for primitive types and
     * false for text — nothing is marked, because marking one would assert something Solr does not.
     *
     * Where it depends on the schema's declared version, the mark follows *this* schema: on a 1.6
     * configset `uninvertible` marks `true`, because that is the value the reader would get by
     * leaving the attribute off the file in front of them.
     */
    private fun propertyValues(attributeName: String, schemaVersion: SolrSchemaVersion): List<LookupElement> {
        val property = SolrFieldProperties.byName(attributeName) ?: return emptyList()
        val default = property.defaultValue
            ?: property.defaultTrueWithin?.let { if (schemaVersion in it) "true" else "false" }
        return property.offerableValues.map { value ->
            val isDefault = value == default
            LookupElementBuilder.create(value)
                .withTypeText(if (isDefault) DEFAULT_LABEL else null)
                .withBoldness(isDefault)
        }
    }

    private companion object {
        /** Shown beside the value Solr would use if the attribute were absent. */
        const val DEFAULT_LABEL = "default"

        /**
         * The two analyzer phases.
         *
         * Not a field property, so it is not in the property table — but it is the closed set a
         * reader is most likely to want, since an index chain and a query chain that disagree is
         * the commonest way a schema surprises its author.
         */
        val ANALYZER_PHASES: List<LookupElement> = listOf(
            LookupElementBuilder.create("index").withTypeText("what is stored"),
            LookupElementBuilder.create("query").withTypeText("what is searched for"),
        )
    }
}

/**
 * Completes the element and attribute *names* a schema accepts.
 *
 * Value completion answers "what goes here"; this answers "what may I write at all". Someone who
 * knows `sortMissingLast` exists can type it — someone who does not will never meet it in a file
 * that does not already use it, and a reference guide is only a page away if you know what to search
 * for.
 *
 * Nesting and existing attributes are both respected. A `<copyField>` inside an `<analyzer>` is not
 * a thing, and an attribute already on the tag cannot be written twice; offering either would teach
 * the reader something false.
 */
private class SolrSchemaVocabularyCompletionProvider : CompletionProvider<CompletionParameters>() {

    override fun addCompletions(
        parameters: CompletionParameters,
        context: ProcessingContext,
        result: CompletionResultSet,
    ) {
        val file = parameters.originalFile
        val model = SolrConfigsetReader.getInstance(file.project).modelFor(file) ?: return
        val position = parameters.position

        // Inside an attribute value is the other provider's job; contributing here as well would
        // list every element name where a field name belongs.
        if (position.parentOfType<XmlAttributeValue>() != null) return

        val tag = position.parentOfType<XmlTag>() ?: return
        val attribute = position.parentOfType<XmlAttribute>()

        val suggestions = if (attribute != null || isAttributePosition(position, tag)) {
            attributeNames(tag, model)
        } else {
            elementNames(tag)
        }
        if (suggestions.isEmpty()) return
        result.addAllElements(suggestions)

        // The platform offers these same names again, read back from the element descriptors this
        // plugin now provides, as bare rows without the summaries. Only the exact duplicates are
        // dropped; everything else another contributor wants to add passes through untouched,
        // which is the promise `stopHere` could not make.
        val offered = suggestions.mapTo(HashSet()) { it.lookupString }
        result.runRemainingContributors(parameters) { delegate ->
            if (delegate.lookupElement.lookupString !in offered) result.passResult(delegate)
        }
    }

    /**
     * Whether the caret is where an attribute name would go rather than where a child element would.
     *
     * The distinction is the tag's own header: inside `<field |>` an attribute is expected, and
     * after `</field> |` a sibling element is.
     */
    private fun isAttributePosition(position: PsiElement, tag: XmlTag): Boolean {
        val header = tag.textRange.startOffset..(tag.attributes.lastOrNull()?.textRange?.endOffset ?: tag.textRange.startOffset)
        return position.textRange.startOffset in header
    }

    /**
     * The elements legal inside [tag] — or, when the caret sits in the tag being typed, the
     * elements legal beside it.
     */
    private fun elementNames(tag: XmlTag): List<LookupElement> {
        val candidates = SolrSchemaElements.childrenOf(tag.name).ifEmpty {
            SolrSchemaElements.childrenOf(tag.parentTag?.name)
        }
        return candidates.map { description ->
            LookupElementBuilder.create(description.tagName)
                .withTypeText(firstSentence(description.summary))
        }
    }

    /**
     * The attributes [tag] accepts, minus those it already carries.
     *
     * An attribute cannot be written twice, so offering one that is present is offering an error.
     */
    private fun attributeNames(tag: XmlTag, model: SolrFieldModel): List<LookupElement> {
        val already = tag.attributes.map { it.name }.toSet()
        analysisAttributeNames(tag, model, already)?.let { return it }
        val properties = when (tag.name) {
            in SolrSchemaTags.FIELD -> SolrFieldProperties.FOR_FIELD
            in SolrSchemaTags.FIELD_TYPE -> SolrFieldProperties.FOR_FIELD_TYPE
            else -> return structuralAttributeNames(tag.name, already)
        }
        return properties
            .filter { it.name !in already }
            .map { property ->
                LookupElementBuilder.create(property.name)
                    .withTypeText(property.validValues)
                    .withTailText("  ${firstSentence(property.summary)}", true)
            }
    }

    /**
     * The attributes of the structural elements the property table does not cover — the schema
     * root, a copy rule, an analyzer.
     *
     * [SolrSchemaElements] owns the list for the same reason it owns the element descriptions,
     * and an element it does not list — a `uniqueKey` takes no attributes at all — stays silent.
     */
    private fun structuralAttributeNames(tagName: String, already: Set<String>): List<LookupElement> =
        SolrSchemaElements.attributesOf(tagName)
            .filter { it.name !in already }
            .map { attribute ->
                LookupElementBuilder.create(attribute.name)
                    .withTailText("  ${firstSentence(attribute.summary)}", true)
            }

    /**
     * The attributes the factory named in this tag's `class` accepts, or null when the tag is not
     * an analysis component at all.
     *
     * Null and empty mean different things here. Null falls through to the field-property table,
     * which is right for a `field` or a `fieldType`. Empty stops — a `<filter>` whose class this
     * catalog does not know accepts attributes the plugin cannot name, and offering field
     * properties there would be offering nonsense with a straight face.
     *
     * The attributes are the half no listing of classes could supply: a factory reads them out of a
     * map by string literal, so they exist only in its constructor body.
     */
    private fun analysisAttributeNames(
        tag: XmlTag,
        model: SolrFieldModel,
        already: Set<String>,
    ): List<LookupElement>? {
        // A fieldType names a class too, but its attributes are the field properties, and the
        // property path owns those — this null keeps the fall-through that gets them offered.
        val kind = SolrClassKind.forTag(tag.name)?.takeIf { it != SolrClassKind.FIELD_TYPE }
            ?: return null
        val className = tag.getAttributeValue("class") ?: return emptyList()
        val version = model.luceneMatchVersion?.let { SolrVersionSelection.fromLuceneMatchVersion(it) }
            ?: SolrVersionSelection.DEFAULT
        val entry = SolrClassCatalog.find(className, version)?.takeIf { it.kind == kind } ?: return emptyList()
        return entry.attributes
            .filter { it.name !in already }
            .map { LookupElementBuilder.create(it.name).withTypeText(entry.shortName) }
    }

    /** The summary's first sentence, which is all a lookup row has space for. */
    private fun firstSentence(summary: String): String =
        summary.substringBefore(". ").removeSuffix(".").take(80)
}
