package org.apache.solr.ide.server.indexing

import org.apache.solr.ide.model.SolrConfigsetFacts
import org.apache.solr.ide.model.schema.SolrCopyField
import org.apache.solr.ide.model.schema.SolrDynamicField
import org.apache.solr.ide.model.schema.SolrField
import org.apache.solr.ide.model.schema.SolrFieldType
import org.apache.solr.ide.server.reading.SolrJsonDocuments
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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

    private val types = listOf(
        SolrFieldType("string", "solr.StrField"),
        SolrFieldType("text_general", "solr.TextField"),
        SolrFieldType("pint", "solr.IntPointField"),
        SolrFieldType("plong", "solr.LongPointField"),
        SolrFieldType("pfloat", "solr.FloatPointField"),
        SolrFieldType("pdouble", "solr.DoublePointField"),
        SolrFieldType("boolean", "solr.BoolField"),
        SolrFieldType("pdate", "solr.DatePointField"),
        SolrFieldType(
            "ignored",
            "solr.StrField",
            attributes = mapOf("indexed" to "false", "stored" to "false", "docValues" to "false"),
        ),
    )

    private fun schema(vararg fields: SolrField) = SolrConfigsetFacts(
        fields = fields.toList(),
        fieldTypes = types,
        uniqueKey = "id",
    )

    private val id = SolrField(name = "id", type = "string", required = true)

    private fun documentFor(vararg fields: SolrField) = SolrSampleDocument.forSchema(schema(*fields))

    /** The field names in [document], in order, read the way the dialog reads them. */
    private fun namesIn(document: String): List<String> {
        val tree = SolrJsonDocuments.treeOf(document)
        assertNotNull("not JSON: $document", tree)
        return tree!!.properties().map { it.key }
    }

    // --- what goes in ------------------------------------------------------------------------------

    @Test
    fun `the unique key comes first`() {
        val document = documentFor(SolrField(name = "title", type = "string", required = true), id)

        assertEquals("id", namesIn(document).first())
    }

    /** The key keeps the value it always had, so a user who only wants an id still has one. */
    @Test
    fun `the unique key keeps its placeholder`() {
        assertTrue(documentFor(id).contains("\"id\": \"${SolrSampleDocument.PLACEHOLDER}\""))
    }

    @Test
    fun `required fields are included`() {
        val document = documentFor(id, SolrField(name = "title", type = "string", required = true))

        assertTrue(document, document.contains("\"title\""))
    }

    /**
     * Optional fields are included, because a document of only an id is not a test of anything.
     *
     * The fields a user fills are the ones they need to see. Deleting a line they did not want is
     * cheaper than remembering, and spelling, one the schema has.
     */
    @Test
    fun `optional fields are included`() {
        val document = documentFor(id, SolrField(name = "subtitle", type = "string"))

        assertTrue(document, document.contains("\"subtitle\""))
    }

    /** Required first, because those are the fields the user must not delete by accident. */
    @Test
    fun `required fields come before optional ones, each in declaration order`() {
        val document = documentFor(
            SolrField(name = "b_optional", type = "string"),
            SolrField(name = "z_required", type = "string", required = true),
            id,
            SolrField(name = "a_optional", type = "string"),
        )

        assertEquals(listOf("id", "z_required", "b_optional", "a_optional"), namesIn(document))
    }

    /** Solr's own fields are Solr's to manage. */
    @Test
    fun `internal fields are left out`() {
        val document = documentFor(
            id,
            SolrField(name = "_version_", type = "plong", required = true),
            SolrField(name = "_root_", type = "string"),
            SolrField(name = "_nest_path_", type = "string"),
            SolrField(name = "_text_", type = "text_general", multiValued = true),
        )

        assertEquals(listOf("id"), namesIn(document))
    }

    /** Solr fills a copyField destination itself; a value supplied for it is added to the copy. */
    @Test
    fun `copy field destinations are left out`() {
        val document = SolrSampleDocument.forSchema(
            schema(
                id,
                SolrField(name = "title", type = "text_general"),
                SolrField(name = "title_sort", type = "string"),
            ).copy(copyFields = listOf(SolrCopyField("title", "title_sort"))),
        )

        assertEquals(listOf("id", "title"), namesIn(document))
    }

    /** A field that is neither indexed, stored nor docValues keeps nothing it is given. */
    @Test
    fun `a field that keeps nothing is left out`() {
        val document = documentFor(
            id,
            SolrField(name = "dropped", type = "string", indexed = false, stored = false, docValues = false),
        )

        assertFalse(document, document.contains("dropped"))
    }

    /** Solr's `ignored` type declares the three flags on the type, and a field inherits them. */
    @Test
    fun `a field whose type keeps nothing is left out`() {
        val document = documentFor(id, SolrField(name = "ignored_field", type = "ignored"))

        assertFalse(document, document.contains("ignored_field"))
    }

    /** One of the three is enough: a docValues-only field is still sortable and facetable. */
    @Test
    fun `a field with doc values only is included`() {
        val document = documentFor(
            id,
            SolrField(name = "popularity", type = "pint", indexed = false, stored = false, docValues = true),
        )

        assertTrue(document, document.contains("\"popularity\""))
    }

    /**
     * An unset flag is not a false one.
     *
     * The Schema API reports only what was written, so a field declared with `stored="false"` and
     * nothing else has an index behind it by default and must stay.
     */
    @Test
    fun `an unset flag does not count as false`() {
        val document = documentFor(id, SolrField(name = "searchable", type = "string", stored = false))

        assertTrue(document, document.contains("\"searchable\""))
    }

    /** A pattern is not a field, and a name invented for one is a name nobody asked for. */
    @Test
    fun `dynamic patterns produce no field`() {
        val document = SolrSampleDocument.forSchema(
            schema(id).copy(
                dynamicFields = listOf(SolrDynamicField("*_s", SolrField(name = "*_s", type = "string"))),
            ),
        )

        assertEquals(listOf("id"), namesIn(document))
    }

    // --- the shapes ---------------------------------------------------------------------------------

    @Test
    fun `a string field gets a quoted placeholder`() {
        assertTrue(documentFor(id).contains("\"id\": \"${SolrSampleDocument.PLACEHOLDER}\""))
    }

    @Test
    fun `a text field gets a quoted placeholder`() {
        val document = documentFor(id, SolrField(name = "title", type = "text_general"))

        assertTrue(document, document.contains("\"title\": \"${SolrSampleDocument.PLACEHOLDER}\""))
    }

    /** A quoted value in a number field is a parse error that reads as the plugin's fault. */
    @Test
    fun `an integer field gets an unquoted number`() {
        val document = documentFor(id, SolrField(name = "year", type = "pint", required = true))

        assertTrue(document, document.contains("\"year\": 1"))
    }

    @Test
    fun `a long field gets an unquoted number`() {
        val document = documentFor(id, SolrField(name = "views", type = "plong"))

        assertTrue(document, document.contains("\"views\": 1"))
    }

    @Test
    fun `a decimal field gets an unquoted decimal`() {
        val document = documentFor(id, SolrField(name = "price", type = "pfloat", required = true))

        assertTrue(document, document.contains("\"price\": 1.0"))
    }

    @Test
    fun `a double field gets an unquoted decimal`() {
        val document = documentFor(id, SolrField(name = "rating", type = "pdouble"))

        assertTrue(document, document.contains("\"rating\": 1.0"))
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
    fun `a schema with no fields still produces a document`() {
        val document = SolrSampleDocument.forSchema(SolrConfigsetFacts())

        assertTrue(document, document.trim().startsWith("{"))
        assertTrue(document, document.trim().endsWith("}"))
    }

    @Test
    fun `fields are separated by commas`() {
        val document = documentFor(id, SolrField(name = "title", type = "string", required = true))

        assertEquals("one separator for two fields", 1, document.count { it == ',' })
    }

    // --- it is accepted as it stands -----------------------------------------------------------------

    /**
     * The starting document raises nothing, so OK works before a key is pressed.
     *
     * The schema is the shape of a real collection's — Solr's four internal fields, a copy-field
     * target, a dynamic pattern and one field of each value shape — because a sample the dialog then
     * complains about is worse than the id-only document this replaced.
     */
    @Test
    fun `the document passes the dialog's own validation`() {
        val facts = schema(
            SolrField(name = "_version_", type = "plong", indexed = false, stored = false),
            SolrField(name = "_root_", type = "string", indexed = true, stored = false),
            SolrField(name = "_nest_path_", type = "string"),
            SolrField(name = "_text_", type = "text_general", multiValued = true, stored = false),
            id,
            SolrField(name = "title", type = "text_general"),
            SolrField(name = "cast", type = "string", multiValued = true, docValues = true),
            SolrField(name = "release_year", type = "pint"),
            SolrField(name = "imdb_rating", type = "pdouble"),
            SolrField(name = "streaming", type = "boolean"),
            SolrField(name = "released", type = "pdate"),
            SolrField(name = "everything", type = "text_general", multiValued = true),
            SolrField(name = "junk", type = "ignored"),
        ).copy(
            dynamicFields = listOf(SolrDynamicField("*_s", SolrField(name = "*_s", type = "string"))),
            copyFields = listOf(SolrCopyField("*", "_text_"), SolrCopyField("title", "everything")),
        )

        val document = SolrSampleDocument.forSchema(facts)
        val names = namesIn(document)

        assertEquals(
            listOf("id", "title", "cast", "release_year", "imdb_rating", "streaming", "released"),
            names,
        )
        assertEquals(emptyList<SolrDocumentProblem>(), SolrDocumentValidation.problemsIn(names, facts))
    }
}
