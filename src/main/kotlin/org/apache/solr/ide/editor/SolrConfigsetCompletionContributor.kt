package org.apache.solr.ide.editor

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
import org.apache.solr.ide.model.SolrFieldModel
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
        val model = SolrInspections.modelFor(file) ?: return
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
                .withTypeText(if (capability.confident) SolrFieldPresentation.summarize(capability) else type.className)
        }

    private companion object {
        val FIELD_TAGS = setOf("field", "dynamicField")
    }
}
