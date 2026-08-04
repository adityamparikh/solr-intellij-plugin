package org.apache.solr.ide.configset.reference

import com.intellij.pom.PomDeclarationSearcher
import com.intellij.pom.PomNamedTarget
import com.intellij.pom.PomTarget
import com.intellij.psi.PsiElement
import com.intellij.psi.DelegatePsiTarget
import com.intellij.psi.util.parentOfType
import com.intellij.psi.xml.XmlAttribute
import com.intellij.psi.xml.XmlAttributeValue
import com.intellij.psi.xml.XmlTag
import com.intellij.util.Consumer
import org.apache.solr.ide.configset.activation.SolrConfigsetDetector
import org.apache.solr.ide.configset.activation.SolrConfigsetFileKind
import org.apache.solr.ide.configset.activation.SolrSchemaTags

/**
 * Makes a schema declaration something the platform will search from.
 *
 * Navigation ran one way before this existed. Every reference in this package is anchored at a use
 * site and resolves to the declaration's `name` attribute value, and `ReferencesSearch` traverses
 * those edges backwards perfectly well — but Find Usages never got that far, because nothing turned
 * a caret sitting on the declaration into something to search *for*.
 *
 * `TargetElementUtilBase` accepts a caret three ways: a reference at that offset, a `PsiNamedElement`
 * whose text offset coincides, or a [PomTarget] from a searcher like this one. A schema declaration
 * is none of the first two and cannot be made either. [XmlAttributeValue] is platform PSI this plugin
 * does not own, and targeting the enclosing [XmlTag] instead is the mistake `SolrSchemaPsi` already
 * documents — for a tag `getName()` means the *tag name*, so the target of `<field name="description">`
 * would be the string `field`. That leaves the third door, which is an extension point rather than an
 * interface on someone else's PSI.
 *
 * **The delegation is load-bearing rather than a convenience.** The target wraps the very
 * [XmlAttributeValue] that [SolrSchemaPsi] hands back, because that identity is what `isReferenceTo`
 * compares. A target pointing anywhere else would make Find Usages and Ctrl-click disagree about
 * which references exist, which is the one thing this step must not do. Rename inherits that identity
 * when Step 8 comes to it — a target whose name is the string the user means is most of what rename
 * needs — but the renaming itself, and the fixtures that prove it, stay there.
 *
 * Dumb-aware like everything else on this path, and the same promise about data sources: it reads
 * schema PSI and the detector, and contacts nothing.
 */
class SolrDeclarationSearcher : PomDeclarationSearcher() {

    /**
     * Offers a target when [element] is the `name` of a `field`, `dynamicField` or `fieldType`
     * declaration in a schema of a detected configset.
     *
     * **The checks are ordered cheapest first, and that ordering is the performance design.** This
     * runs on every caret movement in every XML file in the project, so the PSI shape — which is a
     * cast and two string comparisons — decides the overwhelmingly common no before the detector is
     * consulted at all. `name` is the commonest attribute in XML; without the tag check this would
     * offer a Solr target inside a `pom.xml`.
     *
     * A caret on the attribute *name* rather than its value never reaches here, because the element
     * the platform hands over is then not an [XmlAttributeValue]. It does not follow that the
     * position answers nothing: the platform resolves an attribute name to its enclosing tag through
     * the descriptor machinery, and did so before this existed. That answer is *what attribute is
     * this*, which is not this searcher's question and not its to remove.
     *
     * @param element the element at the caret
     * @param offsetInElement the caret's offset within it
     * @param consumer collects the targets found here
     */
    override fun findDeclarationsAt(element: PsiElement, offsetInElement: Int, consumer: Consumer<in PomTarget>) {
        val value = element as? XmlAttributeValue ?: return
        val attribute = value.parentOfType<XmlAttribute>() ?: return
        if (attribute.name != NAME_ATTRIBUTE) return
        val tag = attribute.parentOfType<XmlTag>() ?: return
        if (tag.name !in SolrSchemaTags.FIELD && tag.name !in SolrSchemaTags.FIELD_TYPE) return
        if (value.value.isEmpty()) return

        // Only now the two checks that touch anything beyond this element's own parents.
        if (SolrConfigsetFileKind.forFileName(value.containingFile.name)?.isSchema != true) return
        if (!SolrConfigsetDetector.isConfigsetFile(value.containingFile)) return

        consumer.consume(SolrDeclarationTarget(value))
    }

    private companion object {
        /** The attribute a declaration writes its name in. */
        const val NAME_ATTRIBUTE = "name"
    }
}

/**
 * A schema declaration, as the platform's target vocabulary rather than as PSI.
 *
 * Delegates to the declaration's `name` [XmlAttributeValue] — the same element every reference in
 * this package resolves to, which is what stops Find Usages and Ctrl-click disagreeing.
 *
 * **It carries a name because the search needs a word to look for.** `ReferencesSearch` picks
 * candidates out of the word index before asking any reference to confirm itself, and the word it
 * asks for is the target's name. A nameless target would send the search looking for nothing and
 * return an empty list, which reads as *this declaration is unused* — the failure this plugin's
 * posture exists to prevent.
 *
 * The platform's ready-made `RenameableDelegatePsiTarget` cannot be used here: it requires a
 * `PsiNamedElement`, and an [XmlAttributeValue] is not one. That is the same fact that made a
 * declaration searcher necessary in the first place, met a second time one layer down. Rename is
 * Step 8's, and the rename-specific behaviour it needs belongs there with its own fixtures.
 */
internal class SolrDeclarationTarget(private val value: XmlAttributeValue) :
    DelegatePsiTarget(value), PomNamedTarget {

    /** The declared name, as the schema spells it. */
    override fun getName(): String = value.value
}
