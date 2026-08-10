package org.apache.solr.ide.configset.solrconfig.inspection

import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.codeInspection.ProblemHighlightType
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.psi.PsiElementVisitor
import com.intellij.psi.XmlElementVisitor
import com.intellij.psi.xml.XmlTag
import org.apache.solr.ide.SolrBundle
import org.apache.solr.ide.configset.activation.SolrConfigsetFileKind
import org.apache.solr.ide.configset.editing.SolrInspections
import org.apache.solr.ide.configset.solrconfig.SolrConfigParameters
import org.apache.solr.ide.configset.reading.SolrConfigsetReader

/**
 * Reports a request-handler parameter in `solrconfig.xml` naming a field the schema does not
 * declare.
 *
 * This is the file boundary the plugin exists to close. A `qf` naming a field that was renamed in
 * the schema is not an error to Solr — the parameter is simply a string, and a query using it
 * returns fewer results, or none, with no warning anywhere. Nothing in either file connects the
 * two, so nothing else can catch it.
 *
 * The precision rules live in [SolrConfigParameters], which maps the parser's idea of a field
 * reference onto positions in the file — the same positions the reference provider makes
 * navigable — and in [SolrInspections], which decides which names a schema could be expected to
 * declare. Both matter more here than in the schema inspections: `fl` in particular is full of
 * syntax that resembles a field name, and every false positive lands on a file that is entirely
 * correct.
 */
class SolrUnknownFieldReferenceInspection : LocalInspectionTool() {

    /**
     * Runs while the project is still indexing.
     *
     * Nothing here consults an index — the model is parsed from the configset's own text — so the
     * platform's default of skipping this until indexing finishes would withhold a working feature
     * for no reason, exactly when a reader is most likely to be opening files for the first time.
     */
    override fun isDumbAware(): Boolean = true

    /**
     * @param holder collects the problems found
     * @param isOnTheFly whether this is an editor pass rather than a batch run
     * @return a visitor over parameter values, or an empty one outside a configset
     */
    override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean): PsiElementVisitor {
        // Only `solrconfig.xml` carries these; running the visitor over a schema would be wasted
        // work on every keystroke.
        if (SolrConfigsetFileKind.forFileName(holder.file.name)?.isSolrConfig != true) {
            return PsiElementVisitor.EMPTY_VISITOR
        }
        val model = SolrConfigsetReader.getInstance(holder.project).modelFor(holder.file) ?: return PsiElementVisitor.EMPTY_VISITOR

        return object : XmlElementVisitor() {
            override fun visitXmlTag(tag: XmlTag) {
                for (occurrence in SolrConfigParameters.fieldNameOccurrences(tag)) {
                    if (!SolrInspections.isCheckableFieldName(occurrence.fieldName)) continue
                    if (model.resolve(occurrence.fieldName) != null) continue
                    holder.registerProblem(
                        tag,
                        SolrBundle.message("inspection.fieldReference.unknown", occurrence.fieldName),
                        ProblemHighlightType.GENERIC_ERROR_OR_WARNING,
                        occurrence.rangeInTag,
                    )
                }
            }
        }
    }
}
