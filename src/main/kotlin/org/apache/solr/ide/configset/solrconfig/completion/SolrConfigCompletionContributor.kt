package org.apache.solr.ide.configset.solrconfig.completion

import com.intellij.codeInsight.completion.CompletionContributor
import com.intellij.codeInsight.completion.CompletionType
import com.intellij.patterns.PlatformPatterns
import com.intellij.patterns.XmlPatterns

/**
 * Completes what is answerable in `solrconfig.xml`: a parameter's name, a `defType`, and the field
 * names a parameter holds.
 *
 * Three positions, and they answer three questions a reader asks in this order. **What may I set here**
 * is the `name` of a value tag in a parameter list, and is the one that makes the vocabulary learnable
 * at all. **What may that hold** is `defType`, the single parameter value in this file whose legal set is
 * closed. **Which fields** is the *text* of `<str name="qf">|</str>`, where the answers are the fields
 * the schema declares.
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
        // The parameter *name*, which is the question asked before the field names inside it. Field
        // completion in a `qf` presumes the reader knew to write `qf`; this is where that is learnable.
        extend(
            CompletionType.BASIC,
            PlatformPatterns.psiElement().inside(XmlPatterns.xmlAttributeValue()),
            SolrParameterNameCompletionProvider(),
        )
        // `defType`, the one parameter value in this file whose legal set is closed.
        extend(
            CompletionType.BASIC,
            PlatformPatterns.psiElement().inside(XmlPatterns.xmlText()),
            SolrParserNameCompletionProvider(),
        )
    }
}
