package org.apache.solr.ide.server.query

import com.intellij.codeInsight.completion.CompletionContributor
import com.intellij.codeInsight.completion.CompletionParameters
import com.intellij.codeInsight.completion.CompletionResultSet
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.json.psi.JsonArray
import com.intellij.json.psi.JsonProperty
import com.intellij.json.psi.JsonValue
import com.intellij.httpClient.http.request.psi.HttpRequest
import com.intellij.lang.injection.InjectedLanguageManager
import com.intellij.openapi.progress.runBlockingCancellable
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.psi.PsiElement
import com.intellij.psi.util.parentOfType
import com.intellij.psi.util.parentsOfType
import org.apache.solr.ide.SolrBundle
import org.apache.solr.ide.configset.reading.SolrCompletionField
import org.apache.solr.ide.configset.reading.SolrProjectFields
import org.apache.solr.ide.server.connection.SolrConnectionSettings
import org.apache.solr.ide.server.transport.SolrResponse

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
 * **The fields come from the collection the request is about to query, where that can be known.**
 * The request line names the collection — see [SolrRequestCollection] — and the selected connection
 * names the server; a collection read from there outranks the project's configsets rather than
 * joining them, because a field the collection does not hold is one this query cannot use. Where
 * either is unknown, or the server cannot be read, the configsets answer as they always have.
 *
 * **Only a completion the user asked for may contact the server.** An explicit invocation reads the
 * collection's schema once, through [SolrCollectionFields], and every popup after it — including
 * the ones that open while typing — reuses that read. A popup that opened by itself never sends a
 * request: typing is not asking, and the plugin's rule is that nothing on the editor path contacts a
 * server unbidden.
 *
 * Dumb-aware by declining: the configsets need the filename index to be found, and offering nothing
 * while it is unavailable is understood as "not ready", where offering half would be understood as
 * the truth.
 */
class SolrQueryFieldCompletionContributor : CompletionContributor() {

    /**
     * Offers the queried collection's field names, or the project's, where one belongs.
     *
     * @param parameters where the caret is, and whether the user asked
     * @param result where offers are added
     */
    override fun fillCompletionVariants(parameters: CompletionParameters, result: CompletionResultSet) {
        val position = parameters.position
        val host = InjectedLanguageManager.getInstance(position.project).getInjectionHost(position) ?: return
        // The check that keeps Solr field names out of every other JSON file in the project.
        if (host.containingFile?.fileType?.defaultExtension != HTTP_REQUEST_EXTENSION) return

        val path = pathOf(position)
        if (!SolrQueryBodyPositions.isQueryBody(path) || !SolrQueryBodyPositions.namesAField(path)) return

        val fields = collectionFields(parameters, host, result)
            ?: SolrProjectFields.getInstance(position.project).all()
        fields.forEach { result.addElement(it.asLookupElement()) }
    }

    /**
     * The fields of the collection this request queries, or null where the configsets should answer.
     *
     * Null where there is no connection, where the request line's collection cannot be known, where
     * nothing has been read and the user did not ask, and where the read failed — the last saying why
     * at the foot of the popup, since a silent fallback would read as the server having answered.
     */
    private fun collectionFields(
        parameters: CompletionParameters,
        host: PsiElement,
        result: CompletionResultSet,
    ): List<SolrCompletionField>? {
        val project = host.project
        val connection = SolrConnectionSettings.getInstance(project).selectedConnection ?: return null
        val target = host.parentOfType<HttpRequest>()?.requestTarget?.text ?: return null
        val environments by lazy { SolrHttpEnvironments.parse(environmentFilesBeside(host)) }
        val collection = SolrRequestCollection.of(target) { SolrHttpEnvironments.agreedValue(it, environments) }
            ?: return null

        val fields = SolrCollectionFields.getInstance(project)
        fields.cached(connection, collection)?.let { return it }
        if (parameters.isAutoPopup) return null

        val read = runBlockingCancellable { fields.fetch(connection, collection) }
        if (read is SolrResponse.Success) return read.value
        result.addLookupAdvertisement(
            SolrBundle.message("query.completion.unreadable", collection, connection.displayName, reasonFor(read)),
        )
        return null
    }

    /**
     * The HTTP Client's environment files beside the request, and above it up to its content root,
     * public before private.
     *
     * Read from the directories rather than from the HTTP Client, which resolves them only for the
     * environment its toolbar has selected and does not publish which that is.
     */
    private fun environmentFilesBeside(host: PsiElement): List<String> {
        val file = host.containingFile?.originalFile?.virtualFile ?: return emptyList()
        val root = ProjectFileIndex.getInstance(host.project).getContentRootForFile(file)
        val directories = generateSequence(file.parent) { dir -> dir.parent.takeIf { dir != root } }.toList()
        return ENVIRONMENT_FILES.flatMap { name -> directories.mapNotNull { it.findChild(name) } }
            .mapNotNull { runCatching { VfsUtilCore.loadText(it) }.getOrNull() }
    }

    private fun reasonFor(read: SolrResponse<*>): String = when (read) {
        is SolrResponse.SolrError -> listOfNotNull(read.code.toString(), read.message).joinToString(" ")
        is SolrResponse.TransportFailure -> read.description
        is SolrResponse.Unrecognized -> read.description
        is SolrResponse.Partial -> read.detail ?: SolrBundle.message("query.completion.incomplete")
        is SolrResponse.Success -> ""
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

        /** The HTTP Client's own names for them, public first so a private value layers over it. */
        val ENVIRONMENT_FILES = listOf("http-client.env.json", "http-client.private.env.json")
    }
}
