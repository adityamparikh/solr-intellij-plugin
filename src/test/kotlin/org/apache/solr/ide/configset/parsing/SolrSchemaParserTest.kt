package org.apache.solr.ide.configset.parsing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Parsing rules for [SolrSchemaParser].
 *
 * Plain JUnit over strings, with no IDE fixture: the model is the component that has to be
 * exhaustively correct, and it is only affordable to test it exhaustively if the tests are fast.
 */
class SolrSchemaParserTest {

    private val schema = """
        <?xml version="1.0" encoding="UTF-8"?>
        <schema name="products" version="1.6">
          <fieldType name="string" class="solr.StrField" sortMissingLast="true" docValues="true"/>
          <fieldType name="text_general" class="solr.TextField" positionIncrementGap="100">
            <analyzer type="index">
              <charFilter class="solr.HTMLStripCharFilterFactory"/>
              <tokenizer class="solr.StandardTokenizerFactory"/>
              <filter class="solr.LowerCaseFilterFactory"/>
              <filter class="solr.StopFilterFactory" words="stopwords.txt" ignoreCase="true"/>
            </analyzer>
            <analyzer type="query">
              <tokenizer class="solr.StandardTokenizerFactory"/>
              <filter class="solr.LowerCaseFilterFactory"/>
            </analyzer>
          </fieldType>
          <field name="id" type="string" indexed="true" stored="true" required="true"/>
          <field name="name" type="text_general" indexed="true" stored="true"/>
          <field name="internal" type="string" indexed="false" stored="false"/>
          <dynamicField name="*_s" type="string" indexed="true" stored="true"/>
          <dynamicField name="*_str" type="string" docValues="true"/>
          <uniqueKey>id</uniqueKey>
          <copyField source="name" dest="text" maxChars="30000"/>
          <copyField source="manufacturer" dest="text"/>
        </schema>
    """.trimIndent()

    @Test
    fun `fields are read with their attributes`() {
        val id = SolrSchemaParser.parse(schema).fields.single { it.name == "id" }
        assertEquals("string", id.type)
        assertEquals(true, id.indexed)
        assertEquals(true, id.required)
        assertNull("multiValued is unset and must not be invented", id.multiValued)
    }

    /**
     * Unset is not false. An unset attribute inherits from the field type, so collapsing the two
     * would make the model assert something the configset never said.
     */
    @Test
    fun `unset boolean attributes stay null`() {
        val name = SolrSchemaParser.parse(schema).fields.single { it.name == "name" }
        assertNull(name.docValues)
        assertEquals(false, SolrSchemaParser.parse(schema).fields.single { it.name == "internal" }.indexed)
    }

    @Test
    fun `dynamic fields are read separately from declared fields`() {
        val facts = SolrSchemaParser.parse(schema)
        assertEquals(listOf("*_s", "*_str"), facts.dynamicFields.map { it.pattern })
        assertTrue("a dynamicField must not also appear as a field", facts.fields.none { it.name.startsWith("*") })
    }

    @Test
    fun `field types carry both analyzer chains`() {
        val type = SolrSchemaParser.parse(schema).fieldTypes.single { it.name == "text_general" }
        assertEquals("solr.TextField", type.className)
        assertEquals("100", type.attributes["positionIncrementGap"])
        assertEquals("solr.StandardTokenizerFactory", type.indexAnalyzer!!.tokenizer!!.className)
        assertEquals(1, type.indexAnalyzer!!.charFilters.size)
        assertEquals(2, type.indexAnalyzer!!.filters.size)
        assertEquals("index and query chains differ and must not be conflated", 1, type.queryAnalyzer!!.filters.size)
    }

    /** The resource attribute is what makes a filter's `words=` navigable to the file beside it. */
    @Test
    fun `a filter's resource attribute is readable`() {
        val type = SolrSchemaParser.parse(schema).fieldTypes.single { it.name == "text_general" }
        val stopFilter = type.indexAnalyzer!!.filters.single { it.className.endsWith("StopFilterFactory") }
        assertEquals("stopwords.txt", stopFilter.resourceAttribute)
        assertEquals("true", stopFilter.attributes["ignoreCase"])
    }

    @Test
    fun `a type with no analyzer is not analyzed`() {
        val string = SolrSchemaParser.parse(schema).fieldTypes.single { it.name == "string" }
        assertFalse(string.isAnalyzed)
        assertTrue(SolrSchemaParser.parse(schema).fieldTypes.single { it.name == "text_general" }.isAnalyzed)
    }

