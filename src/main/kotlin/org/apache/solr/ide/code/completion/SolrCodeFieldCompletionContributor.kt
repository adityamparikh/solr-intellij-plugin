package org.apache.solr.ide.code.completion

import com.intellij.codeInsight.completion.CompletionContributor
import com.intellij.codeInsight.completion.CompletionParameters
import com.intellij.codeInsight.completion.CompletionResultSet
import org.apache.solr.ide.code.SolrRecognizers
import org.apache.solr.ide.code.solrj.SolrJFieldPositions
import org.apache.solr.ide.configset.reading.SolrProjectFields
import org.jetbrains.uast.UastFacade

/**
 * Field names where Java or Kotlin code is naming one.
 *
 * The other half of what the code track owes a user. The inspection says a name is wrong after it is
 * written; this offers the right ones while it is being typed, which is the half that prevents the
 * mistake rather than reporting it.
 *
 * **Registered against no language, so Java and Kotlin both reach it — which makes declining the
 * important part.** Every file of either language in every project arrives here, so it refuses in
 * steps before offering anything: the module must carry a Solr client, and the *caret* must be
 * somewhere a field name belongs — not merely the argument. In `setQuery("category:bo|")` the
 * argument names fields and the caret is in a value; offering a field there completes
 * `category:category`.
 *
 * **Only names the position can use.** Declared fields rather than dynamic patterns — `*_t` inserted
 * into a query is a wildcard field Solr rejects — and of those, only the ones that can do what the
 * call asks: a sort is offered sortable fields, a facet facetable ones, a query searchable ones. What
 * is left out is what an inspection would underline once written.
 *
 * **And only them.** In a field position this stops the contributors after it, which would otherwise
 * fill the popup with the words and file paths the platform offers inside any string — `Search.java`
 * beside `category` is not a choice anybody writing a query is making. Registered first so that it
 * runs before them. Outside a field position it adds nothing and stops nothing.
 *
 * The popup appearing while typing, rather than only on Ctrl-Space, is [SolrCodeFieldCompletionConfidence]
 * and [SolrCodeFieldTypedHandler]'s doing: inside a string the platform suppresses it unless told
 * otherwise.
 *
 * Dumb-aware by declining rather than by waiting: the field source needs the filename index to find
 * configsets, and an empty offer while it is unavailable reads as "nothing to suggest", where a
 * partial one would read as the whole truth.
 */
class SolrCodeFieldCompletionContributor : CompletionContributor() {

    /**
     * Offers the project's field names that can serve the position the caret is in.
     *
     * @param parameters where the caret is
     * @param result where offers are added
     */
    override fun fillCompletionVariants(parameters: CompletionParameters, result: CompletionResultSet) {
        val position = parameters.position

        // **Cheapest question first, and it is what makes `language="any"` affordable.** Registered
        // that way this is reached from every Ctrl-Space in every file the IDE opens — Markdown,
        // YAML, JSON, XML — and the gate below walks the module's libraries under a read action. A
        // file no JVM language reads can never reach the position check, so it is turned away here
        // rather than after that walk.
        if (UastFacade.findPlugin(position.language) == null) return

        // The recognizers' own gate, asked without reading: there is no written name yet.
        if (!SolrRecognizers.recognizeSolrIn(position.containingFile ?: return)) return

        val slot = SolrJFieldPositions.fieldSlotAt(position, parameters.offset) ?: return

        // The prefix is the token the grammar found rather than the platform's guess. Its default
        // reads an identifier back from the caret, which in `category:books AND na` is right by luck
        // and in `id,na` or `some-fi` is not.
        val fields = SolrProjectFields.getInstance(position.project).declaredServing(slot.operation)
        // Nothing to offer — no configset in the project — is not a reason to silence the rest.
        if (fields.isEmpty()) return
        val offers = result.withPrefixMatcher(slot.prefix)
        fields.forEach { offers.addElement(it.asLookupElement()) }
        result.stopHere()
    }
}
