package org.apache.solr.ide.configset.intention

import org.apache.solr.ide.model.schema.SolrField
import org.apache.solr.ide.model.SolrFieldModel
import org.apache.solr.ide.model.schema.SolrMatchAnalysis
import org.apache.solr.ide.model.schema.SolrPrefixSupport

/**
 * Decides whether a field can be given an efficiently prefix-matchable companion, and with what.
 *
 * **A pure function over the model, deliberately.** Every availability rule that does not need a
 * caret lives here or in [SolrCompanions], which is what lets the rules that matter most be tested
 * without booting an IDE. The rules that reject are the ones worth the most: an intention offered
 * where it does not apply is acted on by the user, which is worse than one that is simply missing.
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
     * The shared rules are [SolrCompanions.plan]'s. The two decided here are what counts as already
     * capable — anything the chain already grinds into partial terms — and which declared types may
     * be reused.
     *
     * Only [SolrPrefixSupport.EDGE_NGRAM] qualifies for reuse. A full n-gram type would satisfy a
     * prefix search too, but it indexes every substring, so reusing one would quietly buy a much
     * larger index than the user asked for.
     *
     * Confidence is not required of the *candidate* type, unlike of the source field. The prefix
     * finding rests on positive evidence — an edge-n-gram factory actually present in the chain —
     * whereas `confident` reports that some *other* factory was unrecognised, and an unfamiliar
     * stemmer does not stop the type producing prefixes.
     *
     * @param field the field that may lack prefix support
     * @param model the configset the field belongs to
     * @return the plan, or null if the intention should not be offered
     */
    fun planFor(field: SolrField, model: SolrFieldModel): SolrCompanionPlan? = SolrCompanions.plan(
        field = field,
        model = model,
        suffix = NAME_SUFFIX,
        generatedTypeName = GENERATED_TYPE_NAME,
        alreadyCapable = { it.prefix != SolrPrefixSupport.NONE },
        reusable = { SolrMatchAnalysis.of(it).prefix == SolrPrefixSupport.EDGE_NGRAM },
    )
}
