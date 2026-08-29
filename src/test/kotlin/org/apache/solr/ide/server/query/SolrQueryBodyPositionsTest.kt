package org.apache.solr.ide.server.query

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which positions in a Solr JSON query body name a field.
 *
 * Plain JUnit 4: a function from a list of keys to a boolean needs no IDE. The completion test
 * beside this exercises the same rules through a real caret; these state them one at a time, which
 * is where an added key gets its own line rather than being folded into a fixture.
 */
class SolrQueryBodyPositionsTest {

    private fun namesAField(vararg path: String) = SolrQueryBodyPositions.namesAField(path.toList())

    private fun isQueryBody(vararg path: String) = SolrQueryBodyPositions.isQueryBody(path.toList())

    // --- positions that name a field --------------------------------------------------------------

    @Test
    fun `a field list names a field`() {
        assertTrue(namesAField("fields"))
    }

    @Test
    fun `a sort names a field`() {
        assertTrue(namesAField("sort"))
    }

    @Test
    fun `a facet's field names a field`() {
        assertTrue(namesAField("facet", "prices", "field"))
    }

    /** Any facet name, since the user chose it. */
    @Test
    fun `a facet's field names a field whatever the facet is called`() {
        assertTrue(namesAField("facet", "anything_at_all", "field"))
    }

    // --- positions that do not ---------------------------------------------------------------------

    @Test
    fun `the root names no field`() {
        assertFalse(namesAField())
    }

    @Test
    fun `a limit names no field`() {
        assertFalse(namesAField("limit"))
    }

    /**
     * Query syntax is not handled, and that is deliberate.
     *
     * Completing inside `query` means parsing Solr's query parser's language — where a field name
     * is legal before a colon and wrong inside a phrase, a function call or a local-params block.
     * Offering there without that parser would put field names in the middle of expressions.
     */
    @Test
    fun `a query string names no field yet`() {
        assertFalse(namesAField("query"))
        assertFalse(namesAField("filter"))
    }

    /** The facet's own name is a label the user chose, not a field. */
    @Test
    fun `a facet's name names no field`() {
        assertFalse(namesAField("facet", "prices"))
    }

    @Test
    fun `a facet's type names no field`() {
        assertFalse(namesAField("facet", "prices", "type"))
    }

    /** A `field` key somewhere else in the document is not a facet's field. */
    @Test
    fun `a field key outside a facet names no field`() {
        assertFalse(namesAField("params", "field"))
        assertFalse(namesAField("field"))
    }

    // --- recognising the body at all ----------------------------------------------------------------

    @Test
    fun `solr's own top-level keys mark a query body`() {
        assertTrue(isQueryBody("query"))
        assertTrue(isQueryBody("fields"))
        assertTrue(isQueryBody("facet", "prices", "field"))
    }

    /**
     * Somebody else's JSON is not a query body.
     *
     * The check that keeps this out of every other JSON document, before any question about
     * positions is asked.
     */
    @Test
    fun `an unrelated document is not a query body`() {
        assertFalse(isQueryBody("name"))
        assertFalse(isQueryBody("dependencies", "react"))
        assertFalse(isQueryBody())
    }
}
