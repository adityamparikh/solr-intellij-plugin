package org.apache.solr.ide.model.query

/**
 * Field names written inside a Solr query expression.
 *
 * **A different grammar from [SolrQueryFields], which is why it is a different object.** `fl` and
 * `qf` hold *lists of names*; `q` and `fq` hold *queries*, where a name appears only as the part
 * before a colon in a fielded clause. `SolrQueryFields.holdsFieldNames("fq")` is false and correctly
 * so — nothing in a solrconfig `fq` is a field list — which left the plugin unable to read
 * `categry:books` at all until this existed.
 *
 * That gap is the demo's planted defect: a typo in a filter query compiles, runs, matches nothing,
 * and is exactly what a reader of Java code needs this to catch.
 *
 * **Precision over recall, deliberately and throughout.** Query syntax is dense with things shaped
 * like a field reference — local parameters, function queries, parameter references, the match-all
 * `*:*` — and every one of them read as a field would produce a warning on correct code. This
 * returns a name only where the text can be nothing else, and stays silent otherwise. A missed
 * reference costs a warning nobody sees; a false one costs the user's trust in every warning after
 * it.
 */
object SolrQueryExpressions {

    /**
     * The field names in [query], in the order they appear, with duplicates kept.
     *
     * @param query a Solr query expression, as written in `q` or `fq`
     * @return the field names it references, empty where none can be read with confidence
     */
    fun fieldNamesIn(query: String): List<String> {
        val names = mutableListOf<String>()
        var index = 0
        var quoted = false
        var tokenStart = 0

        while (index < query.length) {
            val character = query[index]
            when {
                character == '"' -> {
                    quoted = !quoted
                    // A phrase is a value in its entirety, so nothing inside it starts a name and
                    // nothing inside it ends one.
                    tokenStart = index + 1
                }
                quoted -> Unit
                // A local-parameter block declares a parser and its arguments. The names inside it
                // are parameters rather than fields, and reading them reported `qf` as a field.
                character == '{' -> {
                    val close = query.indexOf('}', index)
                    index = if (close < 0) query.length else close
                    tokenStart = index + 1
                }
                character == ':' -> {
                    fieldNameOf(query.substring(tokenStart, index))?.let { names += it }
                    tokenStart = index + 1
                }
                character in CLAUSE_SEPARATORS -> tokenStart = index + 1
                else -> Unit
            }
            index++
        }
        return names
    }

    /**
     * A bare field name, or null where the token before a colon is something else.
     *
     * The exclusions are the same ones [SolrQueryFields] applies to a field list, for the same
     * reason and with one addition: a name must start with a letter or an underscore, because a
     * token starting with anything else in this position is syntax. Solr permits stranger names than
     * this rule allows, and refusing one is silence rather than a false warning.
     */
    private fun fieldNameOf(token: String): String? {
        val name = token.trim().ifEmpty { return null }
        if (!name.first().isLetter() && name.first() != '_') return null
        if (name.any { !it.isLetterOrDigit() && it !in NAME_CHARACTERS }) return null
        return name
    }

    /** What ends one clause and begins the next, outside a phrase. */
    private val CLAUSE_SEPARATORS = charArrayOf(' ', '\t', '\n', '\r', '(', ')', '+', '-', '!', '^')

    /** Characters legal inside a field name besides letters and digits. */
    private val NAME_CHARACTERS = charArrayOf('_', '.', '-')
}
