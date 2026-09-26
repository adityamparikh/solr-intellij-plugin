package org.apache.solr.ide.server.query

/**
 * Which collection a request in an `.http` file is querying, read from its request line.
 *
 * **The segment before the handler.** A query body is only ever sent to a query handler, and a
 * handler is one segment — `select`, `query`, or whatever name a configset gave one. What precedes it
 * is the collection, and whatever precedes *that* is the server: a `/solr` context, a proxy's prefix,
 * or the `{{solrUrl}}` the shipped templates write. Reading from the handler end is what makes all
 * three the same case.
 *
 * **A pure function over the request line's text**, so the rule can be stated without an editor. The
 * HTTP Client can substitute variables itself, but only through a substitutor bound to the
 * environment its toolbar has selected, which it does not publish; see [SolrHttpEnvironments] for
 * what is read instead.
 */
object SolrRequestCollection {

    /**
     * The collection [target] addresses, or null where it cannot be known.
     *
     * @param target the request line's target as written, variables and all
     * @param variable the value a variable has, or null where it has none that can be relied on
     * @return the collection, or null where the line names none or names it through a variable with
     *   no reliable value
     */
    fun of(target: String, variable: (String) -> String?): String? {
        val path = pathOf(target.substringBefore('?').substringBefore('#'))
        if (path.size < 2) return null
        val segment = path[path.size - 2]

        val name = WHOLE_VARIABLE.matchEntire(segment)?.groupValues?.get(1)
        val collection = if (name == null) segment else variable(name) ?: return null
        // A value still carrying braces is a reference to something else, or half of one, and either
        // way not a name this can send.
        return collection.takeUnless { it.isEmpty() || "{{" in it || "}}" in it }
    }

    /**
     * The path segments after the server, which is either a scheme and host or a leading variable
     * holding both.
     */
    private fun pathOf(target: String): List<String> {
        val afterServer = when {
            "://" in target -> target.substringAfter("://").substringAfter('/', missingDelimiterValue = "")
            target.startsWith("{{") -> target.substringAfter("}}").removePrefix("/")
            else -> target.removePrefix("/")
        }
        return afterServer.split('/').filter { it.isNotEmpty() }
    }

    private val WHOLE_VARIABLE = Regex("""\{\{\s*([^{}\s]+)\s*}}""")
}
