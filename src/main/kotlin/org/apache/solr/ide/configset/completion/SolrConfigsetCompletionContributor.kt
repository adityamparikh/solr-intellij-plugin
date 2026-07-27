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
import com.intellij.psi.util.parentOfType
import com.intellij.psi.xml.XmlAttribute
import com.intellij.psi.xml.XmlAttributeValue
import com.intellij.psi.xml.XmlTag
import com.intellij.util.ProcessingContext
import org.apache.solr.ide.configset.parsing.SolrConfigsetReader
import org.apache.solr.ide.model.SolrFieldModel
import org.apache.solr.ide.model.SolrFieldProperties
import org.apache.solr.ide.model.SolrMatchAnalysis

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
        tagName in FIELD_TAGS && attributeName == "type" -> fieldTypes(model)
        tagName == "copyField" && attributeName in COPY_FIELD_ATTRIBUTES -> fieldNames(model)
        tagName in FIELD_TAGS && isBooleanProperty(attributeName) -> BOOLEANS
        tagName in TYPE_TAGS && isBooleanProperty(attributeName) -> BOOLEANS
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

    private companion object {
        val FIELD_TAGS = setOf("field", "dynamicField")
        val TYPE_TAGS = setOf("fieldType", "fieldtype")
        val COPY_FIELD_ATTRIBUTES = setOf("source", "dest")

        /** The `validValues` string the property table uses for a boolean. */
        const val BOOLEAN_VALUES = "true or false"

        val BOOLEANS: List<LookupElement> = listOf(
            LookupElementBuilder.create("true"),
            LookupElementBuilder.create("false"),
        )
    }
}
