package org.apache.solr.ide.model.query

import org.apache.solr.ide.code.solrj.SolrJQueryMethods
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * That the tracks naming Solr's parameters name the same ones.
 *
 * Three packages already spelled these strings independently — the configset grammar, the SolrJ
 * method map, and shortly the query console — and nothing made them agree. `facet.field` appeared in
 * all three. The cost is not duplication but divergence: a parameter one side produces and the other
 * has never heard of resolves to no operation, and "no opinion" is indistinguishable from "this field
 * is fine", so the plugin goes quiet without anything reporting a fault.
 *
 * These tests are what turn that from a convention into a failing build.
 */
class SolrParametersTest {

    /**
     * Every parameter the SolrJ map produces is one this package names.
     *
     * The assertion that would have caught the gap this extraction found: `terms.fl` and `mlt.fl`
     * were produced by the code track and unknown to the grammar. They are named now, and this fails
     * the day a fourth is added on one side only.
     */
    @Test
    fun `every parameter the code track produces is named here`() {
        val named = SolrParameters.all()
        val produced = SolrJQueryMethods.allParameters()

        assertTrue(
            "the code track produces parameters this package does not name: ${produced - named}",
            named.containsAll(produced),
        )
    }

    /**
     * Two parameters name fields that the grammar cannot yet read, and this pins exactly which.
     *
     * **A known gap, asserted rather than left to be discovered.** The code track reports a field
     * written into `addTermsField` or `setMoreLikeThisFields`, and `SolrQueryFields` has never heard
     * of either parameter — so `operationFor` returns null and the unsupported-operation inspection
     * has no opinion, which reads exactly like "this field is fine". Naming them here is not the fix;
     * it is what makes the fix provable, since closing the gap is deleting this list and watching the
     * assertion still pass.
     *
     * Extracting the shared vocabulary is what surfaced this. Neither list was wrong on its own.
     */
    @Test
    fun `the field-naming parameters without a grammar are the two known ones`() {
        val unreadable = SolrJQueryMethods.allParameters()
            .filter { it != SolrParameters.QUERY && it != SolrParameters.FILTER_QUERY }
            .filterNot { SolrQueryFields.holdsFieldNames(it) }
            .sorted()

        assertEquals(
            listOf(SolrParameters.MORE_LIKE_THIS_FIELDS, SolrParameters.TERMS_FIELDS).sorted(),
            unreadable,
        )
    }

    /**
     * `q` and `fq` are excluded above deliberately, and this is what says so.
     *
     * They hold query *syntax* rather than a field list, so a name appears in them only before a
     * colon. Reading them with the list grammar would produce a field called `categry:books`.
     */
    @Test
    fun `the query parameters hold expressions rather than field lists`() {
        assertEquals(false, SolrQueryFields.holdsFieldNames(SolrParameters.QUERY))
        assertEquals(false, SolrQueryFields.holdsFieldNames(SolrParameters.FILTER_QUERY))
    }

    /** No parameter is named twice under two constants. */
    @Test
    fun `each parameter is named once`() {
        val all = SolrParameters.all()

        assertEquals("a parameter is named twice", all.size, all.toSet().size)
    }
}
