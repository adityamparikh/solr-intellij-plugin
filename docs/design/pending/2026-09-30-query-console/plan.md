# Query console, PR 1: the transport a console needs — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Give `SolrHttpTransport` a raw answer, a per-call timeout and a form-encoded POST, so the query console (PR 2) can show exactly what Solr sent, wait out a slow query, and send parameters no URL could carry — while every existing caller behaves exactly as today.

**Architecture:** The private `send` becomes `exchange`, which returns `SolrResponse<SolrRawAnswer>`: status, content type and body as they arrived, or a `TransportFailure` when nothing did. Today's JSON classification moves unchanged into a public pure function, `SolrHttpTransport.classify(answer)`, and `get` and `post` are `exchange` followed by `classify`. A new `postForm` returns the raw answer, and every method takes a `timeout` defaulting to the transport's own.

**Tech Stack:** Kotlin, `java.net.http.HttpClient`, `java.net.URLEncoder`, kotlinx.coroutines, JUnit 4 against an embedded `com.sun.net.httpserver.HttpServer`.

**Spec:** [`design.md`](design.md) beside this file, section *What the transport gains first*; and [NFR-3](../../../../specs/0002-solr-server-integration.md#requirements) ("A per-request timeout (proposed default: ten seconds, overridable per call for the console's potentially slower queries)").

**Scope:** PR 1 of the design's five. PR 2 (the console) is planned when this has merged, against the transport as it actually lands.

## Global Constraints

- **Every public class, function and property in `src/main/kotlin` has KDoc**, or `dokkaGenerate` (bound to `check`) fails naming it. Markdown reference links (`[text][ref]`) do not work in KDoc; use inline `[text](url)`.
- **Kover's 80% line floor** (`koverVerify`, bound to `check`) must hold.
- **Tests that import nothing from the platform are plain JUnit 4** with `@Test` and backtick names; this whole plan's tests are.
- **No new dependencies.** `java.net` only, as today.
- **The client still sets no proxy and no SSL context**, and `connectTimeout` stays the transport's constructor timeout. Only the per-request `HttpRequest.timeout` becomes per call.
- **`runInterruptible(Dispatchers.IO)` stays the mechanism** that sends, so a cancelled caller interrupts the blocking send. `InterruptedException` and `CancellationException` are rethrown, never described.
- **Nothing throws out of the transport.** Every failure is a `SolrResponse` case.
- **Existing callers are not edited.** `SolrServerReader`, `SolrConfigsetWriter`, `SolrDocumentIndexer` and `SolrQueryRunner` compile and behave unchanged, because every new parameter has a default.
- **Commits** use conventional subjects and `git commit -s`, ending with `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.
- **Build:** `./gradlew build` must pass; check its exit code, not its output.

## Review Focus

The five inputs a console will meet that no existing transport test sends, most likely first. Each has its test in the task that owns the code.

1. **A parameter value holding `&`, `+`, `#`, `%`, `=`, spaces or non-ASCII** must reach Solr byte-exact. This is the reason the console exists. → Task 3, `a form value with url-significant characters arrives exactly`.
2. **The same parameter name repeated** (several `fq`) must arrive as separate values, in order. → Task 3, `repeated parameter names arrive as separate values in order`.
3. **An error answered as HTML** (the servlet's 404 for a mistyped collection) must come back raw, with its body and content type, and still classify as `SolrError(404, null)`. → Task 1, `an html 404 classifies as an error without a message` and Task 3, `an html error comes back raw with its body and content type`.
4. **A query slower than the default ten seconds** must be able to arrive when the caller asks for longer, and the default must still cut one off. → Task 2, both tests.
5. **An answer with no `Content-Type` header, or a body that is not valid UTF-8** (`wt=javabin`) must be answered, not thrown. → Task 3, `an answer without a content type is still answered` and `a body that is not utf-8 is still answered`.

---

## File Structure

| File | Change | Responsibility |
|---|---|---|
| `src/main/kotlin/org/apache/solr/ide/server/transport/SolrRawAnswer.kt` | Create | One HTTP answer as it arrived |
| `src/main/kotlin/org/apache/solr/ide/server/transport/SolrHttpTransport.kt` | Modify | `exchange`, public `classify` and `formEncoded`, `postForm`, per-call `timeout` |
| `src/test/kotlin/org/apache/solr/ide/server/transport/SolrAnswerClassificationTest.kt` | Create | `classify` as a pure function |
| `src/test/kotlin/org/apache/solr/ide/server/transport/SolrHttpTransportTest.kt` | Modify | per-call timeout, `postForm` against the embedded server |
| `docs/code-organization.md` | Modify | the `server.transport` paragraph gains the raw answer |

---

### Task 1: The raw answer, and classification as a pure function

**Files:**
- Create: `src/main/kotlin/org/apache/solr/ide/server/transport/SolrRawAnswer.kt`
- Modify: `src/main/kotlin/org/apache/solr/ide/server/transport/SolrHttpTransport.kt:122-245`
- Test: `src/test/kotlin/org/apache/solr/ide/server/transport/SolrAnswerClassificationTest.kt`

**Interfaces:**
- Consumes: `SolrResponse` (`Success`, `Partial`, `SolrError`, `TransportFailure`, `Unrecognized`), `SolrJsonDocuments.treeOf(String): JsonNode?`.
- Produces:
  - `data class SolrRawAnswer(val status: Int, val contentType: String?, val body: String)`
  - `SolrHttpTransport.classify(answer: SolrRawAnswer): SolrResponse<JsonNode>` (companion)
  - private `suspend fun exchange(baseUrl: String, path: String, credential: SolrCredential, timeout: Duration, method: (HttpRequest.Builder) -> HttpRequest.Builder): SolrResponse<SolrRawAnswer>`

- [ ] **Step 1: Write the failing test**

Create `src/test/kotlin/org/apache/solr/ide/server/transport/SolrAnswerClassificationTest.kt`:

```kotlin
package org.apache.solr.ide.server.transport

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Turning an answer into an outcome, without a server.
 *
 * The same rules [SolrHttpTransportTest] exercises over the wire, reachable directly because the
 * query console holds the raw answer and classifies it itself: it shows what Solr sent *and* says
 * what that meant.
 */
class SolrAnswerClassificationTest {

    private fun answer(status: Int, body: String, contentType: String? = "application/json") =
        SolrRawAnswer(status, contentType, body)

    @Test
    fun `a healthy answer is a success carrying the parsed body`() {
        val result = SolrHttpTransport.classify(answer(200, """{"responseHeader":{"status":0},"response":{"numFound":3}}"""))

        assertTrue(result.toString(), result is SolrResponse.Success)
        assertEquals(3, (result as SolrResponse.Success).value.path("response").path("numFound").asInt())
    }

    @Test
    fun `a Solr error keeps Solr's message and code`() {
        val result = SolrHttpTransport.classify(
            answer(400, """{"responseHeader":{"status":400},"error":{"msg":"undefined field categry","code":400}}"""),
        )

        assertEquals(SolrResponse.SolrError(400, "undefined field categry"), result)
    }

    /** The servlet's page for a mistyped collection: the status says it failed, and no message is invented. */
    @Test
    fun `an html 404 classifies as an error without a message`() {
        val result = SolrHttpTransport.classify(answer(404, "<html><body>Not Found</body></html>", "text/html"))

        assertTrue(result.toString(), result is SolrResponse.SolrError)
        assertEquals(404, (result as SolrResponse.SolrError).code)
        assertNull(result.message)
    }

    @Test
    fun `a 200 that is not json is unrecognized`() {
        val result = SolrHttpTransport.classify(answer(200, "<response><lst/></response>", "application/xml"))

        assertTrue(result.toString(), result is SolrResponse.Unrecognized)
    }

    @Test
    fun `a non-zero status inside a 200 is an error`() {
        val result = SolrHttpTransport.classify(answer(200, """{"responseHeader":{"status":500},"error":{"msg":"boom"}}"""))

        assertEquals(SolrResponse.SolrError(500, "boom"), result)
    }

    @Test
    fun `partial results are partial, with Solr's detail`() {
        val result = SolrHttpTransport.classify(
            answer(200, """{"responseHeader":{"status":0,"partialResults":true,"partialResultsDetails":"Limits exceeded!"}}"""),
        )

        assertTrue(result.toString(), result is SolrResponse.Partial)
        assertEquals("Limits exceeded!", (result as SolrResponse.Partial).detail)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests "*.SolrAnswerClassificationTest"`
Expected: compilation fails with `Unresolved reference 'SolrRawAnswer'` and `Unresolved reference 'classify'`.

- [ ] **Step 3: Create the raw answer**

Create `src/main/kotlin/org/apache/solr/ide/server/transport/SolrRawAnswer.kt`:

```kotlin
package org.apache.solr.ide.server.transport

/**
 * One HTTP answer, exactly as it arrived.
 *
 * **What a console shows, before anything is concluded from it.** The plugin's own reads want an
 * outcome — a schema, or why there is none — and get one from [SolrHttpTransport.classify]. A query
 * someone ran wants that too, and also what the server actually sent: an XML or CSV answer, or the
 * error page behind a failure, none of which survives being parsed as JSON.
 *
 * @property status the HTTP status
 * @property contentType the `Content-Type` the server declared, or null where it declared none
 * @property body the body, decoded as text. A binary answer (`wt=javabin`) arrives mangled rather
 *   than refused, since showing it is all a console can do with it
 */
data class SolrRawAnswer(val status: Int, val contentType: String?, val body: String)
```

- [ ] **Step 4: Make `send` return the raw answer, and move classification to the companion**

In `SolrHttpTransport.kt`, replace everything from the KDoc of `send` (line 122) to the end of the file with:

```kotlin
    /**
     * Builds a request, authenticates it, sends it, and returns the answer as it arrived.
     *
     * **Separated from the public methods so that a verb is the only thing a caller adds.** The
     * configset upload, document indexing and the console's form POST are all requests with a body,
     * and everything around the verb — the URI, the credential, the timeout — is identical. Written
     * into each method instead, those would arrive as near-copies free to drift on auth.
     *
     * **Nothing is concluded here.** Whether a 404 is a failure, or a 200 is partial, is [classify]'s
     * to say, so a caller that must show what the server sent can have it untouched.
     *
     * @param timeout how long this one request may take
     * @param method applies the verb, and the body where there is one
     * @return the answer, or a [SolrResponse.TransportFailure] where none arrived
     */
    private suspend fun exchange(
        baseUrl: String,
        path: String,
        credential: SolrCredential,
        timeout: Duration,
        method: (HttpRequest.Builder) -> HttpRequest.Builder,
    ): SolrResponse<SolrRawAnswer> {
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
            runCatching {
                val response = client.send(request, HttpResponse.BodyHandlers.ofString())
                SolrResponse.Success(
                    SolrRawAnswer(
                        status = response.statusCode(),
                        contentType = response.headers().firstValue("Content-Type").orElse(null),
                        body = response.body(),
                    ),
                )
            }.getOrElse { failure ->
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

    // A raw answer classified; a request that got none passes its failure through.
    private fun SolrResponse<SolrRawAnswer>.classified(): SolrResponse<JsonNode> = when (this) {
        is SolrResponse.Success -> classify(value)
        is SolrResponse.Partial -> classify(value)
        is SolrResponse.SolrError -> this
        is SolrResponse.TransportFailure -> this
        is SolrResponse.Unrecognized -> this
    }

    /** Service lookup, and the pure functions a caller holding a raw answer needs. */
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

        /**
         * Turns one answer into an outcome.
         *
         * **The status decides, and the body is only consulted for what it can add.** Solr mirrors
         * its error code into the status line, so a failure is knowable whether or not the body
         * parsed — which matters because a failing response is not always JSON. A mistyped
         * collection is answered by the servlet container with an HTML error page, and a transport
         * that required a parsed body before it would report a failure would turn the most common
         * user mistake into a parse error.
         *
         * @param answer what arrived
         * @return the outcome it amounts to
         */
        fun classify(answer: SolrRawAnswer): SolrResponse<JsonNode> {
            val body = SolrJsonDocuments.treeOf(answer.body)

            if (answer.status !in 200..299) {
                // Solr's own words where it gave any, and null rather than a substitute where it did not.
                return SolrResponse.SolrError(answer.status, solrMessage(body))
            }

            if (body == null) {
                return SolrResponse.Unrecognized("the response was not JSON")
            }

            val header = body.path("responseHeader")
            // A non-zero status inside a 200 has not been observed, and is classified rather than
            // trusted: Solr mirroring its code into the status line is what the wire-format pass
            // found, not a guarantee it published.
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
         * Null rather than a substitute: a mistyped collection is answered by the servlet container
         * with no Solr message at all, and inventing one would put words in Solr's mouth.
         */
        private fun solrMessage(body: JsonNode?): String? =
            body?.path("error")?.path("msg")?.asString("")?.takeIf { it.isNotEmpty() }
    }
}
```

Then change the bodies of `get` and `post` (lines 84-120) to classify what `exchange` returns. `get`:

```kotlin
    suspend fun get(
        baseUrl: String,
        path: String,
        credential: SolrCredential = SolrCredential.None,
    ): SolrResponse<JsonNode> =
        exchange(baseUrl, path, credential, timeout) { it.GET() }.classified()
```

`post`:

```kotlin
    suspend fun post(
        baseUrl: String,
        path: String,
        body: ByteArray,
        contentType: String = OCTET_STREAM,
        credential: SolrCredential = SolrCredential.None,
    ): SolrResponse<JsonNode> =
        exchange(baseUrl, path, credential, timeout) {
            it.header("Content-Type", contentType)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body))
        }.classified()
```

- [ ] **Step 5: Run the new test and the existing transport tests**

Run: `./gradlew test --tests "*.SolrAnswerClassificationTest" --tests "*.SolrHttpTransportTest"`
Expected: PASS. Every existing `SolrHttpTransportTest` case passes unchanged, which is the proof that classification did not move in behaviour, only in place.

- [ ] **Step 6: Commit**

```bash
git add src/main/kotlin/org/apache/solr/ide/server/transport/SolrRawAnswer.kt \
        src/main/kotlin/org/apache/solr/ide/server/transport/SolrHttpTransport.kt \
        src/test/kotlin/org/apache/solr/ide/server/transport/SolrAnswerClassificationTest.kt
git commit -s -F - <<'MSG'
refactor: keep the answer Solr sent, and classify it as a pure function

The transport parsed every answer as JSON before anyone saw it, which is right for the plugin's own
reads and wrong for the query console to come: a console shows what the server sent, and an XML
answer, a CSV one or the HTML page behind a 404 does not survive being parsed as JSON.

The private send is now exchange, returning SolrRawAnswer (status, content type, body) or a
TransportFailure where nothing arrived. The classification rules move unchanged into
SolrHttpTransport.classify, public and pure, so a caller holding a raw answer can still say what it
meant. get and post are exchange followed by classify, so every existing caller behaves as before;
the untouched SolrHttpTransportTest is the evidence, and SolrAnswerClassificationTest pins the rules
without a server.

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
MSG
```

---

### Task 2: A timeout per call

**Files:**
- Modify: `src/main/kotlin/org/apache/solr/ide/server/transport/SolrHttpTransport.kt` (`get`, `post`, the class KDoc's `@property timeout`)
- Test: `src/test/kotlin/org/apache/solr/ide/server/transport/SolrHttpTransportTest.kt`

**Interfaces:**
- Consumes: `exchange(..., timeout: Duration, ...)` from Task 1.
- Produces: `get(baseUrl, path, credential = None, timeout: Duration = this.timeout)` and `post(baseUrl, path, body, contentType = OCTET_STREAM, credential = None, timeout: Duration = this.timeout)`.

- [ ] **Step 1: Write the failing tests**

Add to `SolrHttpTransportTest`, after `a server that never answers is a transport failure`:

```kotlin
    /**
     * A caller that asks for longer gets longer.
     *
     * NFR-3's "overridable per call for the console's potentially slower queries". The transport
     * here defaults to half a second; the server takes one and a half.
     */
    @Test
    fun `a longer timeout for one call lets a slow answer arrive`() {
        val url = given {
            Thread.sleep(1_500)
            respond(it, 200, """{"responseHeader":{"status":0}}""")
        }

        val result = runBlocking {
            SolrHttpTransport(timeout = Duration.ofMillis(500))
                .get(url, "/solr/products/select", timeout = Duration.ofSeconds(5))
        }

        assertTrue(result.toString(), result is SolrResponse.Success)
    }

    /** The default still applies wherever a caller did not ask for another. */
    @Test
    fun `the transport's own timeout still cuts off a call that asked for none`() {
        val url = given {
            Thread.sleep(1_500)
            respond(it, 200, """{"responseHeader":{"status":0}}""")
        }

        val result = runBlocking { SolrHttpTransport(timeout = Duration.ofMillis(500)).get(url, "/solr/products/select") }

        assertTrue(result.toString(), result is SolrResponse.TransportFailure)
    }
```

- [ ] **Step 2: Run them to verify the first fails**

Run: `./gradlew test --tests "*.SolrHttpTransportTest"`
Expected: compilation fails with `No parameter with name 'timeout' found`.

- [ ] **Step 3: Add the parameter**

In `SolrHttpTransport.kt`, give `get` and `post` a last parameter and pass it through. `get`:

```kotlin
    /**
     * Fetches [path] from [baseUrl] and classifies the answer.
     *
     * @param baseUrl the server's base URL, as a connection records it
     * @param path the path to request, beginning with a slash
     * @param credential what to authenticate as
     * @param timeout how long this request may take; the transport's own unless a caller has reason
     *   to wait longer
     * @return the outcome, which never completes exceptionally — every failure is a
     *   [SolrResponse] case, because a caller that must catch to find out what happened will
     *   eventually catch too much
     */
    suspend fun get(
        baseUrl: String,
        path: String,
        credential: SolrCredential = SolrCredential.None,
        timeout: Duration = this.timeout,
    ): SolrResponse<JsonNode> =
        exchange(baseUrl, path, credential, timeout) { it.GET() }.classified()
```

`post`: add `@param timeout how long this request may take` to its KDoc, then:

```kotlin
    suspend fun post(
        baseUrl: String,
        path: String,
        body: ByteArray,
        contentType: String = OCTET_STREAM,
        credential: SolrCredential = SolrCredential.None,
        timeout: Duration = this.timeout,
    ): SolrResponse<JsonNode> =
        exchange(baseUrl, path, credential, timeout) {
            it.header("Content-Type", contentType)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body))
        }.classified()
