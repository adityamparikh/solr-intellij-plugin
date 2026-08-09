package org.apache.solr.ide.configset.inspection

import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.psi.PsiElementVisitor
import com.intellij.psi.XmlElementVisitor
import com.intellij.psi.xml.XmlTag
import org.apache.solr.ide.SolrBundle
import org.apache.solr.ide.configset.parsing.SolrConfigsetReader
import org.apache.solr.ide.model.vocabulary.SolrAttributeVocabulary
import org.apache.solr.ide.model.SolrVersionSelection

/**
 * Reports an attribute name that the element cannot accept.
 *
 * `indexd="true"` and `maxGramSiz="15"` are errors Solr reports only when the core loads — the
 * schema throws `Invalid field property`, the factory `Unknown parameters` — and a core load may be
 * a production reload long after the edit. Reporting the typo in the editor moves the discovery to
 * the moment the file is open and the fix is a keystroke.
 *
 * **This only fires where the vocabulary is genuinely closed**, which
 * [SolrAttributeVocabulary.closedVocabularyFor] decides and this inspection does not second-guess.
 * A `<fieldType>` is never checked: it delegates to classes its own configuration names, so its
 * attribute list is open by construction. An analysis component whose `class` the catalog does not
 * know is never checked either, which is how a custom plugin class stays unflagged without anything
 * having to recognize it as custom.
 *
 * The rule this deliberately does not follow is *validation by absence*. An attribute missing from
 * a list is only reported where the list is known to be complete, never merely because the plugin
 * failed to find it.
 */
class SolrUnknownAttributeInspection : LocalInspectionTool() {

    /**
     * Runs while the project is still indexing.
     *
     * The vocabulary comes from a hand-maintained table and a generated resource, neither of which
     * is an index, so there is nothing to wait for.
     */
    override fun isDumbAware(): Boolean = true

    /**
     * @param holder collects the problems found
     * @param isOnTheFly whether this is an editor pass rather than a batch run
     * @return a visitor over schema elements, or an empty one outside a configset
     */
    override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean): PsiElementVisitor {
        val model = SolrConfigsetReader.getInstance(holder.project).modelFor(holder.file)
            ?: return PsiElementVisitor.EMPTY_VISITOR
        val version = model.luceneMatchVersion?.let { SolrVersionSelection.fromLuceneMatchVersion(it) }
            ?: SolrVersionSelection.DEFAULT
        return object : XmlElementVisitor() {
            override fun visitXmlTag(tag: XmlTag) {
                val className = tag.getAttributeValue("class")
                val legal = SolrAttributeVocabulary.closedVocabularyFor(tag.name, className, version)
                    ?: return
                for (attribute in tag.attributes) {
                    if (attribute.name in legal) continue
                    // The name element, not the value: the value may well be correct, and
                    // underlining it would point at the half that is not wrong.
                    holder.registerProblem(
                        attribute.nameElement,
                        SolrBundle.message("inspection.attribute.unknown", attribute.name, tag.name),
                        *SolrInspections.replacementFixes(
                            attribute.name,
                            legal,
                            SolrBundle.message("quickfix.attribute.family"),
                        ),
                    )
                }
            }
        }
    }
}
