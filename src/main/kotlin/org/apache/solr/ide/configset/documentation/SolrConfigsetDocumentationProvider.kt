package org.apache.solr.ide.configset.documentation

import com.intellij.lang.documentation.AbstractDocumentationProvider
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.editor.Editor
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.util.parentOfType
import com.intellij.psi.xml.XmlAttribute
import com.intellij.psi.xml.XmlAttributeValue
import com.intellij.psi.xml.XmlTag
import org.apache.solr.ide.configset.activation.SolrConfigsetDetector
import org.apache.solr.ide.configset.activation.SolrSchemaTags
import org.apache.solr.ide.model.SolrClassCatalog
import org.apache.solr.ide.model.SolrClassKind
import org.apache.solr.ide.model.SolrField
import org.apache.solr.ide.model.SolrFieldModel
import org.apache.solr.ide.model.SolrFieldProperties
import org.apache.solr.ide.model.SolrFieldProperty
import org.apache.solr.ide.model.SolrFieldType
import org.apache.solr.ide.model.SolrReferenceGuide
import org.apache.solr.ide.model.SolrVersionSelection
import org.apache.solr.ide.configset.parsing.SolrConfigsetReader

/**
 * Quick documentation for fields, field types and `class` attribute values in a configset.
 *
 * Answers the question the Reference Guide cannot: not "what does `omitNorms` mean" in general, but
 * what it is *for this field in this schema*, and whether that value came from the field, from its
 * type, or from Solr's default. The guide is linked for the prose; the resolution is the part only
 * something reading the configset can do.
 *
 * Documentation is offered on attribute *values* rather than through references, because reference
 * resolution has not landed yet and this does not need it: the element under the caret carries
 * enough to answer.
 */
class SolrConfigsetDocumentationProvider : AbstractDocumentationProvider(), DumbAware {

    /**
     * Picks the element to document when the caret is inside a schema attribute value.
     *
     * Without this, quick documentation would need a resolved reference to hang off, and there are
     * none in a configset yet.
     *
     * @param editor the editor the caret is in
     * @param file the file being edited
     * @param contextElement the element under the caret
     * @param targetOffset the caret offset
     * @return the attribute value to document, or null
     */
    override fun getCustomDocumentationElement(
        editor: Editor,
        file: PsiFile,
        contextElement: PsiElement?,
        targetOffset: Int,
    ): PsiElement? {
        if (!SolrConfigsetDetector.isConfigsetFile(file)) return null
        contextElement?.parentOfType<XmlAttributeValue>(withSelf = true)
            ?.takeIf { documentedTarget(it) != null }
            ?.let { return it }
        // A property attribute, from either half of it — the name or the value. `omitNorms="false"`
        // is the obvious place to ask what a property means and what Solr would otherwise have used,
        // and it previously answered with the enclosing element's description instead.
        contextElement?.parentOfType<XmlAttribute>(withSelf = true)
            ?.takeIf { documentedProperty(it) != null }
            ?.let { return it }
        // Falling through to the tag means hovering the element itself answers. Without this, every
        // question the plugin can answer required the caret to be inside an attribute value — a
        // gesture a reader makes only once they already suspect something.
        return contextElement?.parentOfType<XmlTag>()?.takeIf { SolrSchemaElements.forTag(it.name) != null }
    }

    /**
     * The documentation popup for [element], or null when it is not something this documents.
     *
     * @param element the element chosen by [getCustomDocumentationElement]
     * @param originalElement the element originally under the caret
     * @return HTML for the popup, or null
     */
    override fun generateDoc(element: PsiElement, originalElement: PsiElement?): String? {
        if (element is XmlTag) return elementDocumentation(element)
        if (element is XmlAttribute) return propertyDocumentation(element)
        val value = element as? XmlAttributeValue ?: return null
        val file = value.containingFile ?: return null
        if (!SolrConfigsetDetector.isConfigsetFile(file)) return null

        val configset = SolrConfigsetDetector.configsetFor(file) ?: return null
        val model = SolrConfigsetReader.getInstance(file.project).modelFor(configset)
        val version = model.solrVersion

        return when (val target = documentedTarget(value)) {
            is Target.Field -> {
                val field = model.fields[target.name]?.effective
                    ?: model.dynamicFields[target.name]?.effective?.field
                    ?: return null
                val type = model.typeOf(field)
                SolrFieldPresentation.fieldDocumentation(
                    field, type, version, model.schemaVersion, model.traitsOf(type),
                )
            }
            is Target.Type -> {
                val type = model.fieldTypes[target.name]?.effective ?: return null
                SolrFieldPresentation.fieldTypeDocumentation(type, version)
            }
            is Target.SchemaClass -> {
                val entry = SolrClassCatalog.find(target.name, version) ?: return null
                SolrFieldPresentation.classDocumentation(
                    entry,
                    SolrSchemaElements.classSpecifics(entry, model),
                    version,
                )
            }
            null -> null
        }
    }

