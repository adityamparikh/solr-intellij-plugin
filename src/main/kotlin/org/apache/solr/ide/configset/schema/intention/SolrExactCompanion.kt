package org.apache.solr.ide.configset.schema.intention

import org.apache.solr.ide.model.schema.SolrField
import org.apache.solr.ide.model.SolrFieldModel
import org.apache.solr.ide.model.schema.SolrFieldType
import org.apache.solr.ide.model.schema.SolrMatchGranularity

/**
 * Decides whether a tokenised field can be given a companion that matches its whole value.
 *
 * A tokenised field cannot answer an exact match, a facet or a sort on the value a document
 * actually carried: `Widget Pro` went into the index as `widget` and `pro`, and the original string
 * is not there to be found. The remedy is the oldest pattern in Solr — a `string` companion beside
 * the text field, populated by a copy rule — and this decides its two halves.
 *
 * The sibling of [SolrPrefixCompanion], sharing [SolrCompanions] with it.
 */
object SolrExactCompanion {

    /** Appended to the source field's name to name its companion. */
    const val NAME_SUFFIX: String = "_exact"

    /** The type written when the schema declares no string type. */
    const val GENERATED_TYPE_NAME: String = "string"

    /**
     * The plan for giving [field] an exact-match companion, or null where nothing should be offered.
     *
     * The shared rules are [SolrCompanions.plan]'s. The two decided here are what counts as already
     * capable, and which declared types may be reused.
     *
     * **Already capable is [SolrMatchGranularity.WHOLE_VALUE], whatever produces it.** A `string`
     * field has nothing to gain, and neither does a keyword-tokenised text field: both hold the
     * whole input as one term, which is the capability this would add.
     *
     * **Reuse matches on the implementing class, not on the capability, and that distinction is the
     * whole of [isStringType].**
     *
     * @param field the tokenised field
     * @param model the configset the field belongs to
     * @return the plan, or null if the intention should not be offered
     */
    fun planFor(field: SolrField, model: SolrFieldModel): SolrCompanionPlan? = SolrCompanions.plan(
        field = field,
        model = model,
        suffix = NAME_SUFFIX,
        generatedTypeName = GENERATED_TYPE_NAME,
        alreadyCapable = { it.granularity == SolrMatchGranularity.WHOLE_VALUE },
        reusable = ::isStringType,
    )

    /**
     * Whether [fieldType] is a string type, and so safe to copy arbitrary text into.
     *
     * **Selecting by match capability instead would be a bug, and a quiet one.** Every numeric, date
     * and boolean type is unanalysed, so match analysis reports all of them as matching whole values
     * — exactly what a string type reports. A reuse rule written against that would point a text
     * field's companion at a `plong` on any schema that happened to declare one first, and the
     * failure would arrive at index time, on the user's documents, long after the edit.
     *
     * The catalog cannot settle it either: its `PRIMITIVE` trait covers every numeric, boolean,
     * string, date, UUID and enum type together, because the trait exists to decide `omitNorms`
     * rather than to distinguish these.
     *
     * Matching the class by simple name accepts `solr.StrField` and the fully qualified spelling
     * alike, the same way [org.apache.solr.ide.model.schema.SolrMatchAnalysis] reads factory names.
     *
     * @param fieldType a declared type
     * @return true if a copy rule may target a field of this type
     */
    private fun isStringType(fieldType: SolrFieldType): Boolean =
        fieldType.className.substringAfterLast('.') == STR_FIELD

    /** Solr's only string class. `SortableTextField` is a text type and is analysed. */
    private const val STR_FIELD = "StrField"
}
