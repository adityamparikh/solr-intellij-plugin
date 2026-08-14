package org.apache.solr.ide.configset.solrconfig.inspection

import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.codeInspection.ProblemHighlightType
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.psi.PsiElementVisitor
import com.intellij.psi.XmlElementVisitor
import com.intellij.psi.xml.XmlTag
import com.intellij.xml.util.XmlTagUtil
import org.apache.solr.ide.SolrBundle
import org.apache.solr.ide.configset.activation.SolrConfigsetFileKind
import org.apache.solr.ide.configset.reading.SolrConfigsetReader
import org.apache.solr.ide.model.vocabulary.SolrElementCatalog

/**
 * Reports an element Solr no longer accepts.
 *
 * **The one rule in this file that reports a defect rather than a risk.** Four of the five retired
 * elements stop the core starting: `<indexDefaults>` and `<mainIndex>` raise `SolrException`, and
 * `<nrtMode>` and `<unlockOnStartup>` reach `assertWarnOrFail` with its `fail` argument set. So a file
 * carrying one is not untidy, it is a file Solr will refuse. `<jmx>` is the exception Solr merely logs
 * about, and it is reported the same way because the message is Solr's own sentence either way.
 *
 * **Solr's wording rather than the plugin's**, because the sentence names the replacement — the
 * message retiring `<indexDefaults>` is what tells a reader to use `<indexConfig>` — and a paraphrase
 * would drop the only part that says what to do next.
 *
 * Nothing is inferred here. An element is reported only where the generated vocabulary carries a
 * retirement notice for it, which is a literal Solr ships beside the code that rejects it.
 */
class SolrDiscontinuedElementInspection : LocalInspectionTool() {

    /**
     * Runs while the project is still indexing.
     *
     * The vocabulary is a generated resource and the element names come from the file's own text, so
     * nothing here consults an index.
     */
    override fun isDumbAware(): Boolean = true

    /**
     * @param holder collects the problems found
     * @param isOnTheFly whether this is an editor pass rather than a batch run
     * @return a visitor over elements, or an empty one outside a configset's `solrconfig.xml`
     */
    override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean): PsiElementVisitor {
        if (SolrConfigsetFileKind.forFileName(holder.file.name)?.isSolrConfig != true) {
            return PsiElementVisitor.EMPTY_VISITOR
        }
        // Declines without a configset for the reason every rule in this file does: a null model is the
        // shape both the detection master switch and a project with no Solr dependency arrive in, and
        // each is the user saying not to treat this file as Solr's.
        val version = SolrConfigsetReader.getInstance(holder.project).modelFor(holder.file)?.solrVersion
            ?: return PsiElementVisitor.EMPTY_VISITOR

        return object : XmlElementVisitor() {
            override fun visitXmlTag(tag: XmlTag) {
                val parent = tag.parentTag ?: return
                val entry = SolrElementCatalog.element(tag.name, pathOf(parent), version) ?: return
                if (entry.isCurrent) return

                // The name in the start tag, not the whole element. A discontinued `<indexDefaults>`
                // holds the configuration a reader still has to move somewhere, so underlining all of
                // it would bury what it says under the report that it is in the wrong place.
                val name = XmlTagUtil.getStartTagNameElement(tag) ?: return
                holder.registerProblem(
                    name,
                    SolrBundle.message("inspection.discontinuedElement.reported", entry.discontinued),
                    ProblemHighlightType.GENERIC_ERROR_OR_WARNING,
                )
            }
        }
    }

    /**
     * The path [element] sits at, which is the key its own children are stored under.
     *
     * The root contributes nothing: `<config>` is the document element rather than a step in a path,
     * so an element directly inside it has the empty parent the generator wrote.
     */
    private fun pathOf(element: XmlTag): String =
        generateSequence(element) { it.parentTag }
            .toList()
            .dropLast(1)
            .reversed()
            .joinToString("/") { it.name }
}