```

And change the class KDoc's last line to:

```kotlin
 * @property timeout how long one request may take before it becomes a [SolrResponse.TransportFailure],
 *   unless its caller asks for another. Also the time allowed to connect, which is the client's and
 *   the same for every request
```

- [ ] **Step 4: Run the transport tests**

Run: `./gradlew test --tests "*.SolrHttpTransportTest" --tests "*.SolrAnswerClassificationTest"`
Expected: PASS, including both new tests.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/org/apache/solr/ide/server/transport/SolrHttpTransport.kt \
        src/test/kotlin/org/apache/solr/ide/server/transport/SolrHttpTransportTest.kt
git commit -s -F - <<'MSG'
feat: let one request wait longer than the transport's default

NFR-3 asks for a per-request timeout "overridable per call for the console's potentially slower
queries". The timeout was fixed when the transport was built, so a console query taking twelve
seconds would fail at ten with no way for its caller to ask for more.

get and post take a timeout, defaulting to the transport's own, so no existing caller changes. Only
the request timeout is per call; the connect timeout belongs to the client and stays the same for
every request. Tests: a call asking for five seconds receives an answer that takes one and a half,
and a call asking for nothing is still cut off at the transport's half-second.

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
MSG
```

---

### Task 3: A form-encoded POST that returns the raw answer

