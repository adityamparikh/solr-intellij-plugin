package org.apache.solr.ide.server.query

import com.intellij.execution.ui.ConsoleViewContentType
import com.intellij.httpClient.execution.common.CommonClientResponse
import com.intellij.httpClient.execution.common.CommonClientResponseBody
import com.intellij.httpClient.http.request.psi.HttpRequest
import com.intellij.httpClient.http.request.run.console.HttpClientMessagePrinter
import com.intellij.httpClient.http.request.run.console.HttpResponseCustomPresentation
import com.intellij.psi.SmartPsiElementPointer

/**
 * Prints a readable summary above a Solr query's raw JSON in the HTTP Client.
 *
 * **This runs for every response the HTTP Client shows, including everyone else's**, because the
 * extension point has no applicability test — it is called and expected to decide for itself. So
 * declining is the common case, and the discipline is the one the inspections follow: say nothing
 * unless there is something to say. A presentation that guessed wrong would print a Solr summary
 * over another plugin's response, in a view this plugin has no business touching.
 *
 * **What makes a response ours is decided by its body, never by its URL.** Solr behind a reverse
 * proxy is addressed at any path at all, and a non-Solr service is free to expose one called
 * `/select`. [SolrQueryResultReader] holds that rule.
 *
 * **Printed before the response, not instead of it.** The HTTP Client prints the full JSON
 * underneath, which is what makes a lossy summary safe: columns are dropped and cells are cut, and
 * the authoritative text is one scroll away.
 */
class SolrQueryResponsePresentation : HttpResponseCustomPresentation {

    /**
     * Where this prints relative to the response's own header.
     *
     * After it, so the status line the HTTP Client already shows stays first — this adds to the
     * response's account of itself rather than displacing it.
     */
    override val order: HttpResponseCustomPresentation.Order =
        HttpResponseCustomPresentation.Order.AFTER_HEADER

    /**
     * Prints the summary, if [response] is a Solr query answer.
     *
     * @param response what came back
     * @param request the request that produced it, unused — the body is what decides
     * @param printer where to write
     */
    override fun print(
        response: CommonClientResponse,
        request: SmartPsiElementPointer<HttpRequest>,
        printer: HttpClientMessagePrinter,
    ) {
        val summary = summaryFor(response.body) ?: return
        printer.print(summary, ConsoleViewContentType.NORMAL_OUTPUT)
        printer.print("\n\n", ConsoleViewContentType.NORMAL_OUTPUT)
    }

    /**
     * What to print for [body], or null to print nothing at all.
     *
     * **Separate from [print] because deciding is the part worth testing and printing is not.**
     * Every case that matters — somebody else's JSON, an HTML error page, a Solr response that is
     * not a query — is a decision about a body, and reaching them through [print] would mean
     * standing up a `CommonClientResponse` whose other members this class never touches.
     *
     * Only a text body can be a Solr response. A streamed or binary one is declined rather than
     * drained to find out, because draining it is the response viewer's job and doing it here would
     * consume what it is about to read.
     *
     * @param body what came back
     * @return the summary to print, or null where this response is not ours
     */
    internal fun summaryFor(body: CommonClientResponseBody): String? {
        val text = (body as? CommonClientResponseBody.Text)?.content ?: return null
        return SolrQueryResultReader.read(text)?.let { SolrQueryResultRenderer.render(it) }
    }
}
