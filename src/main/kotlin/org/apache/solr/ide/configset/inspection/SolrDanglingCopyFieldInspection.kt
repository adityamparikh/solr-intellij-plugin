package org.apache.solr.ide.configset.inspection

import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.psi.PsiElementVisitor
import com.intellij.psi.XmlElementVisitor
import com.intellij.psi.xml.XmlTag
import org.apache.solr.ide.configset.activation.SolrSchemaTags
import org.apache.solr.ide.configset.parsing.SolrConfigsetReader
import org.apache.solr.ide.SolrBundle

/**
 * Reports a `copyField` naming a field that does not exist.
 *
 * The failure this catches is the one the plugin exists for: a `copyField` whose source was renamed
 * or deleted is accepted by the file, accepted by review, and rejected only when Solr next loads
 * the core — by which point the change is deployed. Nothing else in the editor can see it, because
 * the reference is a bare string in an attribute.
 */
class SolrDanglingCopyFieldInspection : LocalInspectionTool() {

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
     * @return a visitor over `copyField` tags, or an empty one outside a configset
     */
    override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean): PsiElementVisitor {
        val model = SolrConfigsetReader.getInstance(holder.project).modelFor(holder.file) ?: return PsiElementVisitor.EMPTY_VISITOR
        return object : XmlElementVisitor() {
            override fun visitXmlTag(tag: XmlTag) {
                if (tag.name != SolrSchemaTags.COPY_FIELD) return
                for (attributeName in SolrSchemaTags.COPY_FIELD_ENDS) {
                    val value = tag.getAttribute(attributeName)?.valueElement ?: continue
                    val name = value.value
                    // A glob source is a pattern over dynamic fields, and whether anything matches
                    // it is not a question this can answer from the schema alone.
                    if (!SolrInspections.isCheckableFieldName(name)) continue
                    if (model.resolve(name) != null) continue
                    SolrInspections.reportOnValue(
                        holder,
                        value,
                        SolrBundle.message("inspection.copyField.dangling", name),
                        SolrInspections.replacementFixes(
                            name,
                            model.fields.keys + model.dynamicFields.keys,
                            SolrBundle.message("quickfix.field.family"),
                        ),
                    )
                }
            }
        }
    }

}
