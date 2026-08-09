package org.apache.solr.ide.configset.descriptor

import com.intellij.psi.PsiElement
import com.intellij.psi.impl.source.xml.XmlElementDescriptorProvider
import com.intellij.psi.xml.XmlAttribute
import com.intellij.psi.xml.XmlTag
import com.intellij.xml.XmlAttributeDescriptor
import com.intellij.xml.XmlElementDescriptor
import com.intellij.xml.XmlElementsGroup
import com.intellij.xml.XmlNSDescriptor
import com.intellij.xml.impl.BasicXmlAttributeDescriptor
import com.intellij.xml.impl.schema.AnyXmlElementDescriptor
import org.apache.solr.ide.configset.activation.SolrConfigsetDetector
import org.apache.solr.ide.configset.activation.SolrSchemaTags
import org.apache.solr.ide.configset.documentation.SolrSchemaElements
import org.apache.solr.ide.configset.reading.SolrConfigsetReader
import org.apache.solr.ide.model.vocabulary.SolrClassCatalog
import org.apache.solr.ide.model.vocabulary.SolrClassKind
import org.apache.solr.ide.model.schema.SolrFieldProperties
import org.apache.solr.ide.model.SolrVersionSelection

/**
 * Owns the element descriptors for the schema files of a configset.
 *
 * A schema has no XSD, so without this the platform is in schema-less mode, where its answers are
 * guesses: attribute-name completion echoes whatever attributes same-named tags elsewhere in the
 * file happen to carry, which put an ngram filter's `minGramSize` on every `<filter>` in the
 * schema. Owning the descriptor replaces the guess with what the plugin actually knows — the
 * property table for fields and types, the generated catalog for analysis factories.
 *
 * **Owning a descriptor must not mean validating by absence.** Every unknown attribute and child
 * element resolves to a permissive descriptor rather than to null, so the platform never flags
 * what it cannot know: a custom filter accepts attributes the catalog cannot name, and flagging
 * them is [the unknown-attribute inspection's](org.apache.solr.ide.configset.inspection.SolrUnknownAttributeInspection)
 * job precisely because it knows when *not* to fire.
 */
class SolrSchemaElementDescriptorProvider : XmlElementDescriptorProvider {

    /**
     * The descriptor for [tag], or null when the file is not a configset schema or the element is
     * not one the plugin knows.
     *
     * Null is the platform's cue to fall back to its own machinery, so an unrecognized element
     * inside a schema keeps exactly the behaviour it has today.
     *
     * @param tag the tag being asked about
     * @return the plugin's descriptor, or null to leave the platform alone
     */
    override fun getDescriptor(tag: XmlTag): XmlElementDescriptor? {
        // Completion runs on a copy of the file whose virtual file is an in-memory one detection
        // rejects. The original is the file the copy was made from — and is the file itself
        // everywhere else — so the gate is checked against it, or the descriptor would vanish
        // during exactly the completion it exists to feed.
        val file = tag.containingFile?.originalFile ?: return null
        if (!SolrConfigsetDetector.isConfigsetFile(file)) return null
        val virtualFile = file.virtualFile ?: return null
        val kind = SolrConfigsetDetector.kindOf(file.project, virtualFile) ?: return null
        if (!kind.isSchema) return null
        if (!isKnownSchemaTag(tag.name)) return null
        return SolrSchemaTagDescriptor(tag)
    }

    private fun isKnownSchemaTag(name: String): Boolean =
        SolrSchemaElements.forTag(name) != null || name in SolrSchemaTags.ANALYSIS_COMPONENTS
}

/**
 * The descriptor for one schema element, answering with what the plugin knows about it.
 *
 * Attribute knowledge comes from the same three sources completion already reads: the field
 * property table for `field`, `dynamicField` and `fieldType`, the structural-attribute table for
 * `schema`, `copyField` and `analyzer`, and the generated catalog for a factory tag — resolved
 * against the class its own `class` attribute names.
 */
internal class SolrSchemaTagDescriptor(private val tag: XmlTag) : XmlElementDescriptor {

    override fun getQualifiedName(): String = tag.name

    override fun getDefaultName(): String = tag.name

    override fun getName(): String = tag.name

    override fun getName(context: PsiElement?): String = tag.name

    /**
     * Empty on purpose: element-name completion is the completion contributor's, which shows each
     * element's summary. Contributing the same names here as bare descriptors would double every
     * row in the list.
     */
    override fun getElementsDescriptors(context: XmlTag?): Array<XmlElementDescriptor> =
        XmlElementDescriptor.EMPTY_ARRAY

