package org.apache.solr.ide.server.reading

import org.apache.solr.ide.configset.schema.parsing.SolrSchemaParser
import org.apache.solr.ide.model.SolrAgreement
import org.apache.solr.ide.model.SolrFieldModel
import org.junit.Assert.assertEquals
import tools.jackson.databind.json.JsonMapper
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Mapping a Solr schema response into the same facts a configset parser produces.
 *
 * The first tier the specification's testing strategy names: the parser correct in isolation, against
 * response bodies rather than a running server. The bodies here are trimmed from ones actually
 * returned by Solr 10.0.0 and 9.10.1 during the wire-format pass recorded in the specification, so the
 * shapes are observed rather than imagined — including the two that surprised it, an analyzer
 * component naming its factory under `name`, and a `copyFields` entry repeating its `source` rather
 * than carrying an array.
 */
class SolrServerSchemaReaderTest {

    private val body = """
        {"responseHeader":{"status":0,"QTime":3},
         "schema":{
           "name":"example",
           "version":1.7,
           "uniqueKey":"id",
           "fieldTypes":[
             {"name":"string","class":"solr.StrField","sortMissingLast":true,"docValues":true},
             {"name":"text_general","class":"solr.TextField","positionIncrementGap":"100",
              "indexAnalyzer":{
                "tokenizer":{"name":"standard"},
                "filters":[{"name":"stop","ignoreCase":"true","words":"stopwords.txt"},
                           {"name":"lowercase"}]},
              "queryAnalyzer":{
                "tokenizer":{"class":"solr.StandardTokenizerFactory"},
                "filters":[{"class":"solr.LowerCaseFilterFactory"}]}}],
           "fields":[
             {"name":"id","type":"string","multiValued":false,"indexed":true,"required":true,"stored":true},
             {"name":"name","type":"text_general","uninvertible":true,"indexed":true,"stored":true},
             {"name":"price","type":"pfloat","indexed":true,"stored":true}],
           "dynamicFields":[
             {"name":"*_s","type":"string","indexed":true,"stored":true}],
           "copyFields":[
             {"source":"name","dest":"text"},
             {"source":"name","dest":"suggest"}]}}
    """.trimIndent()

    private val facts = SolrServerSchemaReader.read(body)

    private val mapper = JsonMapper.builder().build()

    // --- the straightforward half ------------------------------------------------------------------

    @Test
    fun `fields are read with their declared properties`() {
        val id = facts.fields.single { it.name == "id" }
        assertEquals("string", id.type)
        assertEquals(true, id.indexed)
        assertEquals(true, id.required)
        assertEquals(false, id.multiValued)
    }

    /**
     * A property the schema did not declare stays unset rather than becoming false.
     *
     * Solr reports what was declared, not the type's effective defaults — verified against a live
     * server — so an absent key is genuinely "unset" and must not be read as a positive `false`.
     */
    @Test
    fun `a property the schema never declared is unset`() {
        assertNull(facts.fields.single { it.name == "price" }.multiValued)
    }

    @Test
    fun `the unique key is read`() {
        assertEquals("id", facts.uniqueKey)
    }

    @Test
    fun `a dynamic field carries its pattern`() {
        val dynamic = facts.dynamicFields.single()
        assertEquals("*_s", dynamic.pattern)
        assertEquals("string", dynamic.field.type)
    }

    /**
     * Several destinations for one source arrive as several entries, not as an array.
     *
     * Verified against both supported lines: `dest` is always a string, and a source with two
     * destinations repeats itself. That is already the repository parser's shape, so no expansion
     * step exists and this test is what would notice if one were added.
     */
    @Test
    fun `a source with two destinations is two copy fields`() {
        val fromName = facts.copyFields.filter { it.source == "name" }
        assertEquals(listOf("text", "suggest"), fromName.map { it.destination })
    }

    // --- the parts the wire-format pass corrected ---------------------------------------------------

    /**
     * A component naming its factory under `name` is read, and so is one naming it under `class`.
     *
     * Solr echoes back whichever spelling the schema used and normalizes neither. Every configset
     * Solr ships uses `name`; the plugin's own repository parser read only `class` until a defect fix
     * taught it both. A reader that handled one would be half-blind in exactly the same way.
     */
    @Test
    fun `an analyzer component is read under either spelling`() {
        val type = facts.fieldTypes.single { it.name == "text_general" }
        assertEquals("standard", type.indexAnalyzer?.tokenizer?.className)
        assertEquals(listOf("stop", "lowercase"), type.indexAnalyzer?.filters?.map { it.className })
        assertEquals("solr.StandardTokenizerFactory", type.queryAnalyzer?.tokenizer?.className)
    }

    /** A component's other keys are its factory arguments, and they travel with it. */
    @Test
    fun `a component keeps its factory arguments`() {
        val stop = facts.fieldTypes.single { it.name == "text_general" }
            .indexAnalyzer?.filters?.single { it.className == "stop" }
        assertEquals("stopwords.txt", stop?.attributes?.get("words"))
        assertEquals("true", stop?.attributes?.get("ignoreCase"))
    }

