package org.apache.solr.ide.configset.solrconfig.completion

import com.intellij.codeInsight.completion.CompletionParameters
import com.intellij.codeInsight.completion.CompletionProvider
import com.intellij.codeInsight.completion.CompletionResultSet
import com.intellij.codeInsight.lookup.LookupElement
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.psi.util.parentOfType
import com.intellij.psi.xml.XmlAttribute
import com.intellij.psi.xml.XmlAttributeValue
import com.intellij.psi.xml.XmlTag
import com.intellij.util.ProcessingContext
import org.apache.solr.ide.configset.activation.SolrConfigsetFileKind
import org.apache.solr.ide.configset.reading.SolrConfigsetReader
import org.apache.solr.ide.configset.solrconfig.SolrConfigParameters
import org.apache.solr.ide.configset.solrconfig.parsing.SolrConfigParser
import org.apache.solr.ide.model.vocabulary.SolrParameterCatalog
import org.apache.solr.ide.model.vocabulary.SolrParameterEntry

/**
 * Offers the request parameters Solr declares, in the `name` of a value tag inside a parameter list.
 *
 * **The question a reader asks before any other, and the one the plugin answered last.** Field-name
 * completion already worked *inside* `<str name="qf">`, which presumes the reader knew to write `qf` —
 * and someone who does not will never meet it in a file that does not already use it. This is the
 * position where the vocabulary itself is learnable, and each row carries what the parameter is for, so
 * the list answers "what may I set here" and "what does that one do" in one gesture.
 *
 * **A list here does not imply the names not on it are wrong.** That is the opposite of the rule the
 * schema's completion follows, and the difference is real: a schema property set is closed, while a
 * custom `SearchComponent` reads parameters of its author's choosing out of the same `SolrParams`. So
 * this offers what Solr declares, contributes nothing about anything else, and no inspection is allowed
 * to read the same list as a membership test.
 *
 * **Scoped to a parameter list, using the same position test the parser and the inspections use.** A
 * `<str name="…">` under an `<updateRequestProcessorChain>` is configuring something that is not a
 * query, and offering `facet.range.gap` there would be offering nonsense.
 */
internal class SolrParameterNameCompletionProvider : CompletionProvider<CompletionParameters>() {

    override fun addCompletions(
        parameters: CompletionParameters,
        context: ProcessingContext,
        result: CompletionResultSet,
    ) {
        val file = parameters.originalFile
        if (SolrConfigsetFileKind.forFileName(file.name)?.isSolrConfig != true) return
        val model = SolrConfigsetReader.getInstance(file.project).modelFor(file) ?: return

        val value = parameters.position.parentOfType<XmlAttributeValue>() ?: return
        val attribute = value.parentOfType<XmlAttribute>() ?: return
        if (attribute.name != "name") return
        val tag = attribute.parentOfType<XmlTag>() ?: return

        // A value tag or an `arr`, and only inside a parameter list. `arr` is included because
        // `<arr name="facet.field">` is how a repeated parameter is written, and its `name` is a
        // parameter name in exactly the same sense.
        if (tag.name !in SolrConfigParser.VALUE_TAGS && tag.name != "arr") return
        if (!SolrConfigParameters.enclosingIsParameterList(tag.parentTag)) return

        // Names already set on this list, which cannot be set twice. Offering one that is present is
        // offering a duplicate Solr would silently take one of.
        val already = tag.parentTag?.subTags.orEmpty()
            .filter { it !== tag }
            .mapNotNullTo(HashSet()) { it.getAttributeValue("name") }

        result.addAllElements(
            SolrParameterCatalog.parametersFor(model.solrVersion)
                .filterNot { it.name in already }
                .map(::lookupElement),
        )
    }

    /**
     * One parameter, with what it is for beside it.
     *
     * The summary is the half worth having: `qf` reads *query and init param for query fields*, which is
     * the sentence that turns a list of abbreviations into something a reader can choose from. Where
     * Solr wrote no comment on the constant — about two in five — the row still carries the declaring
     * interface, because *DisMaxParams* tells a reader which family the parameter belongs to and that is
     * more than the bare name says.
     */
    private fun lookupElement(entry: SolrParameterEntry): LookupElement =
        LookupElementBuilder.create(entry.name)
            .withTypeText(entry.owner.substringAfterLast('.'))
            .withTailText(entry.summary?.let { "  $it" }, true)
}
