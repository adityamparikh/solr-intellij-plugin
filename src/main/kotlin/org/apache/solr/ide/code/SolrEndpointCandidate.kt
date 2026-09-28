package org.apache.solr.ide.code

/**
 * A Solr server the project's own source or configuration says it talks to, offered as a connection.
 *
 * **Offered, never adopted.** A candidate becomes a connection only when a user presses *Add* and
 * confirms the form it prefills; nothing connects to one on its own. That is why this is a separate
 * type from [SolrEndpointUsage]: a usage says where code names a server, and a candidate is what that
 * resolves to once a framework's configuration has been read — per profile, with its credential.
 *
 * **The password is carried, unlike on [SolrEndpointUsage], and only here.** A usage's password
 * position is usually a reference to somewhere else; a candidate's password is a literal already
 * written in a configuration file the user owns, resolved and not a placeholder. It goes no further
 * than the prefilled form, and reaches the IDE's password safe only if the user ticks the box that
 * says so. It never reaches a project file.
 *
 * @property url the base URL, fully resolved
 * @property username who to connect as, or null where the configuration does not say
 * @property password a literal password written beside the URL, or null where there is none or it
 *   is held somewhere the IDE cannot see
 * @property profile the profile this row resolves under, or null where it applies whatever is active
 * @property active whether that profile is the one currently active, which is the row offered first
 * @property origin where it was read, in words, for the row that offers it
 */
data class SolrEndpointCandidate(
    val url: String,
    val username: String?,
    val password: String?,
    val profile: String?,
    val active: Boolean,
    val origin: String,
)
