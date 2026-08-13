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
 * The judgement itself is [SolrFieldProperties.restatesDefault]'s, in the model where it can be
 * tested without an IDE. What lives here is only the PSI half: finding the property, the type and
 * the version that the model needs in order to answer.
 */
internal object SolrRestatedAttribute {

    /**
     * Whether deleting [attribute] would leave the same field or field type.
     *
     * @param attribute an attribute written in a configset file
     * @return true when the value is what the element resolves to without it
     */
    fun isRestated(attribute: XmlAttribute): Boolean {
        val tag = attribute.parent as? XmlTag ?: return false

        val file = attribute.containingFile?.originalFile ?: return false
        if (SolrConfigsetFileKind.forFileName(file.name)?.isSchema != true) return false
        if (!SolrConfigsetDetector.isConfigsetFile(file)) return false

        val property = SolrFieldProperties.byName(attribute.name) ?: return false
        val written = attribute.value ?: return false
        val model = SolrConfigsetReader.getInstance(file.project).modelFor(file) ?: return false

        return when {
            tag.name in SolrSchemaTags.FIELD -> fieldRestates(property, written, tag, model)
            tag.name in SolrSchemaTags.FIELD_TYPE -> typeRestates(property, written, tag, model)
            else -> false
        }
    }

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
}
