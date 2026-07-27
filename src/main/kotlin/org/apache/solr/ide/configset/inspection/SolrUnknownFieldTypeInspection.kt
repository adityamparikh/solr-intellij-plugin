package org.apache.solr.ide.configset.inspection

import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.psi.PsiElementVisitor
import com.intellij.psi.XmlElementVisitor
import com.intellij.psi.xml.XmlTag
import org.apache.solr.ide.configset.parsing.SolrConfigsetReader
import org.apache.solr.ide.SolrBundle
import org.apache.solr.ide.configset.activation.SolrSchemaTags

/**
 * Reports a field whose `type` names a field type the configset does not declare.
 *
 * Solr refuses to load a core with one of these, so unlike a dangling `copyField` it fails loudly
 * — but it fails at deploy time rather than while the file is being written, and the editor
 * currently accepts any string at all. It is also the mistake most easily made by autocompletion
 * offering words from elsewhere in the file.
 */
class SolrUnknownFieldTypeInspection : LocalInspectionTool() {

    /**
     * @param holder collects the problems found
     * @param isOnTheFly whether this is an editor pass rather than a batch run
     * @return a visitor over field declarations, or an empty one outside a configset
     */
    override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean): PsiElementVisitor {
        val model = SolrConfigsetReader.getInstance(holder.project).modelFor(holder.file) ?: return PsiElementVisitor.EMPTY_VISITOR
        return object : XmlElementVisitor() {
            override fun visitXmlTag(tag: XmlTag) {
                if (tag.name !in SolrSchemaTags.FIELD) return
                val value = tag.getAttribute("type")?.valueElement ?: return
                val typeName = value.value
                // A missing type is a different defect with a different message, and reporting it
                // here would put "unknown type ''" in front of the user.
                if (typeName.isEmpty()) return
                if (model.fieldTypes.containsKey(typeName)) return
                SolrInspections.reportOnValue(holder, value, SolrBundle.message("inspection.fieldType.unknown", typeName))
            }
        }
    }

}
