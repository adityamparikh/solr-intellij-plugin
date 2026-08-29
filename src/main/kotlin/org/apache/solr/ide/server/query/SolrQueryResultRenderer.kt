package org.apache.solr.ide.server.query

import org.apache.solr.ide.SolrBundle

/**
 * Turns a query's answer into the lines shown above its raw JSON.
 *
 * **A summary, not a replacement.** The HTTP Client prints the full response underneath whatever
 * this returns, which is what makes it safe to be lossy here: columns are dropped, cells are
 * truncated, and nothing is actually withheld from the reader — the authoritative text is one scroll
 * away. A presentation that replaced the response would have to be complete, and completeness is
 * exactly what makes raw Solr JSON hard to read in the first place.
 *
 * Pure text in, text out, so what it says can be read in a test rather than in a console.
 */
object SolrQueryResultRenderer {

    /** The widest a cell is allowed to be before it is cut, in characters. */
    const val MAX_CELL_WIDTH = 40

    /**
     * The summary line and table for [result].
     *
     * @param result what the query answered
     * @return the lines to print, without a trailing newline
     */
    fun render(result: SolrQueryResult): String = buildString {
        appendLine(summaryOf(result))
        if (result.hiddenColumns.isNotEmpty()) {
            appendLine(SolrBundle.message("query.hiddenFields", result.hiddenColumns.joinToString(", ")))
        }
        if (result.rows.isNotEmpty()) {
            appendLine()
            append(tableOf(result))
        }
        if (result.explanations.isNotEmpty()) {
            appendLine()
            appendLine()
            append(explanationsOf(result))
        }
    }.trimEnd()

    /**
     * The one line that answers the question the query asked.
     *
     * **Matches and returned rows are stated separately**, because they routinely differ and
     * conflating them is how someone concludes their query found three documents when it found nine
     * thousand and showed three.
     */
    private fun summaryOf(result: SolrQueryResult): String {
        val found = result.numFound
        val shown = result.rows.size
        val time = result.queryTimeMillis
        return when {
            found == null -> SolrBundle.message("query.summary.unknownTotal", shown)
            found == 0L -> SolrBundle.message("query.summary.none", time ?: 0)
            found.toInt() == shown && result.start == 0L ->
                SolrBundle.message("query.summary.all", found, time ?: 0)
            else -> SolrBundle.message(
                "query.summary.window",
                found,
                time ?: 0,
                (result.start ?: 0L) + 1,
                (result.start ?: 0L) + shown,
            )
        }
    }

    /**
     * The documents, as a fixed-width table.
     *
     * Column widths come from the content rather than from a guess, so a table of short values does
     * not sprawl and one of long values still lines up.
     */
    private fun tableOf(result: SolrQueryResult): String {
        val cells = result.rows.map { row -> result.columns.map { truncate(row[it].orEmpty()) } }
        val widths = result.columns.mapIndexed { index, column ->
            maxOf(column.length, cells.maxOfOrNull { it[index].length } ?: 0)
        }

        return buildString {
            appendLine(result.columns.mapIndexed { index, column -> column.padEnd(widths[index]) }
                .joinToString(COLUMN_GAP).trimEnd())
            appendLine(widths.joinToString(COLUMN_GAP) { "-".repeat(it) })
            cells.forEach { row ->
                appendLine(row.mapIndexed { index, cell -> cell.padEnd(widths[index]) }
                    .joinToString(COLUMN_GAP).trimEnd())
            }
        }.trimEnd()
    }

    /**
     * Solr's own scoring explanations, under the document each belongs to.
     *
     * **Passed through rather than reformatted.** Solr returns the explanation already indented as a
     * tree, on both supported lines. Parsing that text back into a structure so it could be
     * re-rendered would be work whose best possible outcome is what Solr already wrote.
     */
    private fun explanationsOf(result: SolrQueryResult): String = buildString {
        result.parsedQuery?.let { appendLine(SolrBundle.message("query.parsedAs", it)) }
        appendLine(SolrBundle.message("query.explanations"))
        result.explanations.forEach { (id, explanation) ->
            appendLine()
            appendLine(SolrBundle.message("query.explanationFor", id))
            explanation.lines()
                .dropWhile { it.isBlank() }
                .forEach { appendLine(if (it.isBlank()) "" else "$EXPLANATION_INDENT$it") }
        }
    }.trimEnd()

    // Cut rather than wrapped, because a wrapped cell destroys the column alignment that makes the
    // table worth having. The full value is in the JSON printed underneath.
    private fun truncate(value: String): String {
        val flattened = value.replace('\n', ' ')
        return if (flattened.length <= MAX_CELL_WIDTH) flattened
        else flattened.take(MAX_CELL_WIDTH - 1) + ELLIPSIS
    }

    private const val COLUMN_GAP = "  "
    private const val EXPLANATION_INDENT = "  "
    private const val ELLIPSIS = "…"
}
