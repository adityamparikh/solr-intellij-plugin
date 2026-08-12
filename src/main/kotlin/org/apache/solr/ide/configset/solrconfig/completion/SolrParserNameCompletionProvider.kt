package org.apache.solr.ide.configset.solrconfig.completion

import com.intellij.codeInsight.completion.CompletionParameters
import com.intellij.codeInsight.completion.CompletionProvider
import com.intellij.codeInsight.completion.CompletionResultSet
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.psi.util.parentOfType
import com.intellij.psi.xml.XmlTag
import com.intellij.psi.xml.XmlText
import com.intellij.util.ProcessingContext
import org.apache.solr.ide.configset.activation.SolrConfigsetFileKind
import org.apache.solr.ide.configset.reading.SolrConfigsetReader
import org.apache.solr.ide.configset.solrconfig.SolrConfigParameters
import org.apache.solr.ide.model.vocabulary.SolrParameterCatalog

/**
 * Offers the query parsers Solr registers, inside a `defType`.
 *
 * **The one parameter *value* in `solrconfig.xml` whose legal set is genuinely closed**, which is what
 * distinguishes it from the values this plugin still declines. A `bf` holds a function query and a `q`
 * holds a query, and neither is enumerable — but `defType` names a parser out of a registry Solr builds
 * in a static initializer, so the list is complete, per line, and each entry has a class behind it to be
 * documented from.
 *
 * **These are registered names, not class names.** `defType` takes `edismax`; the class implementing it
 * is `solr.ExtendedDismaxQParserPlugin`, which is what a `<queryParser>` element's `class` attribute
 * names. The same plugins, written in two places, read from two different populations — and conflating
 * them would offer a class name where Solr wants a registry key and produce a configset that fails to
 * load.
 */
internal class SolrParserNameCompletionProvider : CompletionProvider<CompletionParameters>() {

    override fun addCompletions(
        parameters: CompletionParameters,
        context: ProcessingContext,
        result: CompletionResultSet,
    ) {
        val file = parameters.originalFile
        if (SolrConfigsetFileKind.forFileName(file.name)?.isSolrConfig != true) return
        val model = SolrConfigsetReader.getInstance(file.project).modelFor(file) ?: return

        val text = parameters.position.parentOfType<XmlText>(withSelf = true) ?: return
        val tag = text.parentOfType<XmlTag>() ?: return
        if (SolrConfigParameters.parameterNameOf(tag) !in DEF_TYPE_PARAMETERS) return

        result.addAllElements(
            SolrParameterCatalog.queryParserNamesFor(model.solrVersion).map { entry ->
                LookupElementBuilder.create(entry.name)
                    .withTypeText(entry.owner.substringAfterLast('.'))
                    .withTailText(entry.summary?.let { "  $it" }, true)
            },
        )
    }

    private companion object {
        /**
         * The parameters that hold a parser name.
         *
         * One, and named rather than matched by shape. Solr's own `QueryParsing` declares `defType`
         * beside `type`, which is the *local* parameter spelling — `{!type=dismax}` — and appears in a
         * query string rather than as a parameter of its own, so offering parser names wherever a
         * parameter happened to be called `type` would be offering them in the wrong place.
         */
        val DEF_TYPE_PARAMETERS = setOf("defType")
    }
}
