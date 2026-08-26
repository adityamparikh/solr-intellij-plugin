package org.apache.solr.ide.server.transport

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.util.Base64
import java.util.concurrent.CompletableFuture
import org.apache.solr.ide.server.reading.SolrJsonDocuments
import tools.jackson.databind.JsonNode

/**
 * One request to Solr, and what came back, classified.
 *
 * **`java.net.http.HttpClient`, and nothing wrapped around it.** The specification's parent argues
 * for plain HTTP over embedding a client library, and this is where that lands: the JDK's client,
 * asynchronous so nothing here blocks the UI thread, with a per-request timeout so a server that
 * never answers becomes an outcome rather than a hang.
 *
 * **This carries the plugin's own traffic, not the user's.** Fetching a schema or a cluster status is
 * nobody's authored request — it is a tool window calling out on its own initiative, which is why
 * this is a function returning a result rather than an editor. A query somebody typed is run by the
 * IDE's HTTP Client instead.
 *
 * **The client sets no proxy and no SSL context, deliberately.** Both are inherited from the JVM the
 * IDE configured, which is what makes a corporate proxy and a private certificate authority work
 * without this plugin knowing anything about either. Setting either here would take that decision
 * away from the IDE and break exactly the developers least able to diagnose it.
 *
 * **A credential is sent on the first request, never after a challenge.** No `Authenticator` is
 * installed: the JDK's would wait to be challenged, which costs a round trip against every Solr and
 * fails outright against one that answers 401 without a `WWW-Authenticate` header. The header is
 * built here and put on the request.
 *
 * @property timeout how long one request may take before it becomes a [SolrResponse.TransportFailure]
 */
class SolrHttpTransport(private val timeout: Duration = Duration.ofSeconds(10)) {

    private val client: HttpClient = HttpClient.newBuilder()
        .connectTimeout(timeout)
        // No `.proxy(...)` and no `.sslContext(...)`: see the class comment. Their absence is the
        // decision, which is why it is written down rather than left looking like an omission.
        .build()

    /**
     * Fetches [path] from [baseUrl] and classifies the answer.
     *
     * @param baseUrl the server's base URL, as a connection records it
     * @param path the path to request, beginning with a slash
     * @param username the user to authenticate as, or null for an unauthenticated server
     * @param password that user's password, or null
     * @return the outcome, which never completes exceptionally — every failure is a
     *   [SolrResponse] case, because a caller that must catch to find out what happened will
     *   eventually catch too much
     */
    fun get(
        baseUrl: String,
        path: String,
        username: String? = null,
        password: String? = null,
    ): CompletableFuture<SolrResponse<JsonNode>> {
        return send(baseUrl, path, username, password) { it.GET() }
    }

    /**
     * Builds a request, authenticates it, sends it, and classifies the answer.
     *
     * **Separated from [get] so that a verb is the only thing a caller adds.** The configset upload
     * and document indexing this specification also requires are POSTs with a body, and everything
     * around the verb — the URI, the credential, the timeout, the classification — is identical.
     * Written into [get] instead, each of those would arrive as a near-copy free to drift on auth or
     * on what counts as a failure.
     *
     * It is also what makes the client's configuration testable: a caller can build a request
     * without sending one.
     *
     * @param method applies the verb, and the body where there is one
     */
    private fun send(
        baseUrl: String,
        path: String,
        username: String?,
        password: String?,
        method: (HttpRequest.Builder) -> HttpRequest.Builder,
    ): CompletableFuture<SolrResponse<JsonNode>> {
        val request = runCatching {
            val builder = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl.trimEnd('/') + path))
                .timeout(timeout)
            authorization(username, password)?.let { builder.header("Authorization", it) }
            method(builder).build()
        }.getOrElse {
            return CompletableFuture.completedFuture(
                SolrResponse.TransportFailure(it.message ?: "the address could not be understood"),
            )
        }

        return client.sendAsync(request, HttpResponse.BodyHandlers.ofString())
            .handle { response, failure ->
                if (failure != null) {
                    // Described rather than rethrown. The cause is unwrapped because a
                    // `CompletionException` names itself and not what went wrong.
                    val cause = generateSequence(failure) { it.cause }.last()
                    SolrResponse.TransportFailure(cause.message ?: cause::class.java.simpleName)
                } else {
                    classify(response)
                }
            }
    }

    /**
     * Turns one HTTP response into an outcome.
     *
     * **The status decides, and the body is only consulted for what it can add.** Solr mirrors its
     * error code into the status line, so a failure is knowable whether or not the body parsed —
     * which matters because a failing response is not always JSON. A mistyped collection is answered
     * by the servlet container with an HTML error page, and a transport that required a parsed body
     * before it would report a failure would turn the most common user mistake into a parse error.
     */
    private fun classify(response: HttpResponse<String>): SolrResponse<JsonNode> {
        val body = SolrJsonDocuments.treeOf(response.body())

        if (response.statusCode() !in 200..299) {
            // Solr's own words where it gave any, and null rather than a substitute where it did not.
            return SolrResponse.SolrError(response.statusCode(), solrMessage(body))
        }

        if (body == null) {
            return SolrResponse.Unrecognized("the response was not JSON")
        }

        val header = body.path("responseHeader")
        // A non-zero status inside a 200 has not been observed, and is classified rather than
        // trusted: Solr mirroring its code into the status line is what the wire-format pass found,
        // not a guarantee it published.
        header.path("status").takeIf { it.isNumber && it.asInt() != 0 }?.let {
            return SolrResponse.SolrError(it.asInt(), solrMessage(body))
        }

        if (header.path("partialResults").takeIf { it.isBoolean }?.asBoolean() == true) {
            return SolrResponse.Partial(
                body,
                header.path("partialResultsDetails").asString("").takeIf { it.isNotEmpty() },
            )
        }
        return SolrResponse.Success(body)
    }

    /**
     * Solr's own message, or null where it gave none.
     *
     * Null rather than a substitute: a mistyped collection is answered by the servlet container with
     * no Solr message at all, and inventing one would put words in Solr's mouth.
     */
    private fun solrMessage(body: JsonNode?): String? =
        body?.path("error")?.path("msg")?.asString("")?.takeIf { it.isNotEmpty() }

    /**
     * The `Authorization` header value, or null where there is no credential to send.
     *
     * Built here rather than by an `Authenticator` so it goes out on the first request. The value is
     * returned rather than logged or stored, and the only place it exists is the request it is put
     * on.
     */
    private fun authorization(username: String?, password: String?): String? {
        if (username.isNullOrEmpty()) return null
        val token = "$username:${password.orEmpty()}"
        return "Basic " + Base64.getEncoder().encodeToString(token.toByteArray(StandardCharsets.UTF_8))
    }
}
