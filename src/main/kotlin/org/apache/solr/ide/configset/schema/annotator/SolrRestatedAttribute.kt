package org.apache.solr.ide.configset.schema.annotator

import com.intellij.psi.xml.XmlAttribute
import com.intellij.psi.xml.XmlTag
import org.apache.solr.ide.configset.activation.SolrConfigsetDetector
import org.apache.solr.ide.configset.activation.SolrConfigsetFileKind
import org.apache.solr.ide.configset.reading.SolrConfigsetReader
import org.apache.solr.ide.model.schema.SolrFieldProperties

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
     * The one element this reads today.
     *
     * A `<fieldType>`'s own attributes restate defaults too and are the second half of this feature;
     * they resolve differently, having no inheritance layer above them.
     */
    private const val FIELD_TAG = "field"

    /**
     * Whether deleting [attribute] would leave the same field.
     *
     * @param attribute an attribute written in a configset file
     * @return true when the value is what the field resolves to without it
     */
    fun isRestated(attribute: XmlAttribute): Boolean {
        val tag = attribute.parent as? XmlTag ?: return false
        if (tag.name != FIELD_TAG) return false

        val file = attribute.containingFile?.originalFile ?: return false
        if (SolrConfigsetFileKind.forFileName(file.name)?.isSchema != true) return false
        if (!SolrConfigsetDetector.isConfigsetFile(file)) return false

        val property = SolrFieldProperties.byName(attribute.name) ?: return false
        val written = attribute.value ?: return false

        val model = SolrConfigsetReader.getInstance(file.project).modelFor(file) ?: return false
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
}
