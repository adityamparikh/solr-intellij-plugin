package org.apache.solr.ide.configset.completion

import com.intellij.codeInsight.completion.CompletionParameters
import com.intellij.codeInsight.completion.CompletionProvider
import com.intellij.codeInsight.completion.CompletionResultSet
import com.intellij.codeInsight.lookup.LookupElement
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.psi.util.parentOfType
import com.intellij.psi.xml.XmlTag
import com.intellij.psi.xml.XmlText
import com.intellij.util.ProcessingContext
import org.apache.solr.ide.configset.activation.SolrConfigsetFileKind
import org.apache.solr.ide.configset.parsing.SolrConfigParameters
import org.apache.solr.ide.configset.parsing.SolrConfigParser
import org.apache.solr.ide.configset.parsing.SolrConfigsetReader
import org.apache.solr.ide.model.schema.SolrField
import org.apache.solr.ide.model.SolrFieldModel
import org.apache.solr.ide.model.schema.SolrFieldOperations
import org.apache.solr.ide.model.schema.SolrTypeTrait

/**
 * Offers the schema's field names inside a `solrconfig.xml` parameter that holds them.
 *
 * **The inverse of a warning that already ships.** The unknown-field inspection tells a reader that
 * `descriptoin` is not a field, and the list that lets it say so is the list that would have offered
 * `description` before it was mistyped. Until now the correction existed and the suggestion did not.
 *
 * **Scoped to the parameters [SolrConfigParser] reads field names out of, and no wider.** That set is
 * what separates a value holding field names from one holding a number, a parser name or a function
 * query, and it is asked rather than restated so completion and navigation cannot disagree: a name
 * offered here is a name that resolves, and a position that resolves is a position that offers.
 * Widening it would put field names inside `rows` and `defType`.
 *
 * **The syntax around a field name is not a field name.** A `qf` token may carry a `^`-boost and a
 * `sort` clause ends in a direction, so a caret past either offers nothing — completing there would
 * produce `title^title` or a field where Solr wants `asc`.
 */
internal class SolrParameterFieldCompletionProvider : CompletionProvider<CompletionParameters>() {

    override fun addCompletions(
        parameters: CompletionParameters,
        context: ProcessingContext,
        result: CompletionResultSet,
    ) {
        val file = parameters.originalFile
        if (SolrConfigsetFileKind.forFileName(file.name)?.isSolrConfig != true) return
        val model = SolrConfigsetReader.getInstance(file.project).modelFor(file) ?: return

        val text = parameters.position.parentOfType<XmlText>(withSelf = true) ?: return
        val tag = text.parentOfType<XmlTag>() ?: return
        val parameterName = SolrConfigParameters.parameterNameOf(tag) ?: return
        val caretInValue = (parameters.offset - text.textRange.startOffset).coerceAtLeast(0)
        val prefix = SolrConfigParser.fieldTokenAt(parameterName, text.text, caretInValue) ?: return

        // The prefix is set from the token rather than left to the platform. Its default matcher reads
        // an identifier back from the caret and does not treat Solr's separators as boundaries, so in
        // `sort=id asc,` it takes `asc,` as what the reader is typing and filters every field out of a
        // list that was correctly built. What the reader is typing is the token, and only this knows
        // where that token starts.
        result.withPrefixMatcher(prefix).addAllElements(fieldNames(parameterName, model))
    }

    /**
     * The declared fields and dynamic patterns, filtered to those that can serve the parameter.
     *
     * **Filtered rather than annotated, because the inspections would underline what was offered.** A
     * `qf` naming an unsearchable field and a `facet.field` naming an unfacetable one are both warnings
     * this plugin already reports, and a completion list that suggested them would be arguing with its
     * own inspections in the same file. Fields whose capability is undetermined — a custom field type —
     * are offered, on the same reasoning that keeps the inspections quiet about them.
     *
     * A dynamic pattern is offered because a parameter may legitimately name one, and italicised so it
     * is not mistaken for a field that exists.
     */
    private fun fieldNames(parameterName: String, model: SolrFieldModel): List<LookupElement> {
        val operation = SolrConfigParser.operationFor(parameterName)

        // Traits are memoised by type name because resolving them scans the generated catalog
        // linearly, and this runs per field on every keystroke: a schema with two hundred fields
        // typically names a dozen types, so the difference is two hundred scans against twelve.
        val traitsByType = HashMap<String, Set<SolrTypeTrait>?>()

        fun serves(field: SolrField): Boolean {
            val wanted = operation ?: return true
            val type = model.typeOf(field)
            val traits = traitsByType.getOrPut(field.type) { model.traitsOf(type) }
            return SolrFieldOperations.supports(
                wanted,
                field,
                type,
                model.schemaVersion,
                traits,
            ) != false
        }

        return model.fields.values.map { it.effective }
            .filter { serves(it) }
            .map { LookupElementBuilder.create(it.name).withTypeText(it.type) } +
            model.dynamicFields.values.map { it.effective }
                .filter { serves(it.field) }
                .map {
                    LookupElementBuilder.create(it.pattern)
                        .withTypeText(it.field.type)
                        .withItemTextItalic(true)
                }
    }
}
