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
import org.apache.solr.ide.model.SolrClassAttribute
import org.apache.solr.ide.model.SolrClassCatalog
import org.apache.solr.ide.model.SolrClassEntry
import org.apache.solr.ide.model.SolrAttributeMeanings
import org.apache.solr.ide.model.SolrClassKind
import org.apache.solr.ide.model.SolrField
import org.apache.solr.ide.model.SolrFieldModel
import org.apache.solr.ide.model.SolrFieldProperties
import org.apache.solr.ide.model.SolrFieldProperty
import org.apache.solr.ide.model.SolrReferenceGuide
import org.apache.solr.ide.model.SolrVersionSelection
import org.apache.solr.ide.configset.parsing.SolrConfigsetReader

/**
 * Quick documentation for fields, field types, factory tags, `class` values and factory attributes
 * in a configset.
 *
 * Answers the question the Reference Guide cannot: not "what does `omitNorms` mean" in general, but
 * what it is *for this field in this schema*, and whether that value came from the field, from its
 * type, or from Solr's default. The guide is linked for the prose; the resolution is the part only
 * something reading the configset can do. The same shape applies to an analysis factory: the guide
 * lists what `EdgeNGramFilterFactory` accepts, and only the tag under the caret can say what this
 * particular filter resolves to once Solr fills in the defaults.
 *
 * Factory attributes are the same contract applied to a different source. Javadoc is written per
 * class, not per attribute, so there is no per-argument prose anywhere in this design to surface —
 * only what the catalog can prove: which class reads the name, what type it accepts, and a default
 * or required marker where bytecode recorded one. Anything beyond that stays silent rather than
 * being guessed from a name. A factory *tag* answers the neighbouring question — not what one
 * attribute is, but what this instance resolves to once Solr has filled in every default.
 *
 * Documentation is offered on attribute *values*, attribute *names* and the tags themselves rather
 * than through references, because reference resolution has not landed yet and this does not need
 * it: the element under the caret carries enough to answer.
 */
class SolrConfigsetDocumentationProvider : AbstractDocumentationProvider(), DumbAware {

