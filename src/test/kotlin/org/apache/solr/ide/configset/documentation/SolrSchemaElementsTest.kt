package org.apache.solr.ide.configset.documentation

import org.apache.solr.ide.model.SolrConfigsetFacts
import org.apache.solr.ide.model.SolrCopyField
import org.apache.solr.ide.model.SolrDynamicField
import org.apache.solr.ide.model.SolrField
import org.apache.solr.ide.model.SolrFieldModel
import org.apache.solr.ide.model.SolrFieldType
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the plugin says about a schema element.
 *
 * The *specific* half carries the weight. What a `copyField` is can be looked up; that this
 * particular rule joins two fields, one of which the schema does not declare, cannot be — and that
 * is the half these tests are mostly about.
 */
class SolrSchemaElementsTest {

    private val model = SolrFieldModel.of(
        SolrConfigsetFacts(
            fields = listOf(
                SolrField("id", "string"),
                SolrField("name", "text_general"),
                SolrField("text", "text_general"),
            ),
            dynamicFields = listOf(SolrDynamicField("*_s", SolrField("*_s", "string"))),
            fieldTypes = listOf(
                SolrFieldType("string", "solr.StrField"),
                SolrFieldType("text_general", "solr.TextField"),
            ),
            copyFields = listOf(SolrCopyField("name", "text"), SolrCopyField("manufacturer", "text")),
            uniqueKey = "id",
        ),
    )

    private fun specifics(tag: String, vararg attributes: Pair<String, String>) =
        SolrSchemaElements.specifics(tag, attributes.toMap(), model)

    // --- the general half -------------------------------------------------------------------

    @Test
    fun `every schema element the plugin recognises has an explanation`() {
        for (tag in listOf("schema", "field", "dynamicField", "fieldType", "fieldtype", "copyField", "uniqueKey", "analyzer")) {
            val description = SolrSchemaElements.forTag(tag)
            assertNotNull("$tag should be explained", description)
            assertTrue("$tag needs a summary", description!!.summary.length > 40)
        }
    }

    @Test
    fun `an element the plugin does not recognise has none`() {
        assertNull(SolrSchemaElements.forTag("similarity"))
        assertNull(SolrSchemaElements.forTag("notAnElement"))
    }

    // --- the specific half ------------------------------------------------------------------

    @Test
    fun `the schema element counts what this configset declares`() {
        val text = specifics("schema")!!
        assertTrue(text, text.contains("3 fields"))
        assertTrue(text, text.contains("1 dynamic field"))
        assertTrue(text, text.contains("2 field types"))
        assertTrue(text, text.contains("id"))
    }

    @Test
    fun `a copyField says what it joins`() {
        val text = specifics("copyField", "source" to "name", "dest" to "text")!!
        assertTrue(text, text.contains("name") && text.contains("text"))
        assertTrue("a sound rule reports no missing end: $text", !text.contains("declares no"))
    }

    /** The half no external documentation can supply: this rule's source does not exist. */
    @Test
    fun `a dangling copyField says which end is missing`() {
        val text = specifics("copyField", "source" to "manufacturer", "dest" to "text")!!
        assertTrue(text, text.contains("declares no"))
        assertTrue(text, text.contains("manufacturer"))
    }

    /** A glob is a pattern whose matches the schema alone cannot determine, so it is not called missing. */
    @Test
    fun `a glob copyField source is not reported as missing`() {
        val text = specifics("copyField", "source" to "*_s", "dest" to "text")!!
        assertTrue(text, !text.contains("declares no"))
    }

    @Test
    fun `uniqueKey names the field and its type`() {
        val text = specifics("uniqueKey")!!
        assertTrue(text, text.contains("id") && text.contains("string"))
    }

    @Test
    fun `a fieldType says how many fields use it`() {
        assertTrue(specifics("fieldType", "name" to "text_general")!!.contains("2 fields"))
        assertTrue(specifics("fieldType", "name" to "string")!!.contains("1 field"))
    }

    @Test
    fun `a field says where it is copied to, and says nothing when it is not`() {
        assertTrue(specifics("field", "name" to "name")!!.contains("text"))
        assertNull(specifics("field", "name" to "id"))
    }

    @Test
    fun `an element with nothing specific to say says nothing`() {
        assertNull(specifics("analyzer", "type" to "index"))
        assertNull(specifics("copyField"))
    }
}
