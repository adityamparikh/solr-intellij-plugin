package org.apache.solr.ide.server.connection

import java.net.URI

/**
 * What is wrong with a connection someone is typing.
 *
 * An enum rather than a message, so the rule and its wording stay separable — the wording is
 * localizable and lives in the bundle, and a test naming a case is not also asserting its English.
 */
enum class SolrConnectionProblem {

    /** No base URL was entered at all. */
    BASE_URL_MISSING,

    /** The base URL is not a URL — it does not parse. */
    BASE_URL_MALFORMED,

    /** The base URL parses but is not an HTTP or HTTPS address of some host. */
    BASE_URL_NOT_HTTP,

    /** A secret was entered with no user to send it as, which cannot authenticate anything. */
    PASSWORD_WITHOUT_USERNAME,
}

/**
 * Whether a connection someone is typing can be saved.
 *
 * **The half that is easy to get wrong is what this accepts, not what it rejects.** Solr is
 * routinely reached at something other than `/solr` — a reverse proxy, a servlet context path, a
 * port-forward — so requiring that suffix would be the form manufacturing a problem out of a
 * deployment it has no business having an opinion on. The rule is therefore the weakest one that
 * still catches a typo: it must be an HTTP or HTTPS address of some host. Everything past the host
 * is the user's business.
 *
 * Pure, and deliberately not a method on a dialog, so the rules can be exercised without one.
 */
object SolrConnectionValidation {

    /**
     * The first problem with the values entered, or null where there is none.
     *
     * @param baseUrl the server root as typed, leading and trailing whitespace included
     * @param username the user to authenticate as, blank where the server needs none
     * @param hasPassword whether a secret has been entered or is already stored
     * @return the problem to report, or null where the connection can be saved
     */
    fun problemIn(baseUrl: String, username: String, hasPassword: Boolean): SolrConnectionProblem? {
        val trimmed = baseUrl.trim()
        return when {
            trimmed.isEmpty() -> SolrConnectionProblem.BASE_URL_MISSING
            !isHttpAddressOfAHost(trimmed) -> problemWith(trimmed)
            hasPassword && username.isBlank() -> SolrConnectionProblem.PASSWORD_WITHOUT_USERNAME
            else -> null
        }
    }

    private fun problemWith(trimmed: String): SolrConnectionProblem =
        // Told apart by whether it parsed at all: "not a URL" and "not a URL that reaches a Solr"
        // are different mistakes, and a user who typed a stray space is helped by hearing which.
        runCatching { URI(trimmed) }
            .fold({ SolrConnectionProblem.BASE_URL_NOT_HTTP }, { SolrConnectionProblem.BASE_URL_MALFORMED })

    private fun isHttpAddressOfAHost(trimmed: String): Boolean =
        runCatching { URI(trimmed) }.getOrNull()?.let { uri ->
            // `host` is null for a URI with no authority — `http:///solr` parses and reaches nothing.
            uri.scheme?.lowercase() in HTTP_SCHEMES && !uri.host.isNullOrEmpty()
        } == true

    private val HTTP_SCHEMES = setOf("http", "https")
}
