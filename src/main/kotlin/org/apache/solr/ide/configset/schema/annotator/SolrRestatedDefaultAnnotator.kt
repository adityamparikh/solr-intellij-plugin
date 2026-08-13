package org.apache.solr.ide.configset.schema.annotator

import com.intellij.lang.annotation.AnnotationHolder
import com.intellij.lang.annotation.Annotator
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.editor.colors.CodeInsightColors
import com.intellij.openapi.project.DumbAware
import com.intellij.psi.PsiElement
import com.intellij.psi.xml.XmlAttribute
import org.apache.solr.ide.model.schema.SolrFieldProperties

/**
 * Dims a field attribute whose value the field would have had anyway.
 *
 * **Not an inspection, and the distinction is the whole design.** A restated default is *correct*
 * Solr, so underlining it would be the plugin manufacturing a problem in order to have something to
 * say — the standing rule is that inspections do not fire on correct files. The platform already has
 * an idiom for "true but removable": the dimmed rendering it gives redundant code. This uses it, at
 * information severity and silently, so nothing reaches the Problems view and no problem count moves.
 *
 * **What it claims is narrow on purpose: that deleting the attribute would leave the same field.**
 * Not that it *should* be deleted — a schema author may restate a default precisely to record that
 * they considered it, which is why
 * [the removal intention][org.apache.solr.ide.configset.schema.intention.SolrRemoveRestatedAttributeIntention]
 * is offered rather than applied. The judgement stays with the reader; the plugin supplies only the
 * fact they cannot get by looking.
 *
 * Where the value cannot be determined it stays silent, which is
 * [the model's rule][SolrFieldProperties.restatesDefault] rather than this one: a type whose class
 * the catalog does not carry makes every type-dependent default a guess, and dimming on a guess
 * invites a deletion that changes the index.
 *
 * Dumb-aware through the marker interface, which is the mechanism this extension point uses. The
 * whole answer comes from the configset's own files, so no index is read and none needs guarding.
 */
class SolrRestatedDefaultAnnotator : Annotator, DumbAware {

    /**
     * Dims [element] when it is a field attribute restating what the field resolves to without it.
     *
     * @param element every element in the file, in turn
     * @param holder collects the annotations produced
     */
    override fun annotate(element: PsiElement, holder: AnnotationHolder) {
        val attribute = element as? XmlAttribute ?: return
        if (!SolrRestatedAttribute.isRestated(attribute)) return

        holder.newSilentAnnotation(HighlightSeverity.INFORMATION)
            .range(attribute)
            .textAttributes(CodeInsightColors.NOT_USED_ELEMENT_ATTRIBUTES)
            .create()
    }
}
