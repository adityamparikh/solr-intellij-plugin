package org.apache.solr.ide.server.transport

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import org.apache.solr.ide.server.reading.SolrJsonDocuments
import tools.jackson.databind.JsonNode

/**
 * One request to Solr, and what came back, classified.
 *
 * **`java.net.http.HttpClient`, and nothing wrapped around it.** The specification's parent argues
 * for plain HTTP over embedding a client library, and this is where that lands: the JDK's client,
 * with a per-request timeout so a server that never answers becomes an outcome rather than a hang.
 *
 * **Suspending rather than returning a future, which is what makes it cancellable.** A
 * `CompletableFuture` cannot be stopped by anything the IDE uses to stop work — not a progress
 * indicator, not a closing tool window — and its only consumers, `get` and `join`, block the caller.
 * An API whose natural use from the EDT is the freeze it exists to prevent is the wrong API. The
 * blocking `send` runs on [Dispatchers.IO] instead, so a caller that goes away takes its request with
 * it rather than detaching from one that carries on running.
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
 * **One client per project, released when the project is.** An `HttpClient` owns a selector thread,
 * a worker pool and a connection pool, so building one per request would both leak threads into a
 * long-running IDE and defeat keep-alive against a server being polled. It is also `AutoCloseable`
 * on the JDK this build targets, and the threads it owns inherit the plugin's classloader — so a
 * transport that is never closed is a plugin that cannot be unloaded. Being a project service makes
 * the platform responsible for both: one instance, disposed with the project.
 *
 * @property timeout how long one request may take before it becomes a [SolrResponse.TransportFailure]
 */
@Service(Service.Level.PROJECT)
class SolrHttpTransport(private val timeout: Duration = Duration.ofSeconds(10)) : Disposable {

    /** The platform's constructor for a project service; the timeout is the default. */
    @Suppress("unused")
    constructor(project: Project) : this()

    private val client: HttpClient = HttpClient.newBuilder()
        .connectTimeout(timeout)
        // No `.proxy(...)` and no `.sslContext(...)`: see the class comment. Their absence is the
        // decision, which is why it is written down rather than left looking like an omission.
        .build()

    /** Releases the client's threads and pooled connections when the project closes. */
    override fun dispose() {
        client.close()
    }

    /**
     * Fetches [path] from [baseUrl] and classifies the answer.
     *
     * @param baseUrl the server's base URL, as a connection records it
     * @param path the path to request, beginning with a slash
     * @param credential what to authenticate as
     * @return the outcome, which never completes exceptionally — every failure is a
     *   [SolrResponse] case, because a caller that must catch to find out what happened will
     *   eventually catch too much
     */
    suspend fun get(
        baseUrl: String,
        path: String,
        credential: SolrCredential = SolrCredential.None,
    ): SolrResponse<JsonNode> {
        return send(baseUrl, path, credential) { it.GET() }
    }

    /**
     * POSTs [body] to [path] and classifies the answer.
     *
     * **The body goes raw, under `application/octet-stream`.** A configset upload also accepts
     * `multipart/form-data` — Solr unwraps multipart into a content stream before the handler sees
     * it, and both forms were run against Solr 10.0.0 producing identical results — so the raw form
     * is what this sends, being the one that reaches the same outcome with no multipart encoder to
     * write and keep correct.
     *
     * @param baseUrl the server root
     * @param path the path to request, beginning with a slash
     * @param body the bytes to send
     * @param contentType what the body is. A configset upload is opaque bytes; a Schema API request
     *   is JSON, and telling Solr which is which is cheaper than relying on it to work that out
     * @param credential what to authenticate as
     * @return the outcome, classified exactly as [get]'s is
     */
    suspend fun post(
        baseUrl: String,
        path: String,
        body: ByteArray,
        contentType: String = OCTET_STREAM,
        credential: SolrCredential = SolrCredential.None,
    ): SolrResponse<JsonNode> {
        return send(baseUrl, path, credential) {
            it.header("Content-Type", contentType)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body))
        }
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
    private suspend fun send(
        baseUrl: String,
        path: String,
        credential: SolrCredential,
        method: (HttpRequest.Builder) -> HttpRequest.Builder,
    ): SolrResponse<JsonNode> {
        // An incomplete credential is refused before anything is sent. Solr would reject `user:` as
        // a *wrong* password rather than a missing one, so asking would turn a cleared PasswordSafe
        // entry into an authentication failure against a server that was never properly asked.
        if (credential is SolrCredential.Missing) {
            return SolrResponse.TransportFailure(
                "the connection authenticates as ${credential.username} and no password is stored for it",
            )
        }

        val request = runCatching {
            val builder = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl.trimEnd('/') + path))
                .timeout(timeout)
            credential.authorizationHeader()?.let { builder.header("Authorization", it) }
            method(builder).build()
        }.getOrElse {
            return SolrResponse.TransportFailure(it.message ?: "the address could not be understood")
        }

        // `runInterruptible` rather than a bare `withContext`, and the difference is the whole
        // requirement. Coroutine cancellation is cooperative: a blocking `send` inside a plain
        // `withContext` runs to completion and the caller only learns it was cancelled afterwards,
        // which is a request nobody is waiting for still holding a connection. `runInterruptible`
        // interrupts the thread, and `HttpClient.send` answers an interrupt by throwing.
        return runInterruptible(Dispatchers.IO) {
            runCatching { classify(client.send(request, HttpResponse.BodyHandlers.ofString())) }
                .getOrElse { failure ->
                    // A cancelled caller must cancel the request rather than be told it failed, so
                    // the exception that carries cancellation is rethrown rather than described.
                    if (failure is InterruptedException || failure is CancellationException) throw failure
                    // Otherwise described rather than rethrown, in the plugin's words: Solr never
                    // spoke, so it has no words to quote here.
                    val cause = generateSequence(failure) { it.cause }.last()
                    SolrResponse.TransportFailure(cause.message ?: cause::class.java.simpleName)
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

    /** Service lookup. */
    companion object {

        /** An opaque body, which is what a configset archive is. */
        const val OCTET_STREAM: String = "application/octet-stream"

        /** A JSON body, which is what a Schema API request is. */
        const val JSON: String = "application/json"

        /**
         * The transport for [project].
         *
         * @param project the project whose client and lifetime it shares
         * @return the project-level service
         */
        fun getInstance(project: Project): SolrHttpTransport = project.service()
    }

    /**
     * Solr's own message, or null where it gave none.
     *
     * Null rather than a substitute: a mistyped collection is answered by the servlet container with
     * no Solr message at all, and inventing one would put words in Solr's mouth.
     */
    private fun solrMessage(body: JsonNode?): String? =
        body?.path("error")?.path("msg")?.asString("")?.takeIf { it.isNotEmpty() }

}
