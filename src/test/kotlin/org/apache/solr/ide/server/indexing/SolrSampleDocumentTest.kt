package org.apache.solr.ide.server.indexing

import org.apache.solr.ide.model.SolrConfigsetFacts
import org.apache.solr.ide.model.schema.SolrField
import org.apache.solr.ide.model.schema.SolrFieldType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The starting document a schema produces.
 *
 * What it must get right is *shape*: a number field given a quoted placeholder is rejected by Solr
 * with a parse error that reads as the plugin's fault, and a multiValued field given a bare value is
 * rejected outright. A user replaces a placeholder; they should not have to repair the JSON around
 * it.
 */
class SolrSampleDocumentTest {

    private fun schema(vararg fields: SolrField) = SolrConfigsetFacts(
        fields = fields.toList(),
        fieldTypes = listOf(
            SolrFieldType("string", "solr.StrField"),
            SolrFieldType("pint", "solr.IntPointField"),
            SolrFieldType("pfloat", "solr.FloatPointField"),
            SolrFieldType("boolean", "solr.BoolField"),
            SolrFieldType("pdate", "solr.DatePointField"),
        ),
        uniqueKey = "id",
    )

    private val id = SolrField(name = "id", type = "string", required = true)

    private fun documentFor(vararg fields: SolrField) = SolrSampleDocument.forSchema(schema(*fields))

    // --- what goes in ------------------------------------------------------------------------------

    @Test
    fun `the unique key comes first`() {
        val document = documentFor(SolrField(name = "title", type = "string", required = true), id)

        assertTrue(document, document.lines()[1].contains("\"id\""))
    }

    @Test
    fun `required fields are included`() {
        val document = documentFor(id, SolrField(name = "title", type = "string", required = true))

        assertTrue(document, document.contains("\"title\""))
    }

    /**
     * Optional fields are left out, and that is the choice being made.
     *
     * A schema of two hundred fields would otherwise produce a document nobody reads, and every
     * optional field a user did not want is one they delete before indexing — more work than adding
     * the two they did.
     */
    @Test
    fun `optional fields are left out`() {
        val document = documentFor(id, SolrField(name = "subtitle", type = "string"))

        assertFalse(document, document.contains("subtitle"))
    }

    /** Solr's own fields are Solr's to manage. */
    @Test
    fun `internal fields are left out`() {
        val document = documentFor(id, SolrField(name = "_version_", type = "plong", required = true))

        assertFalse(document, document.contains("_version_"))
    }

    // --- the shapes ---------------------------------------------------------------------------------

    @Test
    fun `a string field gets a quoted placeholder`() {
        assertTrue(documentFor(id).contains("\"id\": \"${SolrSampleDocument.PLACEHOLDER}\""))
    }

    /** A quoted value in a number field is a parse error that reads as the plugin's fault. */
    @Test
    fun `an integer field gets an unquoted number`() {
        val document = documentFor(id, SolrField(name = "year", type = "pint", required = true))

        assertTrue(document, document.contains("\"year\": 1"))
    }

    @Test
    fun `a decimal field gets an unquoted decimal`() {
        val document = documentFor(id, SolrField(name = "price", type = "pfloat", required = true))

        assertTrue(document, document.contains("\"price\": 1.0"))
    }

    @Test
    fun `a boolean field gets a boolean`() {
        val document = documentFor(id, SolrField(name = "live", type = "boolean", required = true))

        assertTrue(document, document.contains("\"live\": true"))
    }

    @Test
    fun `a date field gets a date solr can parse`() {
        val document = documentFor(id, SolrField(name = "when", type = "pdate", required = true))

        assertTrue(document, document.contains("2026-01-01T00:00:00Z"))
    }

    /** Solr rejects a bare value for a multiValued field, so the shape follows the declaration. */
    @Test
    fun `a multivalued field gets an array`() {
        val document = documentFor(
            id,
            SolrField(name = "tags", type = "string", required = true, multiValued = true),
        )

        assertTrue(document, document.contains("\"tags\": [\"${SolrSampleDocument.PLACEHOLDER}\"]"))
    }

    /** A field whose type the schema does not declare still produces valid JSON. */
    @Test
    fun `an unresolvable type falls back to a quoted placeholder`() {
        val document = documentFor(id, SolrField(name = "odd", type = "no_such_type", required = true))

        assertTrue(document, document.contains("\"odd\": \"${SolrSampleDocument.PLACEHOLDER}\""))
    }

    // --- it is always valid JSON --------------------------------------------------------------------

    @Test
    fun `a schema with nothing required still produces a document`() {
        val document = SolrSampleDocument.forSchema(SolrConfigsetFacts())

        assertTrue(document, document.trim().startsWith("{"))
        assertTrue(document, document.trim().endsWith("}"))
    }

    @Test
    fun `fields are separated by commas`() {
        val document = documentFor(id, SolrField(name = "title", type = "string", required = true))

        assertEquals("one separator for two fields", 1, document.count { it == ',' })
    }
}
