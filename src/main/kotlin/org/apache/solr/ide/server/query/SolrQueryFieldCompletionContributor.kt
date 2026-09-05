package org.apache.solr.ide.server.query

import com.intellij.codeInsight.completion.CompletionContributor
import com.intellij.codeInsight.completion.CompletionParameters
import com.intellij.codeInsight.completion.CompletionResultSet
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.json.psi.JsonArray
import com.intellij.json.psi.JsonProperty
import com.intellij.json.psi.JsonValue
import com.intellij.lang.injection.InjectedLanguageManager
import com.intellij.psi.PsiElement
import com.intellij.psi.util.parentsOfType
import org.apache.solr.ide.configset.reading.SolrProjectFields

/**
 * Field names inside a Solr query written in an `.http` file.
 *
 * **The HTTP Client already injects JSON into a request body**, based on its `Content-Type`, so
 * nothing here has to arrange for that — this contributes to the JSON that is already there. That
 * is worth stating because the specification names a body *injector* as what makes this possible; an
 * injector is what a non-JSON body syntax would need, and Solr's JSON Request API is JSON.
 *
 * **Registered against JSON, which is most of the discipline.** Every JSON file in every project
 * reaches this class, so it declines in three steps before offering anything: the fragment must be
 * injected into an HTTP request, the body must look like a Solr query, and the caret must be
 * somewhere a field name belongs. A completion contributor that guessed would put Solr field names
 * into unrelated JSON, which is the same failure as an inspection firing on a correct file.
 *
 * Dumb-aware by declining: the field source needs the filename index to find configsets, and
 * offering nothing while it is unavailable is understood as "not ready", where offering half would
 * be understood as the truth.
 */
class SolrQueryFieldCompletionContributor : CompletionContributor() {

    /**
     * Offers the project's field names where one belongs.
     *
     * @param parameters where the caret is
     * @param result where offers are added
     */
    override fun fillCompletionVariants(parameters: CompletionParameters, result: CompletionResultSet) {
        val position = parameters.position
        if (!isInHttpRequest(position)) return

        val path = pathOf(position)
        if (!SolrQueryBodyPositions.isQueryBody(path) || !SolrQueryBodyPositions.namesAField(path)) return

        SolrProjectFields.getInstance(position.project).all()
            .forEach { result.addElement(it.asLookupElement()) }
    }

    /**
     * Whether this fragment is injected into an HTTP request file.
     *
     * The check that keeps Solr field names out of every other JSON file in the project.
     */
    private fun isInHttpRequest(position: PsiElement): Boolean {
        val host = InjectedLanguageManager.getInstance(position.project)
            .getInjectionHost(position) ?: return false
        return host.containingFile?.fileType?.defaultExtension == HTTP_REQUEST_EXTENSION
    }

    /**
     * The JSON keys from the document root to [position], outermost first.
     *
     * Array indices contribute nothing: `fields` and `fields[2]` are the same position, because a
     * field list may be written as one string or as an array of them and Solr reads both.
     */
    private fun pathOf(position: PsiElement): List<String> =
        position.parentsOfType<JsonValue>()
            .mapNotNull { value -> (value.parent as? JsonProperty)?.name ?: arrayOwnerName(value) }
            .toList()
            .distinct()
            .reversed()

    private fun arrayOwnerName(value: JsonValue): String? =
        ((value.parent as? JsonArray)?.parent as? JsonProperty)?.name

    private companion object {
        /**
         * The extension the HTTP Client's own file type declares.
         *
         * Read from the file type rather than matched against a name, so a request opened as a
         * scratch — which the HTTP Client supports and which has no `.http` in its path — is still
         * recognised.
         */
        const val HTTP_REQUEST_EXTENSION = "http"
    }
}
