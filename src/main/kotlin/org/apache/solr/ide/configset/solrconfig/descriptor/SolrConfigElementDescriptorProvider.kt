package org.apache.solr.ide.configset.solrconfig.descriptor

import com.intellij.psi.PsiElement
import com.intellij.psi.impl.source.xml.XmlElementDescriptorProvider
import com.intellij.psi.xml.XmlAttribute
import com.intellij.psi.xml.XmlElement
import com.intellij.psi.xml.XmlTag
import com.intellij.xml.XmlAttributeDescriptor
import com.intellij.xml.XmlElementDescriptor
import com.intellij.xml.XmlElementsGroup
import com.intellij.xml.XmlNSDescriptor
import com.intellij.xml.impl.BasicXmlAttributeDescriptor
import com.intellij.xml.impl.schema.AnyXmlElementDescriptor
import org.apache.solr.ide.configset.activation.SolrConfigsetDetector
import org.apache.solr.ide.configset.activation.SolrConfigsetFileKind
import org.apache.solr.ide.configset.reading.SolrConfigsetReader
import org.apache.solr.ide.model.SolrVersionSelection
import org.apache.solr.ide.model.vocabulary.SolrElementArity
import org.apache.solr.ide.model.vocabulary.SolrElementCatalog

/**
 * Owns the element descriptors for a configset's `solrconfig.xml`.
 *
 * **A second provider rather than a widened first one.** The schema's descriptor lives under
 * `configset.schema`, and this file is the other aspect's; a gate widened to serve both would make one
 * aspect the owner of a position the other one also has. They share the generated vocabulary and
 * nothing else.
 *
 * Without this the platform is in schema-less mode, where its answers are guesses drawn from the
 * reader's own file: a second `<requestHandler>` was offered `startup` because the first one carried
 * it, which is a fact about that file rather than about Solr.
 * `SolrConfigAttributeCompletionTest` recorded that echo before this existed, precisely so the change
 * would be visible rather than asserted — it is the same fixture, now asserting the replacement.
 *
 * **Owning a descriptor must not mean validating by absence.** `solrconfig.xml` names plugin classes
 * from outside Solr as a matter of course, so an element the catalog has never heard of is the
 * ordinary case: every unknown child resolves to the platform's permissive descriptor and every
 * attribute name resolves, so nothing here can paint a custom component red.
 *
 * Dumb-aware by not reading an index — the vocabulary is a generated resource and the configset is
 * text.
 */
class SolrConfigElementDescriptorProvider : XmlElementDescriptorProvider {

    /**
     * The descriptor for [tag], or null when this is not a configset's `solrconfig.xml`.
     *
     * @param tag the tag being asked about
     * @return the plugin's descriptor, or null to leave the platform alone
     */
    override fun getDescriptor(tag: XmlTag): XmlElementDescriptor? {
        // Completion runs on a copy whose virtual file detection rejects, so the gate is checked
        // against the original — the same reason the schema provider does, and the same failure if
        // not: the descriptor would vanish during exactly the completion it exists to feed.
        val file = tag.containingFile?.originalFile ?: return null
        if (SolrConfigsetFileKind.forFileName(file.name)?.isSolrConfig != true) return null
        if (!SolrConfigsetDetector.isConfigsetFile(file)) return null
        val version = SolrConfigsetReader.getInstance(file.project).modelFor(file)?.solrVersion
            ?: SolrVersionSelection.DEFAULT
        return SolrConfigTagDescriptor(tag, version)
    }
}

/**
 * The half of [XmlElementDescriptor] that is the same whether the element exists yet or not.
 *
 * **Most of this interface is bookkeeping, and only four members carry a decision.** What an element
 * is named, what it may contain, what it may carry, and what declares it are the questions the two
 * descriptors below answer differently; namespaces, groups, content type and default value are
 * answers this file gives once because Solr's configuration has no notion of any of them. Written
 * twice they were a block of identical overrides in two classes, which is a correction that has to be
 * made in both places or in neither.
 *
 * [elementName] is a property rather than a constructor argument on purpose: a descriptor for an
 * element that exists reads the name off the tag every time, so it stays correct while the name is
 * being typed.
 */
internal abstract class SolrConfigDescriptorBase(
    protected val version: SolrVersionSelection,
) : XmlElementDescriptor {

    /** The element's name, read fresh so an edit in progress does not go stale. */
    protected abstract val elementName: String

    /** The tag this descriptor hangs off — the element itself, or the parent it would go in. */
    protected abstract val anchorTag: XmlTag

    final override fun getQualifiedName(): String = elementName

    final override fun getDefaultName(): String = elementName

    final override fun getName(): String = elementName

    final override fun getName(context: PsiElement?): String = elementName

    /**
     * Never null, which is the permissiveness rule.
     *
     * A known child gets a descriptor like this one; anything else gets one too, so no element is
     * flagged for being somewhere this class did not anticipate. A custom component's element is the
     * ordinary case in this file.
     */
    override fun getElementDescriptor(childTag: XmlTag, contextTag: XmlTag?): XmlElementDescriptor =
        SolrConfigTagDescriptor(childTag, version)

    /**
     * A descriptor for any attribute at all, declared or not.
     *
     * The declared set feeds completion; this feeds validation and resolution, and answering null is
     * how a platform paints an attribute as wrong. The plugin's inspections own that judgement, so
     * every name resolves.
     */
    override fun getAttributeDescriptor(attributeName: String, context: XmlTag?): XmlAttributeDescriptor =
        SolrConfigAttributeDescriptor(attributeName, context ?: anchorTag)

    final override fun getAttributeDescriptor(attribute: XmlAttribute): XmlAttributeDescriptor =
        getAttributeDescriptor(attribute.name, attribute.parent)

    final override fun getNSDescriptor(): XmlNSDescriptor? = null

    final override fun getTopGroup(): XmlElementsGroup? = null

    final override fun getContentType(): Int = XmlElementDescriptor.CONTENT_TYPE_ANY

    final override fun getDefaultValue(): String? = null

    override fun getDeclaration(): PsiElement = anchorTag

    final override fun init(element: PsiElement) = Unit
}

