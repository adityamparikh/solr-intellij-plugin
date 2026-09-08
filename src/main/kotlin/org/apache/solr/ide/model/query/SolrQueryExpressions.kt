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
    fun fieldNamesIn(query: String): List<String> =
        spansIn(query).filter { it.kind == SolrQuerySpanKind.FIELD }.map { query.substring(it.start, it.end) }

    /**
     * Everything in [query] worth telling apart, in the order it appears.
     *
     * **The same scan that reads the names, because there is only one set of rules.** A highlighter
     * that found fields by its own reading would colour names the checks never look at, and leave
     * uncoloured the ones they report — the disagreement visible as a warning on a word that is not
     * highlighted as a field.
     *
     * **No parser, and the exclusions are what stand in for one.** A phrase is opaque, a
     * local-parameter block declares parameters rather than fields, and an operator is recognized
     * only in the spelling Solr actually treats as one. Everything else is left alone, which for a
     * highlighter means uncoloured rather than mis-coloured.
     *
     * @param query a Solr query expression, as written in `q` or `fq`
     * @return the spans, which may be empty
     */
    fun spansIn(query: String): List<SolrQuerySpan> {
        val spans = mutableListOf<SolrQuerySpan>()
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
                    fieldNameOf(query.substring(tokenStart, index))?.let {
                        // Trimmed on the way in, so the span covers the name and not the space
                        // before it.
                        val start = tokenStart + query.substring(tokenStart, index).indexOf(it)
                        spans += SolrQuerySpan(start, start + it.length, SolrQuerySpanKind.FIELD)
                    }
                    tokenStart = index + 1
                }
                character in CLAUSE_SEPARATORS -> {
                    operatorAt(query, tokenStart, index)?.let { spans += it }
                    tokenStart = index + 1
                }
                else -> Unit
            }
            index++
        }
        // The last token ends at the end of the text rather than at a separator.
        if (!quoted) operatorAt(query, tokenStart, query.length)?.let { spans += it }
        return spans.sortedBy { it.start }
    }

    /**
     * The operator spanning `[start, end)`, or null where that token is not one.
     *
     * **Only the spellings Solr reads as operators.** Lucene's boolean operators are uppercase; a
     * lowercase `and` is an ordinary term and colouring it would tell a reader their query does
     * something it does not. The symbolic forms are matched as whole tokens for the same reason a
     * name may contain a hyphen: a `-` inside `some-field` is part of the name, and only one
     * standing alone negates.
     */
    private fun operatorAt(query: String, start: Int, end: Int): SolrQuerySpan? {
        if (start >= end || end > query.length) return null
        val token = query.substring(start, end)
        val trimmed = token.trim()
        if (trimmed.isEmpty() || trimmed !in OPERATORS) return null
        val offset = start + token.indexOf(trimmed)
        return SolrQuerySpan(offset, offset + trimmed.length, SolrQuerySpanKind.OPERATOR)
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

    /**
     * Every spelling Solr reads as a boolean operator.
     *
     * `TO` is here because it joins the ends of a range, which is the same kind of thing to a reader
     * scanning a query even though Lucene's grammar calls it something else.
     */
    private val OPERATORS = setOf("AND", "OR", "NOT", "TO", "&&", "||")

    /** Characters legal inside a field name besides letters and digits. */
    private val NAME_CHARACTERS = charArrayOf('_', '.', '-')
}
