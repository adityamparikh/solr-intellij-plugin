package org.apache.solr.ide.configset.inspection

import com.intellij.codeInspection.LocalQuickFix
import com.intellij.codeInspection.ProblemHighlightType
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.psi.ElementManipulators
import com.intellij.psi.xml.XmlAttributeValue

/**
 * Shared ground rules for the configset inspections.
 *
 * **These are where the zero-false-positive requirement gets teeth.** A wrong warning on a correct
 * file is the thing that gets a plugin uninstalled, and Solr's configuration is full of syntax that
 * looks like a field name without being one. Every rule here exists because something legal would
 * otherwise be underlined in red.
 */
internal object SolrInspections {

    /**
     * Whether [name] is a field name the schema could be expected to declare.
     *
     * Excludes the names Solr answers for itself. `score` is computed per result and appears in
     * every second `fl`; `_version_` and `_root_` are managed by Solr; a name containing a wildcard
     * is a pattern rather than a reference, and a dynamic field may satisfy it in ways this cannot
     * confirm. Flagging any of them would be a warning on a correct file.
     */
    fun isCheckableFieldName(name: String): Boolean =
        name.isNotEmpty() && name !in SOLR_SUPPLIED_FIELDS && '*' !in name && !name.startsWith("[")



    /**
     * Quick-fixes replacing [wrong] with each of [candidates], closest spelling first.
     *
     * Ordered by edit distance because the overwhelmingly common cause is a typo, and a reader
     * scanning Alt-Enter should meet `description` before `category` when they typed `descriptoin`.
     * Capped so that a schema with eighty fields does not answer a typo with eighty menu items —
     * past a handful the list stops being a suggestion and becomes a directory.
     *
     * @param wrong the name that was reported
     * @param candidates every name that would have been valid
     * @param familyText the grouping label for the fixes
     * @return the fixes, best first
     */
    fun replacementFixes(wrong: String, candidates: Collection<String>, familyText: String): Array<LocalQuickFix> =
        candidates.asSequence()
            .sortedWith(compareBy({ editDistance(wrong.lowercase(), it.lowercase()) }, { it }))
            .take(MAX_SUGGESTIONS)
            .map { SolrReplaceNameQuickFix(it, familyText) as LocalQuickFix }
            .toList()
            .toTypedArray()

    /** Levenshtein distance, used only to rank suggestions. */
    private fun editDistance(a: String, b: String): Int {
        var previous = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            val current = IntArray(b.length + 1)
            current[0] = i
            for (j in 1..b.length) {
                val substitution = previous[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1
                current[j] = minOf(substitution, previous[j] + 1, current[j - 1] + 1)
            }
            previous = current
        }
        return previous[b.length]
    }

    /** How many replacements Alt-Enter offers before the list stops being useful. */
    private const val MAX_SUGGESTIONS = 6

    /**
     * Reports [message] on the text *inside* an attribute's quotes.
     *
     * An attribute value element spans its quotes, so registering on the element underlines
     * `"manufacturer"` rather than `manufacturer`. The difference is small and reads as sloppiness
     * in the one place the plugin is asking to be believed.
     *
     * @param holder the collector to report to
     * @param value the attribute value element
     * @param message the user-facing message
     */
    fun reportOnValue(
        holder: ProblemsHolder,
        value: XmlAttributeValue,
        message: String,
        fixes: Array<LocalQuickFix> = LocalQuickFix.EMPTY_ARRAY,
    ) {
        holder.registerProblem(
            value,
            message,
            ProblemHighlightType.GENERIC_ERROR_OR_WARNING,
            ElementManipulators.getValueTextRange(value),
            *fixes,
        )
    }

    /**
     * Names Solr supplies without the schema declaring them.
     *
     * `score` is the one that matters — it is in more `fl` parameters than not. The others are
     * Solr-managed fields that a schema may or may not spell out, and warning about them would be
     * wrong in the case where Solr adds them itself.
     *
     * `_docid_` is here for a different reason than the rest: it is not a field at all, managed or
     * otherwise, but a sort term Solr's `SortSpecParsing` answers itself with the internal Lucene
     * document id. `sort="_docid_ asc"` is correct and no schema declares it — the exact shape of
     * warning this list exists to prevent. Its `fl` spelling, `[docid]`, is already excluded by the
     * bracket rule in [isCheckableFieldName], which is why only the sort form needs naming here.
     */
    private val SOLR_SUPPLIED_FIELDS =
        setOf("score", "_docid_", "_version_", "_root_", "_text_", "_nest_path_")
}
