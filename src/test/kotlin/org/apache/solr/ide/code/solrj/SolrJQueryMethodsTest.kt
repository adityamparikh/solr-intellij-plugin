package org.apache.solr.ide.code.solrj

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which `SolrQuery` calls carry field names, and what shape their arguments are in.
 *
 * The map a recognizer consults before it reads anything: a builder call is only worth looking
 * inside when the method is one that names fields, and what to do with the string depends entirely
 * on which method it was. `addFilterQuery("categry:books")` holds a query expression;
 * `setFields("id,name")` holds a field list; `addField("price")` holds one bare name. Reading all
 * three the same way would report `categry:books` as a field called `categry:books`.
 */
class SolrJQueryMethodsTest {

    // --- the three argument shapes ---------------------------------------------------------------

    /** `fq` holds a query, so its argument is parsed as one. This is the demo's planted defect. */
    @Test
    fun `a filter query argument is a query expression`() {
        val method = SolrJQueryMethods.forMethod("addFilterQuery")
        assertEquals("fq", method?.parameter)
        assertEquals(SolrJArgumentShape.QUERY_EXPRESSION, method?.shape)
    }

    @Test
    fun `the main query argument is a query expression`() {
        assertEquals(SolrJArgumentShape.QUERY_EXPRESSION, SolrJQueryMethods.forMethod("setQuery")?.shape)
    }

    /** `setFields("id,name,price")` is one argument holding three names. */
    @Test
    fun `a field list argument is parsed as a list`() {
        val method = SolrJQueryMethods.forMethod("setFields")
        assertEquals("fl", method?.parameter)
        assertEquals(SolrJArgumentShape.FIELD_LIST, method?.shape)
    }

    /** `addField("price")` is one argument holding exactly one name, commas and all. */
    @Test
    fun `a single field argument is the whole name`() {
        val method = SolrJQueryMethods.forMethod("addField")
        assertEquals("fl", method?.parameter)
        assertEquals(SolrJArgumentShape.FIELD_NAME, method?.shape)
    }

    // --- which argument holds the field ----------------------------------------------------------

    /**
     * `setSort(String field, ORDER order)` puts the field in argument zero and an enum in argument
     * one, unlike solrconfig's `sort` where a whole clause is one string. A recognizer reading every
     * argument would read `ORDER.asc` as a field name.
     */
    @Test
    fun `sort reads only its first argument`() {
        val method = SolrJQueryMethods.forMethod("setSort")
        assertEquals("sort", method?.parameter)
        assertEquals(SolrJArgumentShape.FIELD_NAME, method?.shape)
        assertTrue(method?.readsOnlyFirstArgument == true)
    }

    /** Every other mapped method is varargs, and every argument counts. */
    @Test
    fun `a varargs method reads all of its arguments`() {
        assertEquals(false, SolrJQueryMethods.forMethod("addFacetField")?.readsOnlyFirstArgument)
        assertEquals(false, SolrJQueryMethods.forMethod("addFilterQuery")?.readsOnlyFirstArgument)
    }

    // --- the facet, highlight and terms families -------------------------------------------------

    @Test
    fun `the facet family maps to its own parameters`() {
        assertEquals("facet.field", SolrJQueryMethods.forMethod("addFacetField")?.parameter)
        assertEquals("facet.pivot", SolrJQueryMethods.forMethod("addFacetPivotField")?.parameter)
    }

    @Test
    fun `highlighting and terms name fields under their own parameters`() {
        assertEquals("hl.fl", SolrJQueryMethods.forMethod("addHighlightField")?.parameter)
        assertEquals("terms.fl", SolrJQueryMethods.forMethod("addTermsField")?.parameter)
    }

    // --- silence ---------------------------------------------------------------------------------

    /**
     * A method not on the map is not guessed at.
     *
     * `setRows` takes an int, `setFacetLimit` takes a count, and `setHighlight` takes a boolean.
     * Nothing about a name ending in something field-shaped makes its argument a field.
     */
    @Test
    fun `an unmapped method is not read`() {
        for (name in listOf("setRows", "setStart", "setFacetLimit", "setHighlight", "toString")) {
            assertNull(name, SolrJQueryMethods.forMethod(name))
        }
    }

    // --- the class the call must sit on -----------------------------------------------------------

    /**
     * `SolrQuery` moved package between the two supported Solr lines, and both spellings must match.
     *
     * Solr 9 has `org.apache.solr.client.solrj.SolrQuery`; Solr 10 moved it to
     * `org.apache.solr.client.solrj.request.SolrQuery` and left no shim behind. A recognizer holding
     * one name is silent on every project using the other line — and silent is exactly how this
     * plugin's defects have looked every time.
     */
    @Test
    fun `both package spellings of SolrQuery are recognized`() {
        assertTrue(SolrJQueryMethods.isSolrQueryClass("org.apache.solr.client.solrj.SolrQuery"))
        assertTrue(SolrJQueryMethods.isSolrQueryClass("org.apache.solr.client.solrj.request.SolrQuery"))
    }

    /** A class merely named `SolrQuery` somewhere else is not SolrJ's. */
    @Test
    fun `an unrelated class named SolrQuery is not recognized`() {
        assertEquals(false, SolrJQueryMethods.isSolrQueryClass("com.example.internal.SolrQuery"))
        assertEquals(false, SolrJQueryMethods.isSolrQueryClass("SolrQuery"))
    }
}
