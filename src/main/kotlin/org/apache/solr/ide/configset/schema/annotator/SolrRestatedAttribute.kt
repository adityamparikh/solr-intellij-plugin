package org.apache.solr.ide.configset.schema.annotator

import com.intellij.psi.xml.XmlAttribute
import com.intellij.psi.xml.XmlTag
import org.apache.solr.ide.configset.activation.SolrConfigsetDetector
import org.apache.solr.ide.configset.activation.SolrConfigsetFileKind
import org.apache.solr.ide.configset.activation.SolrSchemaTags
import org.apache.solr.ide.configset.reading.SolrConfigsetReader
import org.apache.solr.ide.model.SolrFieldModel
import org.apache.solr.ide.model.schema.SolrFieldProperties
import org.apache.solr.ide.model.schema.SolrFieldProperty
import org.apache.solr.ide.model.vocabulary.SolrClassCatalog
import org.apache.solr.ide.model.vocabulary.SolrClassEntry
import org.apache.solr.ide.model.vocabulary.SolrClassKind

/**
 * Whether a written attribute is one the field would have had anyway.
 *
 * **Shared because the dim and the offer to delete must never disagree.** Two surfaces read this —
 * [SolrRestatedDefaultAnnotator] renders it and
 * [the removal intention][org.apache.solr.ide.configset.schema.intention.SolrRemoveRestatedAttributeIntention]
 * acts on it — and a reader who is shown a dimmed attribute that Alt-Enter then declines to remove
 * has been told two different things about the same file. One predicate is what stops that, rather
 * than two implementations that agree today.
 *
 * The judgement itself belongs to the model, where it can be tested without an IDE, and there are
 * two of them because there are two kinds of default: [SolrFieldProperties.restatesDefault] resolves
 * a field property through the schema's own layers, and [SolrClassEntry.restatesDefault] compares a
 * factory attribute against the literal the catalog read out of that class's constructor. What lives
 * here is only the PSI half — finding the property or the class entry, the type and the version that
 * the model needs in order to answer — and the choice of which question the tag is asking.
 */
internal object SolrRestatedAttribute {

    /**
     * Whether deleting [attribute] would leave the same field, field type or analysis component.
     *
     * @param attribute an attribute written in a configset file
     * @return true when the value is what the element resolves to without it
     */
    fun isRestated(attribute: XmlAttribute): Boolean {
        val tag = attribute.parent as? XmlTag ?: return false

        val file = attribute.containingFile?.originalFile ?: return false
        if (SolrConfigsetFileKind.forFileName(file.name)?.isSchema != true) return false
        if (!SolrConfigsetDetector.isConfigsetFile(file)) return false

        val written = attribute.value ?: return false
        val model = SolrConfigsetReader.getInstance(file.project).modelFor(file) ?: return false

        // The tag decides which question is even being asked, so it is asked first. A field property
        // and a factory attribute share nothing but the syntax: one resolves through the schema's own
        // layers, the other is a literal the catalog read out of a constructor.
        return when {
            tag.name in SolrSchemaTags.FIELD ->
                propertyOf(attribute)?.let { fieldRestates(it, written, tag, model) } == true
            tag.name in SolrSchemaTags.FIELD_TYPE ->
                propertyOf(attribute)?.let { typeRestates(it, written, tag, model) } == true
            tag.name in SolrSchemaTags.ANALYSIS_COMPONENTS ->
                factoryRestates(attribute.name, written, tag, model)
            else -> false
        }
    }

    private fun propertyOf(attribute: XmlAttribute): SolrFieldProperty? =
        SolrFieldProperties.byName(attribute.name)

    /**
     * A field's attribute, which resolves through the type it names.
     *
     * **`<dynamicField>` included, because the pattern is the only thing that makes one different.**
     * A dynamic field names a type exactly as a concrete field does, and none of these properties is
     * about the pattern — `indexed` means the same for the fields it will match as for a field
     * written out. `SolrFieldProperties.FOR_FIELD` is scoped to both for the same reason.
     *
     * **Properties legal only on a type are declined rather than compared.** `enableGraphQueries`
     * defaults to true, so comparing it against that default here would report it as removable — for
     * the wrong reason. Solr ignores it on a field outright, which is a different thing to tell the
     * reader and not this feature's to tell.
     */
    private fun fieldRestates(
        property: SolrFieldProperty,
        written: String,
        tag: XmlTag,
        model: SolrFieldModel,
    ): Boolean {
        if (property !in SolrFieldProperties.FOR_FIELD) return false
        // Read off the tag rather than looked up through the field, so an edit in progress — a field
        // the model has not caught up with — resolves from what the file says right now.
        val fieldType = tag.getAttributeValue("type")?.let { model.fieldTypes[it]?.effective }
        return SolrFieldProperties.restatesDefault(
            property = property,
            writtenValue = written,
            fieldType = fieldType,
            schemaVersion = model.schemaVersion,
            typeTraits = model.traitsOf(fieldType),
        )
    }

    /**
     * A field type's own attribute, which has nothing above it.
     *
     * **That absence is the whole difference, and it is expressed by passing no type.** A field
     * resolves through its `<fieldType>` before reaching Solr's defaults; a field type *is* that
     * layer, so it answers to the defaults and to its own class's traits directly. Every property is
     * legal here — `FOR_FIELD_TYPE` is all of them — so there is no scope test to make.
     */
    private fun typeRestates(
        property: SolrFieldProperty,
        written: String,
        tag: XmlTag,
        model: SolrFieldModel,
    ): Boolean {
        val declared = tag.getAttributeValue(SolrSchemaTags.NAME)?.let { model.fieldTypes[it]?.effective }
        return SolrFieldProperties.restatesDefault(
            property = property,
            writtenValue = written,
            fieldType = null,
            schemaVersion = model.schemaVersion,
            typeTraits = model.traitsOf(declared),
        )
    }

    /**
     * An analysis component's attribute, which resolves against one class and nothing else.
     *
     * **No layering, and therefore a different shape of question.** A `<filter>` inherits nothing —
     * there is no chain-wide default and no outer element to fall through to — so the only thing
     * that can make an attribute removable is the literal default the catalog read out of the
     * factory's own constructor. That is why this joins with no new machinery: the comparison is
     * [SolrClassEntry.restatesDefault]'s and everything here is finding the entry to ask.
     *
     * **The kind is matched as well as the class name**, exactly as the per-attribute hover and the
     * unknown-attribute inspection match it. A tokenizer's attribute written on a `<filter>` is not
     * an attribute that filter reads, even where the two factories spell one the same way, and
     * dimming it would offer to delete an attribute on the strength of a different class's default.
     *
     * Everything unknown stays silent, which is the whole of what the step asked for on this half: a
     * class outside the catalog — the ordinary custom-plugin case — an attribute the class does not
     * read, and an attribute whose default the bytecode did not prove. The last is the one that
     * matters most, because a factory that computes its default at runtime looks exactly like one
     * that declares it, right up to the point where the reader deletes the line.
     */
    private fun factoryRestates(
        attributeName: String,
        written: String,
        tag: XmlTag,
        model: SolrFieldModel,
    ): Boolean {
        val kind = SolrClassKind.forTag(tag.name) ?: return false
        val className = tag.getAttributeValue(CLASS)?.takeIf { it.isNotEmpty() } ?: return false
        val entry = SolrClassCatalog.find(className, model.solrVersion)?.takeIf { it.kind == kind }
            ?: return false
        return entry.restatesDefault(attributeName, written)
    }

    /** The attribute naming the factory, which is the one attribute that is never a setting. */
    private const val CLASS = "class"
}
