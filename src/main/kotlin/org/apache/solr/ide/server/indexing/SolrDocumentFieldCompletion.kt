package org.apache.solr.ide.server.indexing

import com.intellij.codeInsight.completion.CompletionContributor
import com.intellij.codeInsight.completion.CompletionParameters
import com.intellij.codeInsight.completion.CompletionResultSet
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.json.psi.JsonProperty
import com.intellij.openapi.util.Key
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.util.parentOfType
import org.apache.solr.ide.model.SolrConfigsetFacts

/**
 * The schema a document editor checks and completes against.
 *
 * **Carried on the file rather than looked up.** A document being written for a collection is
 * checked against *that collection's* schema, which the dialog has already read and which no rule
 * over the file's location could recover — the file exists only in memory and belongs to no
 * configset. Putting it here is what lets a completion contributor, which the platform calls with
 * nothing but a caret, know which schema it is completing for.
 */
val SOLR_DOCUMENT_SCHEMA: Key<SolrConfigsetFacts> = Key.create("solr.document.schema")

/**
 * Field names inside a document being written for a collection.
 *
 * **Registered against JSON, so every JSON file in every project reaches it** — the same exposure
 * the query-body completion carries, and the same discipline in response: it offers nothing unless
 * the file was opened by this plugin's document editor, which is what carrying the schema on the
 * file establishes. A contributor that guessed would put Solr field names into somebody's
 * `package.json`.
 *
 * **The fields come from the collection, not from the project's configsets.** That differs from the
 * query completion deliberately: a query is written against whatever the developer is working on,
 * while a document is about to be sent to one named collection whose schema has already been read.
 * Completing from anything else would offer a field the target cannot accept — and Solr would then
 * accept it anyway, by adding it to the deployed schema.
 */
class SolrDocumentFieldCompletionContributor : CompletionContributor() {

    /**
     * Offers the collection's field names where a document names one.
     *
     * @param parameters where the caret is
     * @param result where offers are added
     */
    override fun fillCompletionVariants(parameters: CompletionParameters, result: CompletionResultSet) {
        val facts = schemaFor(parameters.position.containingFile) ?: return
        if (!namesAField(parameters.position)) return

        facts.fields.filterNot { it.name.isInternal() }.forEach { field ->
            result.addElement(
                LookupElementBuilder.create(field.name)
                    .withTypeText(field.type)
                    .withBoldness(field.required == true),
            )
        }
        // Patterns are offered too, and italicised as they are everywhere else in this plugin. A
        // user naming `author_s` is naming the pattern's instance, and the pattern is the only clue
        // the schema gives that such a name is legal at all.
        facts.dynamicFields.forEach { pattern ->
            result.addElement(
                LookupElementBuilder.create(pattern.pattern)
                    .withTypeText(pattern.field.type)
                    .withItemTextItalic(true),
            )
        }
    }

    /**
     * The schema this file is being written against, or null where it is not one of ours.
     *
     * The original file is consulted as well as the file itself, because completion runs over a copy
     * and the copy is where the caret is. Only those two: the virtual-file variants an earlier draft
     * carried were speculation about a case nobody produced, and a branch no input reaches is one a
     * reader has to wonder about.
     */
    private fun schemaFor(file: PsiFile?): SolrConfigsetFacts? {
        if (file == null) return null
        return file.getUserData(SOLR_DOCUMENT_SCHEMA) ?: file.originalFile.getUserData(SOLR_DOCUMENT_SCHEMA)
    }

    /**
     * Whether the caret is where a document names a field.
     *
     * A document is an object of field names to values, so a field name is a *key*, and the question
     * is only ever which half of a property the caret sits in. Completing in a value would offer
     * field names as data, which is a different and wrong suggestion.
     *
     * **A caret with nothing typed yet is still inside a property**, which is why there is no second
     * case here for a bare object. The platform substitutes a dummy identifier at the caret before
     * re-parsing, so `{|}` reaches this as an object holding one nameless-value property. An earlier
     * draft carried an `else` for the bare-object position; every input that could reach it went
     * down this path instead.
     */
    private fun namesAField(position: PsiElement): Boolean {
        val property = position.parentOfType<JsonProperty>() ?: return false
        return property.nameElement.textRange.contains(position.textRange.startOffset)
    }

    private fun String.isInternal() = length > 2 && startsWith('_') && endsWith('_')
}
