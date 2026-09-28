package org.apache.solr.ide.code.spring

import org.apache.solr.ide.code.SolrEndpointCandidate

/**
 * One block of Spring Boot properties that apply together: a file, or one document within one.
 *
 * @property profile the profile it applies under, or null where it applies whatever is active
 * @property properties its keys, flattened to Spring's dotted form, and their values as written
 * @property fileName the file it came from, for saying where a candidate was read
 */
data class SpringPropertySource(
    val profile: String?,
    val properties: Map<String, String>,
    val fileName: String,
)

/**
 * A server as code spells it: a literal, or a `${…}` reference to be resolved.
 *
 * @property url the URL argument as written
 * @property username the username argument as written, or null where the code passes none
 */
data class SpelledEndpoint(
    val url: String,
    val username: String?,
)

/**
 * The Solr servers a Spring Boot application would talk to, one per profile.
 *
 * **Spring's own precedence, and only as much of it as the plan asks for.** Sources arrive in the
 * order Spring applies them, and a later one overrides an earlier one; a profile's row is the default
 * sources with that profile's laid over them — exactly what the application would see with that one
 * profile active. `spring.config.import`, `@PropertySource` and relaxed binding are not modelled, and
 * where one of them would have supplied the URL the answer is that nothing is offered, never that a
 * wrong URL is.
 *
 * **Two ways a key becomes a URL.** The one the plan requires is following: the client bean takes
 * `@Value("${app.solr.url}")`, so `app.solr.url` is resolved whatever it is called. The other is a
 * suggestion, used only where no client bean names a property: any key naming Solr whose value
 * resolves to an http(s) URL, since the spec is clear there is no standard property name. Either way the username and password are the keys beside the URL
 * — `app.solr.username` and `app.solr.password` beside `app.solr.url` — unless the code passes a
 * username reference of its own.
 */
object SpringBootEndpoints {

    /**
     * The candidates [sources] resolve to, the active profile's first.
     *
     * @param sources the application's property sources, in the order Spring applies them
     * @param spelled the servers the project's client beans name; literals are ignored here, since
     *   they need no configuration to resolve
     * @param activeProfiles the profiles the user made active in the IDE, or null to read
     *   `spring.profiles.active` from the configuration
     * @return one candidate per distinct server, account and profile
     */
    fun candidates(
        sources: List<SpringPropertySource>,
        spelled: List<SpelledEndpoint>,
        activeProfiles: Set<String>?,
    ): List<SolrEndpointCandidate> {
        val active = activeProfiles?.takeIf { it.isNotEmpty() } ?: declaredActive(sources)
        val profiles = listOf<String?>(null) + sources.mapNotNull { it.profile }.distinct()
        val rows = profiles.flatMap { profile ->
            candidatesUnder(effective(sources, profile), profile, spelled, profile != null && profile in active)
        }
        // A profile that changes nothing about Solr resolves to the default's row, and one row
        // repeated under every profile name would bury the rows that differ.
        return rows.distinctBy { Triple(it.url, it.username, it.password) }
            .sortedByDescending { it.active }
    }

    private fun candidatesUnder(
        properties: Map<String, String>,
        profile: String?,
        spelled: List<SpelledEndpoint>,
        active: Boolean,
    ): List<SolrEndpointCandidate> {
        fun candidate(url: String, key: String?, usernameReference: String?) = SolrEndpointCandidate(
            url = url,
            username = usernameReference?.let { resolve(it, properties) } ?: key?.let { besideIt(it, properties, USERNAME_KEYS) },
            password = key?.let { besideIt(it, properties, PASSWORD_KEYS) },
            profile = profile,
            active = active,
            origin = ORIGIN,
        )

        val references = spelled.filter { REFERENCE.containsMatchIn(it.url) }
        // Where the code names the property it reads, that is the answer, and guessing from key names
        // as well would offer the pieces a URL is built from — `solr.host` beside
        // `app.solr.url: ${solr.host}/solr` — as servers of their own. The guess is for a project
        // whose client beans say nothing followable.
        if (references.isNotEmpty()) {
            return references.mapNotNull { endpoint ->
                val url = resolve(endpoint.url, properties)?.takeIf(::isUrl) ?: return@mapNotNull null
                candidate(url, REFERENCE.find(endpoint.url)?.groupValues?.get(1), endpoint.username)
            }
        }
        return properties.keys.filter { key -> SOLR in key.lowercase() && !key.startsWith(QUARKUS_PROFILE_PREFIX) }
            .mapNotNull { key ->
                val url = resolve(properties.getValue(key), properties)?.takeIf(::isUrl) ?: return@mapNotNull null
                candidate(url, key, null)
            }
    }

    /** The default sources with [profile]'s laid over them, later sources winning. */
    private fun effective(sources: List<SpringPropertySource>, profile: String?): Map<String, String> =
        buildMap {
            sources.filter { it.profile == null || it.profile == profile }.forEach { putAll(it.properties) }
        }

    private fun declaredActive(sources: List<SpringPropertySource>): Set<String> =
        effective(sources, null)[ACTIVE_PROFILES_KEY]
            ?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }?.toSet()
            .orEmpty()

    /**
     * The value of a key beside [key] — `app.solr.username` beside `app.solr.url` — resolved.
     *
     * @return the resolved value, or null where there is none or it cannot be resolved
     */
    private fun besideIt(key: String, properties: Map<String, String>, names: List<String>): String? {
        val prefix = key.substringBeforeLast('.', "").ifEmpty { return null }
        return names.firstNotNullOfOrNull { properties["$prefix.$it"] }
            ?.let { resolve(it, properties) }
            ?.takeIf { it.isNotEmpty() }
    }

    /**
     * [value] with every `${key}` and `${key:default}` replaced, or null where one cannot be.
     *
     * All or nothing: a URL with one reference left in it is not a URL, and offering the half that
     * did resolve would offer a server that does not exist.
     */
    internal fun resolve(value: String, properties: Map<String, String>, depth: Int = 0): String? {
        if (depth > MAX_INDIRECTION) return null
        var unresolved = false
        val replaced = REFERENCE.replace(value) { match ->
            val key = match.groupValues[1]
            val fallback = match.groups[2]?.value
            val found = properties[key]?.let { resolve(it, properties, depth + 1) } ?: fallback
            if (found == null) unresolved = true
            found.orEmpty()
        }
        return replaced.takeUnless { unresolved }
    }

    private fun isUrl(value: String) = value.startsWith("http://") || value.startsWith("https://")

    /** A `${key}` or `${key:default}` reference; the key is group 1 and the default group 2. */
    private val REFERENCE = Regex("""\$\{([^{}:]+)(?::([^{}]*))?}""")

    private const val ACTIVE_PROFILES_KEY = "spring.profiles.active"
    private const val SOLR = "solr"
    private const val QUARKUS_PROFILE_PREFIX = "%"
    private const val MAX_INDIRECTION = 8
    private const val ORIGIN = "Spring Boot configuration"
    private val USERNAME_KEYS = listOf("username", "user")
    private val PASSWORD_KEYS = listOf("password")
}