    /** An untyped `<analyzer>` applies to both phases; Solr allows the form and real configsets use it. */
    @Test
    fun `an untyped analyzer applies to both phases`() {
        val type = SolrSchemaParser.parse(
            """
            <schema>
              <fieldType name="t" class="solr.TextField">
                <analyzer><tokenizer class="solr.KeywordTokenizerFactory"/></analyzer>
              </fieldType>
            </schema>
            """.trimIndent(),
        ).fieldTypes.single()
        assertEquals("solr.KeywordTokenizerFactory", type.indexAnalyzer!!.tokenizer!!.className)
        assertEquals("solr.KeywordTokenizerFactory", type.queryAnalyzer!!.tokenizer!!.className)
    }

    /** Older schemas spell it `fieldtype`; matching only the modern spelling would drop them all. */
    @Test
    fun `the legacy fieldtype spelling is read`() {
        val facts = SolrSchemaParser.parse(
            """<schema><fieldtype name="legacy" class="solr.StrField"/></schema>""",
        )
        assertEquals("legacy", facts.fieldTypes.single().name)
    }

    @Test
    fun `copy fields are read with their limit`() {
        val facts = SolrSchemaParser.parse(schema)
        assertEquals(2, facts.copyFields.size)
        assertEquals(30000, facts.copyFields.first().maxChars)
        assertNull(facts.copyFields.last().maxChars)
        assertEquals("manufacturer", facts.copyFields.last().source)
    }

    @Test
    fun `the schema version is read from the root element`() {
        assertEquals("1.6", SolrSchemaParser.parse(schema).schemaVersion)
    }

    /**
     * Absent is reported as absent, not as a guess. Solr reads a missing attribute as 1.0, but that
     * substitution belongs to the model — the parser's contract is to say what the file says.
     */
    @Test
    fun `a schema declaring no version reports none`() {
        val versionless = "<schema name=\"products\"><field name=\"id\" type=\"string\"/></schema>"
        assertNull(SolrSchemaParser.parse(versionless).schemaVersion)
    }

    @Test
    fun `the unique key is read`() {
        assertEquals("id", SolrSchemaParser.parse(schema).uniqueKey)
    }

    /**
     * A half-typed file is the normal state of a file being edited, so it must yield empty facts
     * rather than an exception on the editor path.
     */
    @Test
    fun `malformed xml yields empty facts rather than throwing`() {
        assertTrue(SolrSchemaParser.parse("<schema><field name=").isEmpty)
        assertTrue(SolrSchemaParser.parse("").isEmpty)
        assertTrue(SolrSchemaParser.parse("not xml at all").isEmpty)
    }

    /** A field with no name cannot be referenced, so it contributes nothing. */
    @Test
    fun `elements missing their identifying attribute are skipped`() {
        val facts = SolrSchemaParser.parse(
            """
            <schema>
              <field type="string"/>
              <fieldType class="solr.StrField"/>
              <copyField source="a"/>
              <field name="ok" type="string"/>
            </schema>
            """.trimIndent(),
        )
        assertEquals(listOf("ok"), facts.fields.map { it.name })
        assertTrue(facts.fieldTypes.isEmpty())
        assertTrue(facts.copyFields.isEmpty())
    }

    /**
     * A field with no type is malformed, but it must stay in the model: the inspection that reports
     * exactly this needs something to report on.
     */
    @Test
    fun `a field with no type is kept with an empty type`() {
        val field = SolrSchemaParser.parse("""<schema><field name="orphan"/></schema>""").fields.single()
        assertEquals("", field.type)
    }

    /**
     * External entity resolution must stay off. A configset is project content, and a cloned
     * repository is not trusted input — an entity pointing at a local file would be read during
     * what the user experiences as opening a file.
     */
    @Test
    fun `doctype declarations are refused rather than expanded`() {
        val withDoctype = """
            <?xml version="1.0"?>
            <!DOCTYPE schema [<!ENTITY xxe SYSTEM "file:///etc/passwd">]>
            <schema><field name="id" type="string"/></schema>
        """.trimIndent()
        assertTrue("a doctype must make the document unreadable, not merely unexpanded", SolrSchemaParser.parse(withDoctype).isEmpty)
    }

    @Test
    fun `a schema declaring nothing parses to empty facts`() {
        assertTrue(SolrSchemaParser.parse("<schema name='empty'/>").isEmpty)
        assertNotNull(SolrSchemaParser.parse("<schema name='empty'/>"))
    }
}