    /**
     * Never null, which is the permissiveness rule. A known child gets a descriptor like this one;
     * anything else gets the platform's accept-everything descriptor, so no element is ever flagged
     * for being somewhere this class did not anticipate.
     */
    override fun getElementDescriptor(childTag: XmlTag, contextTag: XmlTag?): XmlElementDescriptor =
        if (SolrSchemaElements.forTag(childTag.name) != null || childTag.name in SolrSchemaTags.ANALYSIS_COMPONENTS) {
            SolrSchemaTagDescriptor(childTag)
        } else {
            AnyXmlElementDescriptor(null, null)
        }

    /**
     * The attributes this element accepts, from whichever of the three sources owns it.
     *
     * A factory tag whose class the catalog does not know returns empty rather than guessing —
     * same contract as completion, and what stops the sibling-attribute echo from coming back
     * through this path.
     */
    override fun getAttributesDescriptors(context: XmlTag?): Array<XmlAttributeDescriptor> =
        attributeNames().map { SolrSchemaAttributeDescriptor(it, tag) }.toTypedArray()

    /**
     * A descriptor for any attribute at all, declared or not.
     *
     * The declared set feeds completion; this method feeds validation and resolution, and
     * answering null here is how a platform paints an attribute as wrong. The plugin's inspections
     * own that judgement, so every name resolves.
     */
    override fun getAttributeDescriptor(attributeName: String, context: XmlTag?): XmlAttributeDescriptor =
        SolrSchemaAttributeDescriptor(attributeName, tag)

    override fun getAttributeDescriptor(attribute: XmlAttribute): XmlAttributeDescriptor =
        getAttributeDescriptor(attribute.name, attribute.parent)

    override fun getNSDescriptor(): XmlNSDescriptor? = null

    override fun getTopGroup(): XmlElementsGroup? = null

    override fun getContentType(): Int = XmlElementDescriptor.CONTENT_TYPE_ANY

    override fun getDefaultValue(): String? = null

    override fun getDeclaration(): PsiElement = tag

    override fun init(element: PsiElement) = Unit

    private fun attributeNames(): List<String> = when {
        tag.name in SolrSchemaTags.FIELD ->
            listOf("name", "type") + SolrFieldProperties.FOR_FIELD.map { it.name }
        tag.name in SolrSchemaTags.FIELD_TYPE ->
            listOf("name", "class") + SolrFieldProperties.FOR_FIELD_TYPE.map { it.name }
        tag.name in SolrSchemaTags.ANALYSIS_COMPONENTS -> factoryAttributeNames()
        else -> SolrSchemaElements.attributesOf(tag.name).map { it.name }
    }

    /**
     * The attributes of the factory this tag's `class` names, plus `class` itself — or `class`
     * alone when the class is absent or unknown, which is all that can honestly be claimed.
     */
    private fun factoryAttributeNames(): List<String> {
        val className = tag.getAttributeValue("class") ?: return listOf("class")
        val file = tag.containingFile?.originalFile ?: return listOf("class")
        val model = SolrConfigsetReader.getInstance(file.project).modelFor(file) ?: return listOf("class")
        val version = model.luceneMatchVersion?.let { SolrVersionSelection.fromLuceneMatchVersion(it) }
            ?: SolrVersionSelection.DEFAULT
        val kind = when (tag.name) {
            SolrSchemaTags.TOKENIZER -> SolrClassKind.TOKENIZER
            SolrSchemaTags.FILTER -> SolrClassKind.TOKEN_FILTER
            else -> SolrClassKind.CHAR_FILTER
        }
        val entry = SolrClassCatalog.find(className, version)?.takeIf { it.kind == kind }
            ?: return listOf("class")
        return listOf("class") + entry.attributes.map { it.name }
    }
}

/**
 * One attribute on a schema element: never required, never validated.
 *
 * Validation staying with the inspections is the point — they fire as warnings, only where the
 * catalog positively knows better, and this descriptor existing must not add a second, sterner
 * voice saying the same thing.
 */
internal class SolrSchemaAttributeDescriptor(
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

    /** Never an error: value judgement belongs to the inspections, which know when to stay quiet. */
    override fun validateValue(context: com.intellij.psi.xml.XmlElement?, value: String?): String? = null
}