/**
 * What one `solrconfig.xml` element accepts, read from the generated vocabulary.
 *
 * @property tag the element this describes
 * @property version the Solr line the configset targets
 */
internal class SolrConfigTagDescriptor(
    private val tag: XmlTag,
    version: SolrVersionSelection,
) : SolrConfigDescriptorBase(version) {

    override val elementName: String get() = tag.name

    override val anchorTag: XmlTag get() = tag

    /**
     * The path this element sits at, which is also the key its own children are stored under.
     *
     * The root contributes nothing: `<config>` is the document element rather than a step in a path,
     * so an element directly inside it has the empty parent the generator wrote — and `<query>`, one
     * level down, is the parent key `filterCache` was written with.
     */
    private fun pathOf(element: XmlTag): String =
        generateSequence(element) { it.parentTag }
            .toList()
            .dropLast(1)
            .reversed()
            .joinToString("/") { it.name }

    /**
     * The elements Solr accepts here, which is what feeds element-name completion.
     *
     * **Populated, where the schema's descriptor leaves it empty, and the difference is which surface
     * owns the list.** On the schema side a completion contributor offers element names with their
     * summaries, so contributing them here too would double every row. This file has no such
     * contributor, so leaving it empty would replace the platform's guess with silence — offering
     * less than before owning the descriptor, which is the one outcome worse than the guess.
     *
     * Discontinued elements are withheld. Solr still reads them, but only to reject the file or to say
     * it is ignoring them — `<indexDefaults>` and `<mainIndex>` raise `SolrException`, `<nrtMode>` and
     * `<unlockOnStartup>` fail the same way one level down, and `<jmx>` is the one that merely warns.
     * Offering any of them would be completing a configuration that stops the core starting.
     */
    override fun getElementsDescriptors(context: XmlTag?): Array<XmlElementDescriptor> {
        val here = context ?: tag
        return SolrElementCatalog.offerableChildrenOf(pathOf(here), version)
            .filter { it.arity != SolrElementArity.ATTRIBUTE }
            .map { SolrConfigChildDescriptor(it.name, here, version) }
            .toTypedArray()
    }

    /**
     * The attributes Solr reads on this element.
     *
     * This is what replaces the sibling echo. An element the vocabulary does not describe returns
     * empty rather than guessing — the same contract the schema's factory tags take, and what stops
     * the echo returning through this path.
     */
    override fun getAttributesDescriptors(context: XmlTag?): Array<XmlAttributeDescriptor> {
        val here = context ?: tag
        return SolrElementCatalog.childrenOf(pathOf(here), version)
            .filter { it.arity == SolrElementArity.ATTRIBUTE }
            .map { SolrConfigAttributeDescriptor(it.name, here) }
            .toTypedArray()
    }

}

/**
 * An element offered inside another, named before it exists in the file.
 *
 * Distinct from [SolrConfigTagDescriptor] because completion asks for descriptors of elements the
 * reader has not written yet, so there is no tag to anchor to — only the parent it would go in.
 */
private class SolrConfigChildDescriptor(
    override val elementName: String,
    private val anchor: XmlTag,
    version: SolrVersionSelection,
) : SolrConfigDescriptorBase(version) {

    override val anchorTag: XmlTag get() = anchor

    /**
     * Nothing, because an element that does not exist yet has no contents to describe.
     *
     * Once the reader writes it, the descriptor for the real tag answers from the vocabulary.
     */
    override fun getElementsDescriptors(context: XmlTag?): Array<XmlElementDescriptor> =
        XmlElementDescriptor.EMPTY_ARRAY

    override fun getAttributesDescriptors(context: XmlTag?): Array<XmlAttributeDescriptor> =
        XmlAttributeDescriptor.EMPTY

    /**
     * Anchored on the parent rather than on [context], which is the one place these two differ.
     *
     * There is no tag for this element yet, so a context the platform passes belongs to something
     * else; the parent it would go in is the only position that means anything.
     */
    override fun getAttributeDescriptor(attributeName: String, context: XmlTag?): XmlAttributeDescriptor =
        SolrConfigAttributeDescriptor(attributeName, anchor)
}

/**
 * One attribute of a `solrconfig.xml` element.
 *
 * Nothing is required, fixed or enumerated: every judgement about a value belongs to the inspections,
 * which know when not to fire.
 */
private class SolrConfigAttributeDescriptor(
    private val attributeName: String,
    private val anchor: XmlTag,
) : BasicXmlAttributeDescriptor() {

    override fun getName(): String = attributeName

    override fun getDeclaration(): PsiElement = anchor

    override fun init(element: PsiElement) = Unit

    override fun isRequired(): Boolean = false

    override fun isFixed(): Boolean = false

    override fun hasIdType(): Boolean = false

    override fun hasIdRefType(): Boolean = false

    override fun isEnumerated(): Boolean = false

    override fun getEnumeratedValues(): Array<String>? = null

    override fun getDefaultValue(): String? = null

    override fun validateValue(context: XmlElement?, value: String?): String? = null
}