**Files:**
- Modify: `src/main/kotlin/org/apache/solr/ide/server/transport/SolrHttpTransport.kt` (add `postForm`, and `FORM` and `formEncoded` to the companion; imports `java.net.URLEncoder`, `java.nio.charset.StandardCharsets`)
- Test: `src/test/kotlin/org/apache/solr/ide/server/transport/SolrHttpTransportTest.kt`
- Modify: `docs/code-organization.md:793-802`

**Interfaces:**
- Consumes: `exchange` (Task 1), per-call `timeout` (Task 2).
- Produces (PR 2's console calls these):
  - `suspend fun postForm(baseUrl: String, path: String, parameters: List<Pair<String, String>>, credential: SolrCredential = SolrCredential.None, timeout: Duration = this.timeout): SolrResponse<SolrRawAnswer>`
  - `SolrHttpTransport.FORM: String` = `"application/x-www-form-urlencoded; charset=UTF-8"`
  - `SolrHttpTransport.formEncoded(parameters: List<Pair<String, String>>): String`

- [ ] **Step 1: Write the failing tests**

Add to `SolrHttpTransportTest` (imports: `java.net.URLDecoder`, `java.nio.charset.StandardCharsets`):

```kotlin
    // --- the console's form POST -------------------------------------------------------------------

    private class Seen {
        var body: String? = null
        var contentType: String? = null
        var authorization: String? = null
    }

    private fun postForm(
        parameters: List<Pair<String, String>>,
        credential: SolrCredential = SolrCredential.None,
        handler: (HttpExchange) -> Unit = { respond(it, 200, """{"responseHeader":{"status":0}}""") },
    ): Pair<SolrResponse<SolrRawAnswer>, Seen> {
        val seen = Seen()
        val url = given { exchange ->
            seen.body = exchange.requestBody.readAllBytes().toString(StandardCharsets.UTF_8)
            seen.contentType = exchange.requestHeaders.getFirst("Content-Type")
            seen.authorization = exchange.requestHeaders.getFirst("Authorization")
            handler(exchange)
        }
        val result = runBlocking {
            SolrHttpTransport(timeout = Duration.ofMillis(500)).postForm(url, "/solr/products/select", parameters, credential)
        }
        return result to seen
    }

    private fun decoded(body: String): List<Pair<String, String>> =
        body.split('&').map { pair ->
            val (key, value) = pair.split('=', limit = 2)
            URLDecoder.decode(key, StandardCharsets.UTF_8) to URLDecoder.decode(value, StandardCharsets.UTF_8)
        }

    /** The reason the console exists: a value no URL can carry as typed reaches Solr exactly. */
    @Test
    fun `a form value with url-significant characters arrives exactly`() {
        val value = """name:"A & B" +x #1 100% a=b é"""

        val (_, seen) = postForm(listOf("q" to value))

        assertEquals("application/x-www-form-urlencoded; charset=UTF-8", seen.contentType)
        assertEquals(listOf("q" to value), decoded(checkNotNull(seen.body)))
    }

    @Test
    fun `repeated parameter names arrive as separate values in order`() {
        val parameters = listOf("q" to "*:*", "fq" to "category:books", "fq" to "manufacturer:\"Lucid Press\"")

        val (_, seen) = postForm(parameters)

        assertEquals(parameters, decoded(checkNotNull(seen.body)))
    }

    @Test
    fun `a form post answers with what the server sent`() {
        val (result, _) = postForm(listOf("q" to "*:*", "wt" to "xml")) {
            respond(it, 200, "<response><lst name=\"responseHeader\"/></response>", contentType = "application/xml")
        }

        assertEquals(
            SolrResponse.Success(SolrRawAnswer(200, "application/xml", "<response><lst name=\"responseHeader\"/></response>")),
            result,
        )
    }

    /** A failure is still an answer here: the console shows the page and says what it meant. */
    @Test
    fun `an html error comes back raw with its body and content type`() {
        val page = "<html><body><p>Searching for Solr?</p></body></html>"

        val (result, _) = postForm(listOf("q" to "*:*")) { respond(it, 404, page, contentType = "text/html") }

        assertEquals(SolrResponse.Success(SolrRawAnswer(404, "text/html", page)), result)
        assertEquals(
            SolrResponse.SolrError(404, null),
            SolrHttpTransport.classify((result as SolrResponse.Success).value),
        )
    }

    @Test
    fun `an answer without a content type is still answered`() {
        val (result, _) = postForm(listOf("q" to "*:*")) { exchange ->
            val bytes = "{}".toByteArray()
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }

        assertEquals(SolrResponse.Success(SolrRawAnswer(200, null, "{}")), result)
    }

    /** `wt=javabin` is binary; a console can only show it, and must not fail trying. */
    @Test
    fun `a body that is not utf-8 is still answered`() {
        val (result, _) = postForm(listOf("q" to "*:*", "wt" to "javabin")) { exchange ->
            val bytes = byteArrayOf(0x02, 0xA2.toByte(), 0xE0.toByte(), 0x2E, 0xFF.toByte())
            exchange.responseHeaders.add("Content-Type", "application/octet-stream")
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }

        assertTrue(result.toString(), result is SolrResponse.Success)
        assertEquals(200, (result as SolrResponse.Success).value.status)
    }

    @Test
    fun `a form post sends the credential preemptively`() {
        val (_, seen) = postForm(listOf("q" to "*:*"), SolrCredential.Resolved("solr", "SolrRocks"))

        assertTrue("expected a Basic header, got ${seen.authorization}", seen.authorization?.startsWith("Basic ") == true)
    }

    @Test
    fun `a form post with no stored password never reaches the wire`() {
        var reached = false
        val url = given { exchange ->
            reached = true
            respond(exchange, 200, "{}")
        }

        val result = runBlocking {
            SolrHttpTransport(timeout = Duration.ofMillis(500))
                .postForm(url, "/solr/products/select", listOf("q" to "*:*"), SolrCredential.Missing("solr"))
        }

        assertFalse("no request may be sent for an incomplete credential", reached)
        assertTrue(result.toString(), result is SolrResponse.TransportFailure)
    }

    /** The console cancels a run it no longer wants; the request must stop, not finish unheard. */
    @Test
    fun `cancelling a form post cancels the request`() {
        val url = given { Thread.sleep(30_000) }
        var completed = false

        val elapsed = measureTimeMillis {
            runBlocking {
                val job = launch(Dispatchers.IO) {
                    SolrHttpTransport(timeout = Duration.ofSeconds(30)).postForm(url, "/solr/products/select", listOf("q" to "*:*"))
                    completed = true
                }
                delay(300)
                job.cancelAndJoin()
            }
        }

        assertFalse("the request should have been cancelled, not completed", completed)
        assertTrue("cancelling must interrupt the request, not wait for it; took ${elapsed}ms", elapsed < 5_000)
    }

    @Test
    fun `form encoding escapes keys and values and keeps order`() {
        assertEquals(
            "q=a+%26+b&fq=x%3D1&fq=%C3%A9",
            SolrHttpTransport.formEncoded(listOf("q" to "a & b", "fq" to "x=1", "fq" to "é")),
        )
    }
```

- [ ] **Step 2: Run them to verify they fail**

Run: `./gradlew test --tests "*.SolrHttpTransportTest"`
Expected: compilation fails with `Unresolved reference 'postForm'` and `Unresolved reference 'formEncoded'`.

- [ ] **Step 3: Implement**

In `SolrHttpTransport.kt`, add imports:

```kotlin
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
```

Add after `post`:

```kotlin
    /**
     * POSTs [parameters] form-encoded to [path], and returns the answer as it arrived.
     *
     * **For the query console, and the one method here that does not classify.** A console shows
     * what Solr sent — an XML answer, a CSV one, the error page behind a failure — and says what it
     * meant with [classify] itself. Classifying here would throw away exactly the part it shows.
     *
     * **A form body rather than a query string**, because a query does not fit in a URL for long:
     * several filter queries, a JSON facet or a large boost query pass Jetty's 8 KB request-line
     * limit as a GET. Every Solr search handler reads a form POST as it reads a GET. Each key and
     * value is encoded on its own, which is what lets a value hold `&`, `+`, `#` or `%` and arrive
     * as typed.
     *
     * @param baseUrl the server root
     * @param path the handler to send to, beginning with a slash
     * @param parameters the parameters in the order given; a name may repeat, as `fq` does
     * @param credential what to authenticate as
     * @param timeout how long this request may take
     * @return the answer, or a [SolrResponse.TransportFailure] where none arrived. Never a
     *   [SolrResponse.SolrError]: a status is part of the answer, for the caller to classify
     */
    suspend fun postForm(
        baseUrl: String,
        path: String,
        parameters: List<Pair<String, String>>,
        credential: SolrCredential = SolrCredential.None,
        timeout: Duration = this.timeout,
    ): SolrResponse<SolrRawAnswer> =
        exchange(baseUrl, path, credential, timeout) {
            it.header("Content-Type", FORM)
                .POST(HttpRequest.BodyPublishers.ofString(formEncoded(parameters), StandardCharsets.UTF_8))
        }
```

Add to the companion, after `JSON`:

```kotlin
        /** A form body, which is what a query from the console is. */
        const val FORM: String = "application/x-www-form-urlencoded; charset=UTF-8"

        /**
         * [parameters] as a form body: each key and value encoded on its own, joined in order.
         *
         * @param parameters the parameters; a name may repeat
         * @return the body, in UTF-8's form encoding
         */
        fun formEncoded(parameters: List<Pair<String, String>>): String =
            parameters.joinToString("&") { (key, value) ->
                URLEncoder.encode(key, StandardCharsets.UTF_8) + "=" + URLEncoder.encode(value, StandardCharsets.UTF_8)
            }
```

In the class KDoc, replace the paragraph beginning `**This carries the plugin's own traffic, not the user's.**` with:

```kotlin
 * **Mostly the plugin's own traffic.** Fetching a schema or a cluster status is nobody's authored
 * request — it is a tool window calling out on its own initiative, which is why this is a function
 * returning a result rather than an editor. [postForm] is the exception, for the query console, and
 * it returns what the server sent rather than what the plugin concluded from it.
```

- [ ] **Step 4: Run the transport tests**

Run: `./gradlew test --tests "*.SolrHttpTransportTest" --tests "*.SolrAnswerClassificationTest"`
Expected: PASS.

- [ ] **Step 5: Describe it in code organization**

In `docs/code-organization.md`, after the `server.transport` paragraph ending `would eventually catch too much.`, add:

```markdown
**The answer is kept as well as classified.** `SolrRawAnswer` is status, content type and body as
they arrived, and `SolrHttpTransport.classify` turns one into an outcome. The plugin's own reads get
the outcome; the query console's form POST gets the answer, because it shows what Solr sent — an XML
answer, or the page behind a failure — as well as what that meant.
```

- [ ] **Step 6: Run the full build**

Run: `./gradlew build; echo "exit=$?"`
Expected: `exit=0`, `BUILD SUCCESSFUL`. If `SolrServerContractTest` alone fails because its Solr container did not start, re-run; it has done so before with no code change.

- [ ] **Step 7: Commit**

```bash
git add src/main/kotlin/org/apache/solr/ide/server/transport/SolrHttpTransport.kt \
        src/test/kotlin/org/apache/solr/ide/server/transport/SolrHttpTransportTest.kt \
        docs/code-organization.md
git commit -s -F - <<'MSG'
feat: send a form-encoded query and keep the answer as sent

The query console's request. postForm POSTs its parameters as application/x-www-form-urlencoded
and returns the raw answer, unclassified, because the console shows what Solr sent and classifies
it itself.

A form body rather than a query string: several filter queries, a JSON facet or a large boost query
pass Jetty's 8 KB request-line limit as a GET, and every Solr search handler reads a form POST as it
reads a GET. Each key and value is encoded on its own, which is the point of the console: a value
holding &, +, # or % arrives exactly as typed, where the HTTP Client can only encode a request line
as a whole.

Tests against the embedded server: such a value arrives byte-exact; repeated fq arrive in order; an
XML answer and an HTML 404 come back raw with their content type, and the 404 still classifies as
SolrError(404, null); an answer with no content type, and a binary body, are answered rather than
thrown; the credential is sent preemptively, and a missing password sends nothing; cancelling the
caller interrupts the request. code-organization.md says the transport keeps the answer as well as
classifying it.

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
MSG
```

---

## After the last task

Push the branch and open one PR for the three commits. The PR says it is PR 1 of the query console
design, with no visible change, and names the new API PR 2 will call: `postForm`, `classify`,
`SolrRawAnswer`, and the per-call `timeout`.
