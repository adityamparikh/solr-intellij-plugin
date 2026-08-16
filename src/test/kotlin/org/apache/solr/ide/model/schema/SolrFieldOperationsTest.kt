package org.apache.solr.ide.model.schema

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Which operations a field can serve — the plugin's first disjunctive property rule.
 *
 * Every other property check resolves one property and compares it. These rules cannot: a field is
 * searchable if it is indexed *or* carries doc values, and facetable if it carries doc values *or* is
 * indexed and un-invertible. The cases below are chosen for the disjunction rather than for coverage —
 * each side satisfied alone, neither satisfied, and the undetermined middle that must stay silent.
 */
class SolrFieldOperationsTest {

    private val modern = SolrSchemaVersion.of("1.7")
    private val older = SolrSchemaVersion.of("1.6")

    private val stringType = SolrFieldType(name = "string", className = "solr.StrField")

    private fun supports(
        operation: SolrFieldOperation,
        attributes: Map<String, String>,
        version: SolrSchemaVersion = modern,
        type: SolrFieldType? = stringType,
        traits: Set<SolrTypeTrait>? = emptySet(),
    ): Boolean? = SolrFieldOperations.supports(
        operation,
        SolrField(name = "f", type = "string", attributes = attributes),
        type,
        version,
        traits,
    )

    // --- search and filter ----------------------------------------------------------------------

    @Test
    fun `an indexed field is searchable and filterable`() {
        val attributes = mapOf("indexed" to "true", "docValues" to "false")
        assertEquals(true, supports(SolrFieldOperation.SEARCH, attributes))
        assertEquals(true, supports(SolrFieldOperation.FILTER, attributes))
    }

    /**
     * The case that made this class necessary. Solr turns an exact match on a doc-values-only field
     * into a single-value range query over the doc values rather than refusing it, so the field is
     * searchable — which the inspection reading `indexed` alone had no way to say.
     */
    @Test
    fun `a doc values only field is searchable`() {
        val attributes = mapOf("indexed" to "false", "docValues" to "true")
        assertEquals(true, supports(SolrFieldOperation.SEARCH, attributes))
        assertEquals(true, supports(SolrFieldOperation.FILTER, attributes))
    }

    /**
     * Solr spells these with `Boolean.parseBoolean`, so the casing a reader chose is not a fact.
     *
     * `FieldProperties.parseProperties` reads every one of these attributes through
     * `Boolean.parseBoolean(val.toString())`, which ignores case — `indexed="TRUE"` is a field that
     * indexes. A case-sensitive comparison here resolved it to a definite *false* and the operation
     * inspections underlined a schema that works, which is the one thing they must never do.
     *
     * The plugin already agreed with Solr on the other side of this: `SolrValueType.BOOLEAN` matches
     * ignoring case, so the invalid-value inspection accepted the spelling this rule misread. The two
     * disagreeing about one attribute is what makes this a defect rather than a strictness choice.
     */
    @Test
    fun `boolean properties are read the way Solr reads them, ignoring case`() {
        val shouting = mapOf("indexed" to "TRUE", "docValues" to "False")
        assertEquals(true, supports(SolrFieldOperation.SEARCH, shouting))
        assertEquals(true, supports(SolrFieldOperation.FILTER, shouting))

        val mixed = mapOf("indexed" to "False", "docValues" to "True")
        assertEquals(true, supports(SolrFieldOperation.SEARCH, mixed))
    }

    /**
     * A spelling neither Solr word covers is undetermined, not false.
     *
     * Solr would read `yes` as false, and this rule could follow it there. It does not: null is what
     * this class says when the schema has not clearly stated the property, and a value the table does
     * not recognise has not clearly stated anything. Reporting on it would be guessing about a file
     * whose real problem is that the value is invalid — which is the invalid-value inspection's to
     * say, in its own words, rather than this rule's to infer.
     */
    @Test
    fun `an unrecognised boolean spelling stays undetermined`() {
        val attributes = mapOf("indexed" to "yes", "docValues" to "false")
        assertNull(supports(SolrFieldOperation.SEARCH, attributes))
    }