    /**
     * JSON booleans reach the attribute map as the strings the XML side puts there.
     *
     * `SolrField` stores its five named flags twice — as typed `Boolean?` properties and again in
     * `attributes`, which the repository parser fills with raw attribute text. A reader populating
     * only the first would produce facts that agree in their properties and differ in their maps.
     */
    @Test
    fun `a boolean also reaches the attribute map as text`() {
        val id = facts.fields.single { it.name == "id" }
        assertEquals("true", id.attributes["indexed"])
        assertEquals("false", id.attributes["multiValued"])
    }

    @Test
    fun `a field type carries its class and its other attributes`() {
        val string = facts.fieldTypes.single { it.name == "string" }
        assertEquals("solr.StrField", string.className)
        assertEquals("true", string.attributes["docValues"])
    }

    // --- what this parser deliberately does not populate --------------------------------------------

    /**
     * The schema version and the Lucene match version are the repository's to state.
     *
     * The specification argues both out: `SolrFieldModel.of` reads `schemaVersion` from the repository
     * half deliberately, and a server's own Solr version is a separate fact carried separately.
     * Populating either here would create a value that looks consumed and is discarded.
     */
    @Test
    fun `the version fields are left to the repository`() {
        assertNull(facts.schemaVersion)
        assertNull(facts.luceneMatchVersion)
    }

    /** A server reports its configuration, not the file that produced it. */
    @Test
    fun `field references are always empty`() {
        assertTrue(facts.fieldReferences.isEmpty())
    }

    // --- malformed input ----------------------------------------------------------------------------

    /** A body that is not a schema response yields empty facts rather than throwing. */
    @Test
    fun `a response with no schema object is empty`() {
        val empty = SolrServerSchemaReader.read("""{"responseHeader":{"status":0}}""")
        assertTrue(empty.fields.isEmpty())
        assertTrue(empty.fieldTypes.isEmpty())
    }

    /** Text that is not JSON at all is the 404 HTML case, and is empty rather than an exception. */
    @Test
    fun `a body that is not json is empty`() {
        val empty = SolrServerSchemaReader.read("<html><body>Searching for Solr?</body></html>")
        assertTrue(empty.fields.isEmpty())
    }

    // --- the reason the type is shared -------------------------------------------------------------

    /**
     * The facts merge with a repository half and the agreement states come out right.
     *
     * This is what the whole shape decision is for. `SolrConfigsetFacts` is symmetric so that
     * `SolrFieldModel.of(repository, server)` can merge two instances without privileging either, and
     * every model built so far has passed `server = null`. A reader producing something almost-right
     * would still satisfy every assertion above; only merging it proves the type is the same type.
     */
    @Test
    fun `the facts merge with a repository half into a two-source model`() {
        val repository = SolrSchemaParser.parse(
            """
            <schema name="example" version="1.7">
              <fieldType name="string" class="solr.StrField"/>
              <field name="id" type="string" indexed="true" stored="true" required="true" multiValued="false"/>
              <field name="onlyInRepository" type="string" indexed="true"/>
              <uniqueKey>id</uniqueKey>
            </schema>
            """.trimIndent(),
        )
        val model = SolrFieldModel.of(repository, facts)

        assertEquals(SolrAgreement.AGREEING, model.fields["id"]?.agreement)
        assertEquals(SolrAgreement.REPOSITORY_ONLY, model.fields["onlyInRepository"]?.agreement)
        assertEquals(SolrAgreement.SERVER_ONLY, model.fields["price"]?.agreement)
    }
    // --- what the transport hands over ---------------------------------------------------------------

    /**
     * The reader takes the tree the transport already parsed, not the text again.
     *
     * The transport parses a body to classify it — a Solr error is found by reading `error.msg` out
     * of the same JSON. Handing the reader the string would parse it twice, and worse, would let the
     * two disagree about whether a body was readable at all.
     */
    @Test
    fun `the reader accepts an already-parsed tree`() {
        val tree = mapper.readTree(body)
        assertEquals(SolrServerSchemaReader.read(body).fields, SolrServerSchemaReader.read(tree).fields)
    }

    /** The version a server reports comes from the system-info response, not the schema. */
    @Test
    fun `the reported solr version is read from a system info response`() {
        val body = """
            {"responseHeader":{"status":0},"mode":"solrcloud",
             "lucene":{"solr-spec-version":"10.0.0","lucene-spec-version":"10.3.2"}}
        """.trimIndent()

        assertEquals("10.0.0", SolrServerSchemaReader.solrVersionIn(mapper.readTree(body)))
    }

    /**
     * The neighbouring key is the Lucene version and must not be read as Solr's.
     *
     * They differ by one word, sit next to each other, and both look like the answer — Solr 10.0.0
     * reports Lucene 10.3.2. Reading the wrong one produces a major that happens to match today and
     * stops matching the day the lines diverge.
     */
    @Test
    fun `the lucene version beside it is not mistaken for solr's`() {
        val body = """{"lucene":{"solr-spec-version":"9.10.1","lucene-spec-version":"9.12.3"}}"""

        assertEquals("9.10.1", SolrServerSchemaReader.solrVersionIn(mapper.readTree(body)))
    }

    /** A response without the key yields null rather than a guess. */
    @Test
    fun `a response naming no version reports none`() {
        assertNull(SolrServerSchemaReader.solrVersionIn(mapper.readTree("""{"mode":"std"}""")))
    }
}
