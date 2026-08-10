package org.apache.solr.ide.configset.navigation

import com.intellij.pom.PomTargetPsiElement
import com.intellij.psi.ElementDescriptionLocation
import com.intellij.psi.ElementDescriptionProvider
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiReference
import com.intellij.psi.impl.source.resolve.reference.impl.providers.FileReference
import com.intellij.psi.xml.XmlElement
import com.intellij.usageView.UsageViewTypeLocation
import com.intellij.usages.impl.rules.UsageType
import com.intellij.usages.impl.rules.UsageTypeProvider
import org.apache.solr.ide.SolrBundle
import org.apache.solr.ide.configset.solrconfig.reference.SolrConfigFieldReference
import org.apache.solr.ide.configset.schema.reference.SolrCopyFieldReference
import org.apache.solr.ide.configset.schema.reference.SolrResourceFileReferenceSet
import org.apache.solr.ide.configset.schema.reference.SolrFieldTypeReference

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
 * Groups a usage by what it is, instead of leaving it unclassified.
 *
 * A configset references a field three ways, and they carry different weight when the question is
 * *what breaks if I change this*: a copy rule feeds one field from another, while a handler
 * parameter in `solrconfig.xml` shapes what a query searches — and it lives in the file the reader
 * is not looking at. Grouping by that is more use than grouping by nothing, which is what
 * *Unclassified* amounts to.
 *
 * **A resource file is the fourth, and it was left out on an assumption that was simply wrong.** The
 * reasoning recorded here was that a `words="stopwords.txt"` points at a file rather than at a
 * declaration, and so would be grouped by the platform's own file rules. There are no such rules for
 * a [FileReference] inside an XML
 * attribute value: nothing claims it, and Find Usages on `stopwords.txt` listed both correct results
 * under *Unclassified*. Found in the sandbox, like the two defects above it — the headless suite
 * asserts what a search returns, and every one of these is about what the returned thing is called.
 *
 * **The reference decides, not a second reading of the PSI.** Asking the element which references it
 * carries reuses the four providers' own judgement about what each position means, so this cannot
 * drift into disagreeing with navigation about what a position is.
 */
class SolrUsageTypeProvider : UsageTypeProvider {

    /**
     * What kind of usage [element] holds, or null when it holds none of this plugin's.
     *
     * **Declines on shape before asking for references**, for the reason the two searchers on this
     * path give: every usage view in the IDE consults this, for every result of every search in
     * every language, and asking an element for its references is the platform running each
     * registered provider over it. Nothing this plugin contributes sits outside XML, so the type
     * check settles the overwhelming majority without that work being done on anybody's behalf.
     *
     * The first reference that is one of this plugin's decides. A resource attribute holding a
     * comma-separated list carries several, and a path several more — one per segment — but they are
     * all the same kind of usage, so which one answers does not matter.
     *
     * @param element the element a usage was found in
     * @return its usage type, or null to leave the platform's grouping alone
     */
    override fun getUsageType(element: PsiElement): UsageType? {
        if (element !is XmlElement) return null
        return element.references.firstNotNullOfOrNull { reference ->
            when {
                reference is SolrFieldTypeReference -> FIELD_TYPE_REFERENCE
                reference is SolrCopyFieldReference -> COPY_FIELD_END
                reference is SolrConfigFieldReference -> HANDLER_PARAMETER
                reference.isSolrResourceFile -> RESOURCE_FILE
                else -> null
            }
        }
    }

    private companion object {
        /** A `<field>` naming the field type being searched for. */
        val FIELD_TYPE_REFERENCE = UsageType(SolrBundle.messagePointer("usageType.fieldTypeReference"))

        /** Either end of a `<copyField>`. */
        val COPY_FIELD_END = UsageType(SolrBundle.messagePointer("usageType.copyFieldEnd"))

        /** A field name inside a request handler's parameter, in the other file. */
        val HANDLER_PARAMETER = UsageType(SolrBundle.messagePointer("usageType.handlerParameter"))

        /** A `<filter>` or `<charFilter>` reading the resource file being searched for. */
        val RESOURCE_FILE = UsageType(SolrBundle.messagePointer("usageType.resourceFile"))
    }
}

/**
 * Whether this is one of the resource-file references this plugin built.
 *
 * Asked of the reference set rather than of the reference, because a
 * [FileReference] is the platform's
 * own class and carries nothing saying who built it. Another plugin may contribute file references
 * into an XML attribute value, and labelling those as Solr's would be the same overreach in the
 * other direction.
 */
private val PsiReference.isSolrResourceFile: Boolean
    get() = this is FileReference && fileReferenceSet is SolrResourceFileReferenceSet