    @Test
    fun `a field with neither is searchable by nothing`() {
        val attributes = mapOf("indexed" to "false", "docValues" to "false")
        assertEquals(false, supports(SolrFieldOperation.SEARCH, attributes))
        assertEquals(false, supports(SolrFieldOperation.FILTER, attributes))
    }

    // --- facet and sort -------------------------------------------------------------------------

    @Test
    fun `doc values alone are enough to facet and sort`() {
        val attributes = mapOf("indexed" to "false", "docValues" to "true")
        assertEquals(true, supports(SolrFieldOperation.FACET, attributes))
        assertEquals(true, supports(SolrFieldOperation.SORT, attributes))
    }

    /**
     * From schema version 1.7 `uninvertible` defaults false, so an indexed field with no doc values
     * can no longer be un-inverted at query time and Solr fails the request rather than building a
     * field cache. Searchable, and not facetable — the split this operation model exists to express.
     */
    @Test
    fun `an indexed field without doc values cannot facet in a modern schema`() {
        val attributes = mapOf("indexed" to "true", "docValues" to "false")
        assertEquals(false, supports(SolrFieldOperation.FACET, attributes))
        assertEquals(false, supports(SolrFieldOperation.SORT, attributes))
        assertEquals(true, supports(SolrFieldOperation.SEARCH, attributes))
    }

    /** Below 1.7 the same field is facetable, because `uninvertible` defaulted true. */
    @Test
    fun `an indexed field without doc values can facet in an older schema`() {
        val attributes = mapOf("indexed" to "true", "docValues" to "false")
        assertEquals(true, supports(SolrFieldOperation.FACET, attributes, version = older))
        assertEquals(true, supports(SolrFieldOperation.SORT, attributes, version = older))
    }

    /** An explicit `uninvertible="true"` overrides the version default, as any declaration does. */
    @Test
    fun `an explicit uninvertible restores faceting on an indexed field`() {
        val attributes = mapOf("indexed" to "true", "docValues" to "false", "uninvertible" to "true")
        assertEquals(true, supports(SolrFieldOperation.FACET, attributes))
    }

    /**
     * A multiValued field can be faceted and cannot be sorted, which is the one asymmetry between the
     * two operations. Solr rejects a plain sort on it because several values have no defined order, and
     * requires a selector — `sort=field(prices,min) asc` — which is a different expression rather than
     * the bare field name a `sort` parameter holds.
     */
    @Test
    fun `a multiValued field can be faceted and not sorted`() {
        val attributes = mapOf("docValues" to "true", "multiValued" to "true")
        assertEquals(true, supports(SolrFieldOperation.FACET, attributes))
        assertEquals(false, supports(SolrFieldOperation.SORT, attributes))
    }

    /** And searching a multiValued field is ordinary, so the extra condition stays out of that rule. */
    @Test
    fun `a multiValued field is searchable`() {
        val attributes = mapOf("indexed" to "true", "multiValued" to "true")
        assertEquals(true, supports(SolrFieldOperation.SEARCH, attributes))
    }

    // --- the undetermined middle -----------------------------------------------------------------

    /**
     * A field type whose class the catalog has never seen leaves `docValues` undetermined, and a rule
     * treating that as false would invent a default for somebody's custom type. Null is not "no" — it
     * is "the schema does not say", and a caller reporting a problem must stay silent on it.
     */
    @Test
    fun `an undetermined property yields null rather than false`() {
        assertNull(
            supports(
                SolrFieldOperation.SEARCH,
                mapOf("indexed" to "false"),
                type = SolrFieldType(name = "custom", className = "com.example.MyField"),
                traits = null,
            ),
        )
    }

    /**
     * Unknown on one side and satisfied on the other is still an answer. A doc-values field is
     * searchable however little is known about `indexed`, so silence here would withhold a fact the
     * schema does state.
     */
    @Test
    fun `a satisfied side settles the disjunction despite an undetermined other side`() {
        assertEquals(
            true,
            supports(
                SolrFieldOperation.SEARCH,
                mapOf("docValues" to "true"),
                type = SolrFieldType(name = "custom", className = "com.example.MyField"),
                traits = null,
            ),
        )
    }
}