    /**
     * The Reference Guide page for this element, offered as the popup's external-documentation link.
     *
     * Constructed, never fetched — the browser resolves it if the user asks for it.
     *
     * @param element the element being documented
     * @param originalElement the element originally under the caret
     * @return the external documentation URLs, or null when none applies
     */
    override fun getUrlFor(element: PsiElement?, originalElement: PsiElement?): List<String>? {
        val value = element as? XmlAttributeValue ?: return null
        val file = value.containingFile ?: return null
        if (!SolrConfigsetDetector.isConfigsetFile(file)) return null
        val configset = SolrConfigsetDetector.configsetFor(file) ?: return null
        val version = SolrConfigsetReader.getInstance(file.project).modelFor(configset).solrVersion
        return when (val target = documentedTarget(value)) {
            is Target.Field -> listOf(SolrReferenceGuide.fieldPropertiesPage(version))
            is Target.Type -> listOf(SolrReferenceGuide.fieldTypesPage(version))
            is Target.SchemaClass -> classPage(target.name, version)?.let { listOf(it) }
            null -> null
        }
    }

    /**
     * The popup for a schema element: what it is, and what this particular one does.
     *
     * The second half is the part worth having. What a `copyField` is can be looked up; that *this*
     * rule joins two fields, one of which the schema does not declare, cannot be.
     */
    private fun elementDocumentation(tag: XmlTag): String? {
        val description = SolrSchemaElements.forTag(tag.name) ?: return null
        val model = modelFor(tag) ?: return null
        val attributes = tag.attributes.mapNotNull { a -> a.name.let { n -> a.value?.let { n to it } } }.toMap()
        val field = fieldDeclaredBy(tag, model)
        return SolrFieldPresentation.elementDocumentation(
            description = description,
            specifics = SolrSchemaElements.specifics(tag.name, attributes, model),
            version = model.solrVersion,
            field = field,
            fieldType = field?.let { model.typeOf(it) },
            schemaVersion = model.schemaVersion,
            typeTraits = model.traitsOf(field?.let { model.typeOf(it) }),
        )
    }

    /**
     * The popup for a property attribute, resolved against the field it sits on where there is one.
     *
     * On a `fieldType` the general half is all there is: a type has no "effective value for this
     * field" to report, and inventing one would be asserting something Solr does not.
     */
    private fun propertyDocumentation(attribute: XmlAttribute): String? {
        val property = documentedProperty(attribute) ?: return null
        val tag = attribute.parentOfType<XmlTag>() ?: return null
        val model = modelFor(tag) ?: return null
        val field = fieldDeclaredBy(tag, model)
        return SolrFieldPresentation.propertyDocumentation(
            property = property,
            effective = field?.let {
                val type = model.typeOf(it)
                SolrFieldProperties.resolve(
                    property, it, type, model.schemaVersion, model.traitsOf(type),
                )
            },
            version = model.solrVersion,
            schemaVersion = model.schemaVersion,
            typeClassName = field?.let { model.typeOf(it) }?.className?.takeIf { it.isNotEmpty() },
        )
    }

    /**
     * The property [attribute] names, or null when it is not one — including on a tag that cannot
     * carry properties at all, where `name` on a `copyField` would otherwise look like one.
     */
    private fun documentedProperty(attribute: XmlAttribute): SolrFieldProperty? {
        val tag = attribute.parentOfType<XmlTag>() ?: return null
        if (tag.name !in SolrSchemaTags.FIELD && tag.name !in SolrSchemaTags.FIELD_TYPE) return null
        return SolrFieldProperties.byName(attribute.name)
    }

    /** The model's field for a `field` or `dynamicField` tag, or null for any other element. */
    private fun fieldDeclaredBy(tag: XmlTag, model: SolrFieldModel): SolrField? {
        if (tag.name !in SolrSchemaTags.FIELD) return null
        val name = tag.getAttributeValue("name")?.takeIf { it.isNotEmpty() } ?: return null
        return model.fields[name]?.effective ?: model.dynamicFields[name]?.effective?.field
    }

    private fun modelFor(tag: XmlTag): SolrFieldModel? {
        val file = tag.containingFile ?: return null
        if (!SolrConfigsetDetector.isConfigsetFile(file)) return null
        val configset = SolrConfigsetDetector.configsetFor(file) ?: return null
        return SolrConfigsetReader.getInstance(file.project).modelFor(configset)
    }

    private fun documentedTarget(value: XmlAttributeValue): Target? {
        val attribute = value.parentOfType<XmlAttribute>() ?: return null
        val tag = attribute.parentOfType<XmlTag>() ?: return null
        val name = value.value.takeIf { it.isNotEmpty() } ?: return null
        return when {
            tag.name in SolrSchemaTags.FIELD && attribute.name == "type" -> Target.Type(name)
            tag.name in SolrSchemaTags.FIELD && attribute.name == "name" -> Target.Field(name)
            tag.name in SolrSchemaTags.FIELD_TYPE && attribute.name == "name" -> Target.Type(name)
            attribute.name == "class" && SolrClassKind.forTag(tag.name) != null -> Target.SchemaClass(name)
            else -> null
        }
    }

    /** The guide page for the class [name] refers to, or null when the catalog does not know it. */
    private fun classPage(name: String, version: SolrVersionSelection): String? {
        val entry = SolrClassCatalog.find(name, version) ?: return null
        return SolrReferenceGuide.classPage(entry.kind, entry.className, version)
    }

    private sealed interface Target {
        data class Field(val name: String) : Target
        data class Type(val name: String) : Target
        /** A `class` attribute's value — named `SchemaClass` so it cannot shadow `java.lang.Class`. */
        data class SchemaClass(val name: String) : Target
    }

}
