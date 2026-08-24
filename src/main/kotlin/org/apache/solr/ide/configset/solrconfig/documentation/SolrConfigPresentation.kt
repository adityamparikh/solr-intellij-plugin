package org.apache.solr.ide.configset.solrconfig.documentation

import com.intellij.openapi.util.text.StringUtil
import org.apache.solr.ide.model.query.SolrBoostOccurrence
import org.apache.solr.ide.model.vocabulary.SolrParameterEntry

/**
 * The popup HTML for a `solrconfig.xml` parameter and for a `defType` value.
 *
 * Separate from the provider for the same reason the schema's presentation is: the HTML is a pure
 * function of a catalog entry, so it is testable without an editor, and the provider is left holding
 * nothing but the position tests.
 */
internal object SolrConfigPresentation {

    /**
     * What a request parameter is for, and which of Solr's vocabularies it belongs to.
     *
     * **The summary is thin on purpose and absent about two times in five.** It is the first sentence of
     * the Javadoc on the constant Solr declares the name in, read from the artifact published for this
     * exact release — so `qf` reads *query and init param for query fields*, and a parameter Solr never
     * commented shows no sentence at all rather than one this plugin invented. The declaring interface is
     * shown either way, because it is what makes the entry attributable and because *DisMaxParams* tells
     * a reader which family the parameter belongs to.
     *
     * @param entry the parameter
     * @return HTML for the popup
     */
    fun parameterDocumentation(entry: SolrParameterEntry): String = buildString {
        append("<div class='definition'><pre>${escape(entry.name)}</pre></div>")
        append("<div class='content'>")
        append("<p>Solr request parameter.</p>")
        entry.summary?.let { append("<p>${escape(it)}</p>") }
        append("</div>")
        append("<table class='sections'>")
        append("<tr><td valign='top' class='section'>Declared in</td>")
        append("<td valign='top'><code>${escape(entry.owner.substringAfterLast('.'))}</code></td></tr>")
        append("</table>")
    }

    /**
     * What a `defType` value selects, and the class behind it.
     *
     * The class is the useful second half: `edismax` says nothing about what it does, and
     * `ExtendedDismaxQParserPlugin` is what a reader would search for. Its own class Javadoc supplies the
     * sentence, which is why these read fuller than the parameters do.
     *
     * @param entry the registered query parser name
     * @return HTML for the popup
     */
    fun parserNameDocumentation(entry: SolrParameterEntry): String = buildString {
        append("<div class='definition'><pre>${escape(entry.name)}</pre></div>")
        append("<div class='content'>")
        append("<p>Query parser.</p>")
        entry.summary?.let { append("<p>${escape(it)}</p>") }
        append("</div>")
        append("<table class='sections'>")
        append("<tr><td valign='top' class='section'>Implemented by</td>")
        append("<td valign='top'><code>${escape(entry.owner.substringAfterLast('.'))}</code></td></tr>")
        append("</table>")
    }

    /**
     * What the configset resolves the boosted field to.
     *
     * Built by the provider only where the schema declares the field, so a boost on a name the
     * schema does not carry passes null and the popup drops the field half entirely. Repeating the
     * undeclared name would be a second voice on a mistake
     * [the unknown-field inspection][org.apache.solr.ide.configset.solrconfig.inspection.SolrUnknownFieldReferenceInspection]
     * already reports.
     *
     * @property name the field as the parameter spells it
     * @property searchable whether Solr can search it, or null where the schema has not clearly
     *   said — a field type naming a class this build has never seen
     */
    internal data class BoostedField(val name: String, val searchable: Boolean?)

    /**
     * What a `^`-boost does, and to what.
     *
     * **Says what changes rather than only what the syntax is.** A boost of `1` is the default, so
     * writing it changes nothing, and the popup says so — an observation a reader asked for by
     * putting a caret here, which is why it is not an inspection. It stays prose for that reason:
     * the moment it wants a colour it is annotating a correct file.
     *
     * @param occurrence the boost under the caret
     * @param field what the configset resolves the boosted field to, or null when the boost follows
     *   something that is not a declared field
     * @return HTML for the popup
     */
    fun boostDocumentation(occurrence: SolrBoostOccurrence, field: BoostedField?): String = buildString {
        append("<div class='definition'><pre>^${escape(occurrence.boost)}</pre></div>")
        append("<div class='content'>")
        append("<p>Query-time boost.</p>")
        append("<p>${scaleSentence(occurrence.parameterName)}</p>")
        boostValueSentence(occurrence.boost)?.let { append("<p>$it</p>") }
        append("</div>")
        append("<table class='sections'>")
        field?.let {
            append("<tr><td valign='top' class='section'>Boosts</td>")
            append("<td valign='top'><code>${escape(it.name)}</code>${searchableSuffix(it.searchable)}</td></tr>")
        }
        append("<tr><td valign='top' class='section'>In</td>")
        append("<td valign='top'><code>${escape(occurrence.parameterName)}</code></td></tr>")
        append("</table>")
    }

    /**
     * What this parameter's boost scales.
     *
     * Six parameters and five sentences, because the same `^n` means something different in each.
     * `bf` and `boost` are the pair the relevance inspection stays silent on — their values are
     * function queries — and they are answered here anyway: that inspection declines to report a
     * defect, which is not a reason to decline to explain syntax.
     */
    private fun scaleSentence(parameterName: String): String = when (parameterName) {
        "qf" -> "Multiplies the score contributed by a term match on this field."
        "pf" -> "Multiplies the score contributed by a phrase match on this field."
        "pf2" -> "Multiplies the score contributed by a two-word phrase (bigram) match on this field."
        "pf3" -> "Multiplies the score contributed by a three-word phrase (trigram) match on this field."
        // Additive and multiplicative respectively, which is the whole difference between them.
        "bf" -> "Multiplies the value of this function query, which is then <i>added</i> to the score."
        else -> "Multiplies the value of this function query, by which the whole score is then <i>multiplied</i>."
    }

    /**
     * What this particular boost value does, where there is something to say beyond the default.
     *
     * Three cases earn a sentence and the ordinary one earns none: a boost Solr will reject, a boost
     * that has not been written yet, and a boost equal to the default it replaces.
     */
    private fun boostValueSentence(boost: String): String? {
        if (boost.isEmpty()) return "Nothing follows the <code>^</code>. Solr expects a number here."
        val parsed = boost.toFloatOrNull()
            ?: return "<code>${escape(boost)}</code> is not a number, so Solr rejects the query when it parses it."
        if (parsed != 1.0f) return null
        return "This is the default boost of <code>1</code>, so writing it changes nothing."
    }

    /** The resolved half of the field line, silent where the schema has not clearly answered. */
    private fun searchableSuffix(searchable: Boolean?): String = when (searchable) {
        true -> " — searchable, so the boost applies"
        false -> " — not searchable, so there is nothing for the boost to scale"
        null -> ""
    }

    /**
     * Escapes a value read out of a configset or a generated resource.
     *
     * Applied to every interpolated value without exception. A parameter name comes from the file the
     * user is editing and a summary comes from Solr's own Javadoc, and neither is trusted to be free of
     * `<` — a summary that was not escaped would render its `<pre>` example as markup and take the rest
     * of the popup with it.
     */
    private fun escape(text: String): String = StringUtil.escapeXmlEntities(text)
}