    /**
     * Picks the element to document when the caret is inside a schema attribute value or name, or
     * anywhere in a tag that carries one of them.
     *
     * Without this, quick documentation would need a resolved reference to hang off, and there are
     * none in a configset yet.
     *
     * @param editor the editor the caret is in
     * @param file the file being edited
     * @param contextElement the element under the caret
     * @param targetOffset the caret offset
     * @return the attribute value, attribute or tag to document, or null
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
        // A factory attribute is the same gesture on a different vocabulary: hovering `minGramSize`
        // asks which class reads it and what it accepts, not what a `<filter>` element is. Claimed
        // only where the catalog can name the attribute; an unknown name falls through so the
        // element half — or silence, on analysis tags the element table does not cover — still wins.
        contextElement?.parentOfType<XmlAttribute>(withSelf = true)
            ?.takeIf { documentedClassAttribute(it) != null }
            ?.let { return it }
        // The structural attributes — `name`, `class`, `type`, `source`, `dest` — claimed last, so
        // they only take a caret the property and factory tables both declined. Without this the
        // caret falls through to the tag and hovering `class` answers with what a `<fieldType>` is,
        // which is a reasonable answer to a question nobody asked — the same mistake the property
        // half of this method exists to correct.
        contextElement?.parentOfType<XmlAttribute>(withSelf = true)
            ?.takeIf { it.parentOfType<XmlTag>()?.let { tag -> SolrAttributeMeanings.of(tag.name, it.name) } != null }
            ?.let { return it }
        // Falling through to the tag means hovering the element itself answers. Without this, every
        // question the plugin can answer required the caret to be inside an attribute value — a
        // gesture a reader makes only once they already suspect something. Analysis factory tags
        // join the schema elements here: the complete-configuration table is a fact about the tag,
        // not about the class name written on it.
        return contextElement?.parentOfType<XmlTag>()?.takeIf {
            SolrSchemaElements.forTag(it.name) != null || isAnalysisFactoryTag(it)
        }
    }

    /**
     * The documentation popup for [element], or null when it is not something this documents.
     *
     * @param element the element chosen by [getCustomDocumentationElement]
     * @param originalElement the element originally under the caret
     * @return HTML for the popup, or null
     */
    override fun generateDoc(element: PsiElement, originalElement: PsiElement?): String? {
        if (element is XmlTag) {
            if (isAnalysisFactoryTag(element)) return factoryTagDocumentation(element)
            return elementDocumentation(element)
        }
        if (element is XmlAttribute) {
            propertyDocumentation(element)?.let { return it }
            classAttributeDocumentation(element)?.let { return it }
            return structuralAttributeDocumentation(element)
        }
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
        if (element is XmlAttribute) {
            val resolved = documentedClassAttribute(element) ?: return null
            val version = modelFor(element.parentOfType<XmlTag>() ?: return null)?.solrVersion ?: return null
            return SolrReferenceGuide.classPage(resolved.entry.kind, resolved.entry.className, version)
                ?.let { listOf(it) }
        }
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
     * The complete-configuration popup for an analysis factory tag.
     *
     * Declines rather than inventing when the catalog does not know the class. A custom plugin
     * factory must never render as a class that accepts nothing — that is worse than silence,
     * because silence is honest and an empty table is a claim. The `class` *value* popup already
     * takes the same vow; this is the tag half of it.
     *
     * The catalog is looked up by class name alone, so the entry it returns says what the class is
     * and not where it was written. Those can disagree — a tokenizer factory on a `<filter>` is a
     * configuration Solr rejects, and one a reader may well be hovering to understand — so the tag
     * goes to the presentation beside the entry rather than being inferred back out of it.
     */
    private fun factoryTagDocumentation(tag: XmlTag): String? {
        val model = modelFor(tag) ?: return null
        val className = tag.getAttributeValue("class")?.takeIf { it.isNotEmpty() } ?: return null
        val entry = SolrClassCatalog.find(className, model.solrVersion) ?: return null
        val written = tag.attributes
            .asSequence()
            .filter { it.name != "class" }
            .mapNotNull { attribute -> attribute.value?.let { attribute.name to it } }
            .toMap()
        return SolrFieldPresentation.factoryDocumentation(tag.name, entry, written, model.solrVersion)
    }

    /**
     * Whether [tag] is a tokenizer, token filter or char filter — the analysis factories whose
     * complete configuration this provider owns.
     *
     * `fieldType` is deliberately excluded: it already has element documentation and a property
     * table of its own, and routing it through the factory path would replace that with a catalog
     * attribute list that is the wrong answer for a type.
     */
    private fun isAnalysisFactoryTag(tag: XmlTag): Boolean {
        val kind = SolrClassKind.forTag(tag.name) ?: return false
        return kind != SolrClassKind.FIELD_TYPE
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
     * The popup for a structural attribute — `name`, `class`, `type`, `source`, `dest`.
     *
     * **The last of the three caret positions to answer.** The element explained itself and so did
     * an attribute's value; the attribute's own name said nothing, which reads as the plugin not
     * knowing the file when the attribute beside it answers.
     *
     * `version` on the schema root gets a second paragraph, because the model already resolves what
     * it decides for every field-property popup and this is the attribute that causes it.
     */
    private fun structuralAttributeDocumentation(attribute: XmlAttribute): String? {
        val tag = attribute.parentOfType<XmlTag>() ?: return null
        if (!SolrConfigsetDetector.isConfigsetFile(attribute.containingFile)) return null

        val meaning = SolrAttributeMeanings.of(tag.name, attribute.name) ?: return null
        val detail = if (tag.name == "schema" && attribute.name == "version") {
            modelFor(tag)?.let { SolrAttributeMeanings.ofSchemaVersion(it.schemaVersion) }
        } else {
            null
        }
        return SolrFieldPresentation.attributeMeaning(attribute.name, meaning, detail)
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

    /**
     * The popup for one factory attribute the catalog can prove something about.
     *
     * Null rather than a half-filled table when the class or the attribute is unknown: inventing a
     * value type or a default is exactly the false confidence this package is organised to prevent,
     * and the same rule the unknown-attribute inspection follows when it declines to flag a custom
     * class.
     */
    private fun classAttributeDocumentation(attribute: XmlAttribute): String? {
        val resolved = documentedClassAttribute(attribute) ?: return null
        val tag = attribute.parentOfType<XmlTag>() ?: return null
        val model = modelFor(tag) ?: return null
        return SolrFieldPresentation.classAttributeDocumentation(
            resolved.entry,
            resolved.attribute,
            model.solrVersion,
        )
    }

    /**
     * The catalog entry and attribute [attribute] names, or null when either is unknown.
     *
     * Field properties on a `field` or `fieldType` are deliberately excluded: they have richer
     * documentation of their own, and a catalog hit for a name like `indexed` would be the wrong
     * answer even if one ever appeared. Structural names — `class`, `name` — are absent from every
     * recovered attribute list, so they fall out as null without a special case here.
     *
     * The kind is matched, not only the class name, for the same reason completion and the
     * unknown-attribute inspection match it: a tokenizer's attribute on a filter is not an
     * attribute this filter reads, even when both factories happen to share a spelling.
     */
    private fun documentedClassAttribute(attribute: XmlAttribute): ResolvedClassAttribute? {
        if (documentedProperty(attribute) != null) return null
        val tag = attribute.parentOfType<XmlTag>() ?: return null
        val kind = SolrClassKind.forTag(tag.name) ?: return null
        val className = tag.getAttributeValue("class")?.takeIf { it.isNotEmpty() } ?: return null
        val model = modelFor(tag) ?: return null
        val entry = SolrClassCatalog.find(className, model.solrVersion)
            ?.takeIf { it.kind == kind }
            ?: return null
        val known = entry.attribute(attribute.name) ?: return null
        return ResolvedClassAttribute(entry, known)
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

    /**
     * One attribute the catalog can document: the class that reads it, and what the catalog knows
     * about the attribute itself.
     */
    private data class ResolvedClassAttribute(
        val entry: SolrClassEntry,
        val attribute: SolrClassAttribute,
    )

}
