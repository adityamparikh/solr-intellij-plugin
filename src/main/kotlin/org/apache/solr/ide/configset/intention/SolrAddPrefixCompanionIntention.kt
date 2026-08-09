package org.apache.solr.ide.configset.intention

import org.apache.solr.ide.SolrBundle
import org.apache.solr.ide.model.schema.SolrField
import org.apache.solr.ide.model.SolrFieldModel

/**
 * Generates the field that makes prefix searching work, from the field that cannot do it.
 *
 * The plugin already tells a reader that `description` cannot match a prefix. Before this, it left
 * them to write the remedy from memory — a three-part pattern that is usually copied off a blog
 * post, and usually copied wrong in the one way described under [generatedType].
 *
 * See [SolrAddCompanionIntention] for why this is an intention rather than a quick-fix, and
 * [SolrPrefixCompanion] for the availability rules.
 */
class SolrAddPrefixCompanionIntention : SolrAddCompanionIntention() {

    /** The stable label, which groups both variants under one entry in the intention settings. */
    override fun getFamilyName(): String = SolrBundle.message("intention.prefixCompanion.family")

    override fun planFor(field: SolrField, model: SolrFieldModel): SolrCompanionPlan? =
        SolrPrefixCompanion.planFor(field, model)

    /**
     * Naming the reused type is the mitigation for the one real ambiguity here: a schema declaring
     * several edge-n-gram types has one picked for it, and reading that choice before taking the fix
     * is the difference between a decision and a surprise.
     */
    override fun textFor(plan: SolrCompanionPlan): String = if (plan.generatesType) {
        SolrBundle.message("intention.prefixCompanion.generate")
    } else {
        SolrBundle.message("intention.prefixCompanion.reuse", plan.typeName)
    }

    /**
     * The generated type, whose asymmetry is the reason generating it is worth more than generating
     * the field: the edge n-gram appears on the index side only.
     *
     * Put it on the query side as well and a search for `wid` is itself ground into `wi` and `wid`,
     * both of which match a large fraction of the index. Relevance collapses, and the symptom gets
     * reported as "search is broken" rather than as a schema bug.
     *
     * The gram bounds are conventional rather than derived: below two a single character matches
     * most of the index, and above fifteen the index carries prefixes nobody types.
     */
    override fun generatedType(typeName: String): String = """
        <fieldType name="$typeName" class="solr.TextField" positionIncrementGap="100">
          <analyzer type="index">
            <tokenizer class="solr.StandardTokenizerFactory"/>
            <filter class="solr.LowerCaseFilterFactory"/>
            <filter class="solr.EdgeNGramFilterFactory" minGramSize="2" maxGramSize="15"/>
          </analyzer>
          <analyzer type="query">
            <tokenizer class="solr.StandardTokenizerFactory"/>
            <filter class="solr.LowerCaseFilterFactory"/>
          </analyzer>
        </fieldType>
    """.trimIndent()
}
