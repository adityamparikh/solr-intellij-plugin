package org.apache.solr.ide.configset.inspection

import com.intellij.codeInspection.LocalQuickFix
import com.intellij.codeInspection.ProblemDescriptor
import com.intellij.openapi.project.Project
import com.intellij.psi.ElementManipulators
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
     * Substitutes the replacement into the attribute value the problem was reported on.
     *
     * Writes through `ElementManipulators` rather than editing text directly, so the quotes and
     * escaping stay the platform's business and the result is guaranteed to be a well-formed
     * attribute.
     */
    override fun applyFix(project: Project, descriptor: ProblemDescriptor) {
        val value = descriptor.psiElement as? XmlAttributeValue ?: return
        ElementManipulators.handleContentChange(value, replacement)
    }

    /** Fixes are computed from the model, which is not modified, so a preview needs no special case. */
    override fun startInWriteAction(): Boolean = true
}
