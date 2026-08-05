package org.apache.solr.ide.configset.reference

import com.intellij.pom.PomTargetPsiElement
import com.intellij.psi.ElementDescriptionLocation
import com.intellij.psi.ElementDescriptionProvider
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiReference
import com.intellij.usageView.UsageViewTypeLocation
import com.intellij.usages.impl.rules.UsageType
import com.intellij.usages.impl.rules.UsageTypeProvider
import org.apache.solr.ide.SolrBundle

/**
 * Tells the platform what a schema declaration is called, rather than letting it guess.
 *
 * **Both of this file's providers exist because of a sandbox pass, and neither defect was visible to
 * the headless suite.** Find Usages on `text_general` returned exactly the right four results, under
 * a header reading *Solr Declaration Target* — [SolrDeclarationTarget]'s own class name, which the
 * platform de-camel-cases and shows the user when nothing better is registered. The same description
 * feeds the rename dialog, so Shift+F6 offered to rename a *Solr Declaration Target*. The tests
 * asserted what the search returned, which was right the whole time; what it looked like is decided
 * a layer further out.
 *
 * Answers only for this plugin's own targets, and only where the platform is asking what *kind* of
 * thing it has. Every other question is left where it was.
 */
class SolrDeclarationDescriptionProvider : ElementDescriptionProvider {

    /**
     * The kind of declaration [element] is, or null when that is not what was asked or not ours.
     *
     * @param element the element being described
     * @param location what the description is wanted for
     * @return the declaration's kind, or null to defer
     */
    override fun getElementDescription(element: PsiElement, location: ElementDescriptionLocation): String? {
        if (location !is UsageViewTypeLocation) return null
        val target = (element as? PomTargetPsiElement)?.target as? SolrDeclarationTarget ?: return null
        return target.kind
    }
}

/**
 * Groups a declaration's usages by what each one is, instead of leaving them unclassified.
 *
 * A configset references a field three ways, and they carry different weight when the question is
 * *what breaks if I change this*: a copy rule feeds one field from another, while a handler
 * parameter in `solrconfig.xml` shapes what a query searches — and it lives in the file the reader
 * is not looking at. Grouping by that is more use than grouping by nothing, which is what
 * *Unclassified* amounts to.
 *
 * **The reference decides, not a second reading of the PSI.** Asking the element which references it
 * carries reuses the four providers' own judgement about what each position means, so this cannot
 * drift into disagreeing with navigation about what a position is.
 */
class SolrUsageTypeProvider : UsageTypeProvider {

    /**
     * What kind of usage [element] holds, or null when it holds none of this plugin's.
     *
     * @param element the element a usage was found in
     * @return its usage type, or null to leave the platform's grouping alone
     */
    override fun getUsageType(element: PsiElement): UsageType? =
        when (element.references.firstOrNull { it.isSolrFieldReference }) {
            is SolrFieldTypeReference -> FIELD_TYPE_REFERENCE
            is SolrCopyFieldReference -> COPY_FIELD_END
            is SolrConfigFieldReference -> HANDLER_PARAMETER
            else -> null
        }

    private companion object {
        /** A `<field>` naming the field type being searched for. */
        val FIELD_TYPE_REFERENCE = UsageType(SolrBundle.messagePointer("usageType.fieldTypeReference"))

        /** Either end of a `<copyField>`. */
        val COPY_FIELD_END = UsageType(SolrBundle.messagePointer("usageType.copyFieldEnd"))

        /** A field name inside a request handler's parameter, in the other file. */
        val HANDLER_PARAMETER = UsageType(SolrBundle.messagePointer("usageType.handlerParameter"))
    }
}

/**
 * Whether this is one of the references that names a field or field type.
 *
 * Excludes the resource-file references, which point at files rather than at declarations and are
 * already grouped perfectly well by the platform's own file rules.
 */
private val PsiReference.isSolrFieldReference: Boolean
    get() = this is SolrFieldTypeReference || this is SolrCopyFieldReference || this is SolrConfigFieldReference
