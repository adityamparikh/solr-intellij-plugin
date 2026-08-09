package org.apache.solr.ide.configset.solrconfig.completion

import com.intellij.codeInsight.completion.CompletionContributor
import com.intellij.codeInsight.completion.CompletionType
import com.intellij.patterns.PlatformPatterns
import com.intellij.patterns.XmlPatterns

/**
 * Completes the field names a `solrconfig.xml` handler parameter can hold.
 *
 * The one answerable position in `solrconfig.xml` that is neither an attribute value nor a tag
 * name: the *text* of `<str name="qf">|</str>`, where the legal answers are the fields the schema
 * declares.
 *
 * Separate from
 * [SolrSchemaCompletionContributor][org.apache.solr.ide.configset.schema.completion.SolrSchemaCompletionContributor]
 * because the two answer in different files and at different kinds of position, and neither needs
 * to know the other exists.
 */
class SolrConfigCompletionContributor : CompletionContributor() {

    /**
     * Runs while the project is still indexing.
     *
     * The field names come from the configset's own text rather than from an index, so withholding
     * them until indexing finishes would disable a working feature for no gain.
     */
    override fun isDumbAware(): Boolean = true

    init {
        extend(
            CompletionType.BASIC,
            PlatformPatterns.psiElement().inside(XmlPatterns.xmlText()),
            SolrParameterFieldCompletionProvider(),
        )
    }
}
