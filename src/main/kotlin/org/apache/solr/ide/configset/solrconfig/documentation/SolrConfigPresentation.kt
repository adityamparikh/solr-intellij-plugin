package org.apache.solr.ide.configset.solrconfig.documentation

import com.intellij.openapi.util.text.StringUtil
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
     * Escapes a value read out of a configset or a generated resource.
     *
     * Applied to every interpolated value without exception. A parameter name comes from the file the
     * user is editing and a summary comes from Solr's own Javadoc, and neither is trusted to be free of
     * `<` — a summary that was not escaped would render its `<pre>` example as markup and take the rest
     * of the popup with it.
     */
    private fun escape(text: String): String = StringUtil.escapeXmlEntities(text)
}
