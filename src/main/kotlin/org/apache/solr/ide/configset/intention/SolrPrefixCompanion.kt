package org.apache.solr.ide.configset.intention

import org.apache.solr.ide.model.SolrField
import org.apache.solr.ide.model.SolrFieldModel
import org.apache.solr.ide.model.SolrMatchAnalysis
import org.apache.solr.ide.model.SolrPrefixSupport

/**
 * What adding prefix support to one field would take.
 *
 * Computed before anything is written, so the intention can name the type it is about to use in the
 * text the user reads — a schema declaring more than one edge-n-gram type is rare, but when it
 * happens the choice should be visible before it is taken rather than discovered afterwards.
 *
 * @property companionField the name of the field to add
 * @property typeName the field type the companion will use, whether reused or generated
 * @property generatesType true when [typeName] has to be written as well, false when the schema
 *   already declares it
 */
data class SolrPrefixCompanionPlan(
    val companionField: String,
    val typeName: String,
    val generatesType: Boolean,
)

/**
 * Decides whether a field can be given an efficiently prefix-matchable companion, and with what.
 *
 * **A pure function over the model, deliberately.** Every availability rule that does not need a
 * caret lives here, which is what lets the rules that matter most be tested without booting an IDE.
 * The rules that reject are the ones worth the most: an intention offered where it does not apply is
 * acted on by the user, which is worse than one that is simply missing.
 *
 * The companion pattern is three parts — a type with an edge-n-gram filter, a field using it, and a
 * copy rule to populate it — and this decides the first two.
 */
object SolrPrefixCompanion {

    /** Appended to the source field's name to name its companion. */
    const val NAME_SUFFIX: String = "_prefix"

    /** The type written when the schema declares nothing reusable. */
    const val GENERATED_TYPE_NAME: String = "text_prefix"

    /**
     * The plan for giving [field] prefix support, or null where the intention should not appear.
     *
     * Null covers every availability rule, and each one is a case where acting would be wrong
     * rather than merely unhelpful:
     *
     * - the field's type is not declared, so there is nothing to classify;
     * - the field already supports prefix matching, so there is nothing to add;
     * - the chain held a factory [SolrMatchAnalysis] does not know, so it was not fully understood —
     *   the same bar the inline hint uses, and for the same reason;
     * - the companion name is already declared, and inventing `description_prefix2` is worse than
     *   declining;
     * - a type would have to be generated under a name the schema has already used for something
     *   else.
     *
     * **`indexed` is deliberately not consulted.** `copyField` copies the incoming document value
     * before analysis, so the source field's own `indexed` and `stored` settings do not affect what
     * the companion receives. A display-only field is the cleanest case for this pattern, and a
     * plausible-looking `indexed="true"` guard would have suppressed the feature precisely where it
     * helps most.
     *
     * @param field the field that may lack prefix support
     * @param model the configset the field belongs to
     * @return the plan, or null if the intention should not be offered
     */
    fun planFor(field: SolrField, model: SolrFieldModel): SolrPrefixCompanionPlan? {
        val fieldType = model.typeOf(field) ?: return null
        val capability = SolrMatchAnalysis.of(fieldType)
        if (!capability.confident || capability.prefix != SolrPrefixSupport.NONE) return null

        val companionField = field.name + NAME_SUFFIX
        if (companionField in model.fields) return null

        reusableTypeIn(model)?.let {
            return SolrPrefixCompanionPlan(companionField, it, generatesType = false)
        }

        // Only the free-name case is checked, because the other half of the rule cannot be reached:
        // a `text_prefix` that *were* edge-n-gram-backed would have been found as reusable above,
        // and reuse never falls through to here.
        if (GENERATED_TYPE_NAME in model.fieldTypes) return null
        return SolrPrefixCompanionPlan(companionField, GENERATED_TYPE_NAME, generatesType = true)
    }

    /**
     * The name of a declared type already backed by an edge n-gram, or null if the schema has none.
     *
     * First in document order wins. Only [SolrPrefixSupport.EDGE_NGRAM] qualifies: a full n-gram
     * type would satisfy a prefix search too, but it indexes every substring, so reusing one would
     * quietly buy a much larger index than the user asked for.
     *
     * Confidence is not required of the candidate, unlike of the source field. The prefix finding
     * rests on positive evidence — an edge-n-gram factory actually present in the chain — whereas
     * `confident` reports that some *other* factory was unrecognised, and an unfamiliar stemmer does
     * not stop the type producing prefixes.
     *
     * @param model the configset to search
     * @return the type's name, or null
     */
    private fun reusableTypeIn(model: SolrFieldModel): String? = model.fieldTypes.values
        .map { it.effective }
        .firstOrNull { SolrMatchAnalysis.of(it).prefix == SolrPrefixSupport.EDGE_NGRAM }
        ?.name
}
