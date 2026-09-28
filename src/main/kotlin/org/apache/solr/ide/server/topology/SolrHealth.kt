package org.apache.solr.ide.server.topology

/**
 * How healthy SolrCloud says a collection or shard is.
 *
 * Solr's own summary from `CLUSTERSTATUS`, taken as reported rather than recomputed from replica
 * states: the server knows things about its replicas this plugin would only be guessing at.
 *
 * **A value, not the word.** The tree draws it as a coloured dot, and a dot needs to know which colour
 * it is; carrying the string would leave every renderer to parse it again.
 */
enum class SolrHealth {

    // Solr's own meanings, from its `ClusterStatus.Health`. A collection reports its worst shard.

    /** Every replica is up, and there is a leader. */
    GREEN,

    /** Some replicas are down — more than half still up — and there is a leader. */
    YELLOW,

    /** Most replicas are down, but some are up and there is a leader. */
    ORANGE,

    /** No leader, or no replica up: some documents cannot be served or written. */
    RED,
    ;

    /** Reading a health word into a value. */
    companion object {

        /**
         * The health [value] names, or null where it names none this plugin knows.
         *
         * **Null is the answer for a word nobody has drawn a colour for**, and it tells the tree to
         * show the word itself. A newer Solr inventing a fifth value is ordinary, and colouring it
         * would be claiming to know how bad it is.
         *
         * @param value the word Solr reported, in any case, or null where it reported none
         * @return the health it names, or null
         */
        fun of(value: String?): SolrHealth? {
            val word = value?.trim().orEmpty()
            return entries.firstOrNull { it.name.equals(word, ignoreCase = true) }
        }
    }
}
