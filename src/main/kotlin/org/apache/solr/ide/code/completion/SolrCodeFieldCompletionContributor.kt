package org.apache.solr.ide.code.completion

import com.intellij.codeInsight.completion.CompletionContributor
import com.intellij.codeInsight.completion.CompletionParameters
import com.intellij.codeInsight.completion.CompletionResultSet
import com.intellij.codeInsight.lookup.LookupElementBuilder
import org.apache.solr.ide.code.SolrRecognizers
import org.apache.solr.ide.code.solrj.SolrJFieldPositions
import org.apache.solr.ide.configset.reading.SolrProjectFields

/**
 * Field names where Java or Kotlin code is naming one.
 *
 * The other half of what the code track owes a user. The inspection says a name is wrong after it is
 * written; this offers the right ones while it is being typed, which is the half that prevents the
 * mistake rather than reporting it.
 *
 * **Registered against no language, so Java and Kotlin both reach it — which makes declining the
 * important part.** Every file of either language in every project arrives here, so it refuses in
 * two steps before offering anything: the module must carry a Solr client, and the caret must be
 * somewhere a field name belongs. A contributor that guessed would put Solr field names into
 * unrelated code, which is the same failure as an inspection firing on a correct file.
 *
 * Dumb-aware by declining rather than by waiting: the field source needs the filename index to find
 * configsets, and an empty offer while it is unavailable reads as "nothing to suggest", where a
 * partial one would read as the whole truth.
 */
class SolrCodeFieldCompletionContributor : CompletionContributor() {

    /**
     * Offers the project's field names where one belongs.
     *
     * @param parameters where the caret is
     * @param result where offers are added
     */
    override fun fillCompletionVariants(parameters: CompletionParameters, result: CompletionResultSet) {
        val position = parameters.position

        // The recognizers' own gate, asked without reading: there is no written name yet.
        if (!SolrRecognizers.recognizeSolrIn(position.containingFile ?: return)) return

        if (!SolrJFieldPositions.namesAFieldAt(position)) return

        SolrProjectFields.getInstance(position.project).all().forEach { field ->
            result.addElement(
                LookupElementBuilder.create(field.name)
                    .withTypeText(field.type)
                    .withTailText("  ${field.configset}", true)
                    // A pattern is not a field, and italics is how both other Solr completions mark
                    // that same distinction — so the three surfaces read alike.
                    .withItemTextItalic(field.dynamic),
            )
        }
    }
}
