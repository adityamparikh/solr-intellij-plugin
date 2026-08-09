package org.apache.solr.ide.configset.editing

import com.intellij.codeInspection.LocalQuickFix
import com.intellij.codeInspection.ProblemDescriptor
import com.intellij.openapi.project.Project
import com.intellij.psi.ElementManipulators
import com.intellij.psi.xml.XmlAttribute
import com.intellij.psi.xml.XmlAttributeValue
import org.apache.solr.ide.SolrBundle

/**
 * Replaces a name the plugin has just reported as unknown with one that exists.
 *
 * **The inspection already computed the valid set in order to decide.** Reporting that a name is
 * wrong and then discarding the list of right ones leaves the reader to go and find it, which is
 * the difference between an editor that helps and one that complains.
 *
 * One fix per candidate rather than one fix offering a list, because Alt-Enter shows the candidates
 * inline that way — the user reads the alternatives without committing to a dialog, which for a
 * schema with four field types is the whole interaction.
 *
 * @param replacement the name to substitute
 * @param familyText the grouping label shown when several of these are offered together
 */
internal class SolrReplaceNameQuickFix(
    private val replacement: String,
    private val familyText: String,
) : LocalQuickFix {

    override fun getName(): String = SolrBundle.message("quickfix.replaceWith", replacement)

    override fun getFamilyName(): String = familyText

    /**
     * Substitutes the replacement into whichever half of the attribute the problem was reported on.
     *
     * **Both halves, because the inspections report on both.** A wrong *value* is reported on the
     * value element, and a wrong *name* on the name element —
     * [SolrUnknownAttributeInspection][org.apache.solr.ide.configset.inspection.SolrUnknownAttributeInspection]
     * deliberately underlines the name so it does not point at the half that is correct. Handling
     * only the value made every fix that inspection offered a no-op: the menu listed the right
     * spellings, and choosing one changed nothing.
     *
     * The value is written through `ElementManipulators`, so the quotes and escaping stay the
     * platform's business. A name has no manipulator registered and goes through
     * [XmlAttribute.setName], which is the same call rename uses. The value case is tested first
     * because an attribute value's parent is an attribute too, and asking about the parent first
     * would rewrite the name whenever the value was meant.
     */
    override fun applyFix(project: Project, descriptor: ProblemDescriptor) {
        when (val element = descriptor.psiElement) {
            is XmlAttributeValue -> ElementManipulators.handleContentChange(element, replacement)
            else -> (element.parent as? XmlAttribute)?.name = replacement
        }
    }

    /** Fixes are computed from the model, which is not modified, so a preview needs no special case. */
    override fun startInWriteAction(): Boolean = true
}
