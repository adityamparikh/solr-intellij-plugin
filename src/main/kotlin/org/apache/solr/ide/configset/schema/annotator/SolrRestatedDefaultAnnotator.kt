package org.apache.solr.ide.configset.schema.annotator

import com.intellij.lang.annotation.AnnotationHolder
import com.intellij.lang.annotation.Annotator
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.editor.colors.CodeInsightColors
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.project.DumbAware
import com.intellij.psi.PsiElement
import com.intellij.psi.xml.XmlAttribute
import org.apache.solr.ide.model.schema.SolrFieldProperties

/**
 * Dims an attribute whose value the element would have had anyway.
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
 * **Two kinds of attribute reach it, and only the first has layers.** A property on a `<field>`,
 * `<dynamicField>` or `<fieldType>` resolves through the type and the schema version; an attribute
 * on a `<filter>`, `<tokenizer>` or `<charFilter>` resolves against one factory's own literal
 * default and nothing else, because an analysis component inherits nothing.
 *
 * Where the value cannot be determined it stays silent, which is
 * [the model's rule][SolrFieldProperties.restatesDefault] rather than this one, on both halves: a
 * type whose class the catalog does not carry makes every type-dependent default a guess, and a
 * factory attribute whose default Solr computes at runtime is recorded as having none rather than
 * guessed at. Dimming on a guess invites a deletion that changes the index.
 *
 * Dumb-aware through the marker interface, which is the mechanism this extension point uses. The
 * whole answer comes from the configset's own files, so no index is read and none needs guarding.
 */
class SolrRestatedDefaultAnnotator : Annotator, DumbAware {

    /**
     * Dims [element] when it is an attribute restating what its element resolves to without it.
     *
     * @param element every element in the file, in turn
     * @param holder collects the annotations produced
     */
    override fun annotate(element: PsiElement, holder: AnnotationHolder) {
        val attribute = element as? XmlAttribute ?: return
        if (!SolrRestatedAttribute.isRestated(attribute)) return

        holder.newSilentAnnotation(HighlightSeverity.INFORMATION)
            .range(attribute)
            .textAttributes(RESTATED_DEFAULT)
            .create()
    }

    /** The colour the dim is drawn in. */
    companion object {

        /**
         * Grey text and nothing else, in every scheme.
         *
         * **Its own key rather than the platform's unused-code one, because that one may underline.**
         * `NOT_USED_ELEMENT_ATTRIBUTES` is grey in the default schemes and grey *with a wave* in High
         * Contrast, and a restated default is correct Solr: an underline reads as a finding, which is
         * what the second sandbox pass reported. So the plugin ships grey-only values for Default and
         * Darcula — every bundled scheme inherits from one of them — and for High Contrast, which
         * inherits from Darcula but must stay as bright as its own grey. The platform key remains the
         * fallback for a third-party scheme, and the colour is still the user's to change.
         */
        @JvmField
        val RESTATED_DEFAULT: TextAttributesKey = TextAttributesKey.createTextAttributesKey(
            "SOLR_RESTATED_DEFAULT",
            CodeInsightColors.NOT_USED_ELEMENT_ATTRIBUTES,
        )
    }
}
