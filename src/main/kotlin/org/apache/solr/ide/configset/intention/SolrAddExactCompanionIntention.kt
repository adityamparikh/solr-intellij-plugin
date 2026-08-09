package org.apache.solr.ide.configset.intention

import org.apache.solr.ide.SolrBundle
import org.apache.solr.ide.model.schema.SolrField
import org.apache.solr.ide.model.SolrFieldModel

/**
 * Generates the string companion that lets a tokenised field be matched, faceted and sorted whole.
 *
 * The inline hint already says a `text_general` field is tokenised. What it cannot say is that the
 * value the document actually carried is gone: `Widget Pro` went into the index as `widget` and
 * `pro`, so a filter on the whole string, a facet over it, or a sort by it all have nothing to work
 * with. The remedy is the oldest pattern in Solr, and one of the most re-derived.
 *
 * See [SolrAddCompanionIntention] for why this is an intention rather than a quick-fix, and
 * [SolrExactCompanion] for the availability rules — including why a numeric type is never borrowed
 * for the companion even though it matches whole values.
 */
class SolrAddExactCompanionIntention : SolrAddCompanionIntention() {

    /** The stable label, which groups both variants under one entry in the intention settings. */
    override fun getFamilyName(): String = SolrBundle.message("intention.exactCompanion.family")

    override fun planFor(field: SolrField, model: SolrFieldModel): SolrCompanionPlan? =
        SolrExactCompanion.planFor(field, model)

    /** Names the reused type, so a schema declaring several string types shows which one won. */
    override fun textFor(plan: SolrCompanionPlan): String = if (plan.generatesType) {
        SolrBundle.message("intention.exactCompanion.generate")
    } else {
        SolrBundle.message("intention.exactCompanion.reuse", plan.typeName)
    }

    /**
     * The generated type, written as Solr's own `_default` configset writes it.
     *
     * Copied from what Solr ships rather than invented, because this is the one declaration in a
     * schema that almost never varies, and matching it means a reader recognises the line instead of
     * having to check it. `sortMissingLast` is what makes a sort over the companion put documents
     * that lack the field at the end rather than the start, which is the behaviour anyone sorting
     * expects; `docValues` is what a facet or a sort actually reads.
     *
     * No analyzer, and that is the point: `StrField` is unanalysed, so the whole value survives into
     * the index as one term, which is the capability the companion exists to add.
     */
    override fun generatedType(typeName: String): String =
        """<fieldType name="$typeName" class="solr.StrField" sortMissingLast="true" docValues="true"/>"""
}
