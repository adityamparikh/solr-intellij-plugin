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
import org.apache.solr.ide.model.SolrFieldModel
import org.apache.solr.ide.model.SolrFieldProperties
import org.apache.solr.ide.model.SolrMatchAnalysis
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
 * `stopHere` is called after contributing, which is the documented way to keep other contributors
 * from extending a set that is already complete. Note that no other contributor was observed adding
 * to these positions in testing, so this is a guard rather than a fix for something reproduced —
 * IntelliJ's word completion, the obvious candidate, does not fire inside an XML attribute value.
 */
class SolrConfigsetCompletionContributor : CompletionContributor() {

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

        val suggestions = suggestionsFor(tag.name, attribute.name, model)
        if (suggestions.isEmpty()) return
        result.addAllElements(suggestions)

        // The values above are the complete set Solr accepts here, so anything else on the list
        // could only be wrong. A guard rather than a fix for anything observed — see the class doc.
        result.stopHere()
    }

    private fun suggestionsFor(tagName: String, attributeName: String, model: SolrFieldModel): List<LookupElement> = when {
        tagName in SolrSchemaTags.FIELD && attributeName == "type" -> fieldTypes(model)
        tagName == "copyField" && attributeName in COPY_FIELD_ATTRIBUTES -> fieldNames(model)
        tagName in SolrSchemaTags.FIELD && isBooleanProperty(attributeName) -> booleans(attributeName)
        tagName in SolrSchemaTags.FIELD_TYPE && isBooleanProperty(attributeName) -> booleans(attributeName)
        tagName == "analyzer" && attributeName == "type" -> ANALYZER_PHASES
        closedValuesFor(attributeName).isNotEmpty() -> closedValues(attributeName)
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
     * Whether [attributeName] is one of the properties that takes only `true` or `false`.
     *
     * Read from the property table rather than listed again here, so a property added there gains
     * completion without a second edit — and so the two can never disagree about what a property
     * accepts.
     */
    private fun isBooleanProperty(attributeName: String): Boolean =
        SolrFieldProperties.byName(attributeName)?.validValues == BOOLEAN_VALUES

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
     * `true` and `false`, with the one Solr would have used marked as the default.
     *
     * "This is what you already have" is usually what a reader is trying to work out, and it is the
     * question a list of two identical-looking values cannot answer. Where the default depends on
     * the field type — `omitNorms` is true for primitive types and false for text — neither value
     * is marked, because marking one would assert something Solr does not.
     */
    /**
     * The closed set an attribute accepts, when it has one that is not boolean.
     *
     * Empty for anything open-ended. `synonymQueryStyle` takes one of three;
     * `positionIncrementGap` takes any integer, and offering a list there would imply the values
     * not on it are wrong.
     */
    private fun closedValuesFor(attributeName: String): List<String> =
        SolrFieldProperties.byName(attributeName)?.closedValues.orEmpty()

    private fun closedValues(attributeName: String): List<LookupElement> {
        val default = SolrFieldProperties.byName(attributeName)?.defaultValue
        return closedValuesFor(attributeName).map { value ->
            LookupElementBuilder.create(value)
                .withTypeText(if (value == default) DEFAULT_LABEL else null)
                .withBoldness(value == default)
        }
    }

    private fun booleans(attributeName: String): List<LookupElement> {
        val default = SolrFieldProperties.byName(attributeName)?.defaultValue
        return listOf("true", "false").map { value ->
            LookupElementBuilder.create(value)
                .withTypeText(if (value == default) DEFAULT_LABEL else null)
                .withBoldness(value == default)
        }
    }

    private companion object {
        val COPY_FIELD_ATTRIBUTES = setOf("source", "dest")

        /** The `validValues` string the property table uses for a boolean. */
        const val BOOLEAN_VALUES = "true or false"

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
        if (SolrConfigsetReader.getInstance(file.project).modelFor(file) == null) return
        val position = parameters.position

        // Inside an attribute value is the other provider's job; contributing here as well would
        // list every element name where a field name belongs.
        if (position.parentOfType<XmlAttributeValue>() != null) return

        val tag = position.parentOfType<XmlTag>() ?: return
        val attribute = position.parentOfType<XmlAttribute>()

        val suggestions = if (attribute != null || isAttributePosition(position, tag)) {
            attributeNames(tag)
        } else {
            elementNames(tag)
        }
        if (suggestions.isEmpty()) return
        result.addAllElements(suggestions)
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
    private fun attributeNames(tag: XmlTag): List<LookupElement> {
        val already = tag.attributes.mapNotNull { it.name }.toSet()
        val properties = when (tag.name) {
            in SolrSchemaTags.FIELD -> SolrFieldProperties.FOR_FIELD
            in SolrSchemaTags.FIELD_TYPE -> SolrFieldProperties.FOR_FIELD_TYPE
            else -> return emptyList()
        }
        return properties
            .filter { it.name !in already }
            .map { property ->
                LookupElementBuilder.create(property.name)
                    .withTypeText(property.validValues)
                    .withTailText("  ${firstSentence(property.summary)}", true)
            }
    }

    /** The summary's first sentence, which is all a lookup row has space for. */
    private fun firstSentence(summary: String): String =
        summary.substringBefore(". ").removeSuffix(".").take(80)
}
