package org.apache.solr.ide.repository

import org.apache.solr.ide.model.SolrFieldModel
import org.apache.solr.ide.model.SolrMatchAnalysis
import org.apache.solr.ide.model.SolrMatchGranularity
import org.apache.solr.ide.model.SolrMatchTrait
import org.apache.solr.ide.model.SolrPrefixSupport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Parses the committed demo configset — the fixture the demo runbook is written against.
 *
 * Every other parser test uses an inline string chosen to exercise one rule. This one uses the real
 * file, and exists because those two things drift apart silently: a parser that handles every
 * synthetic case and not the fixture the plugin will be demonstrated on is a parser that fails in
 * front of an audience. It also pins the planted defects, so that "someone helpfully fixed the demo"
 * fails a build rather than being discovered on stage.
 */
class DemoConfigsetTest {

    private val configsetDirectory = File(System.getProperty("user.dir"), "demo/solr/conf")

    private val model: SolrFieldModel by lazy {
        assertTrue(
            "demo configset not found at $configsetDirectory — this test reads the committed fixture",
            configsetDirectory.isDirectory,
        )
        val schema = SolrSchemaParser.parse(File(configsetDirectory, "managed-schema.xml").readText())
        val config = SolrConfigParser.parse(File(configsetDirectory, "solrconfig.xml").readText())
        SolrFieldModel.of(schema + config)
    }

    @Test
    fun `every field in the demo schema is read`() {
        assertEquals(
            setOf("id", "sku", "name", "name_prefix", "category", "description", "text"),
            model.fields.keys,
        )
        assertEquals("id", model.uniqueKey!!.effective)
        assertEquals(true, model.fields.getValue("text").effective.multiValued)
    }

    @Test
    fun `analyzer chains are read in pipeline order`() {
        val prefix = model.fieldTypes.getValue("text_prefix").effective
        assertEquals(
            listOf("solr.StandardTokenizerFactory", "solr.LowerCaseFilterFactory", "solr.EdgeNGramFilterFactory"),
            prefix.indexAnalyzer!!.components.map { it.className },
        )
        assertEquals(
            "the query chain omits the n-gram filter, which is the whole point of the type",
            listOf("solr.StandardTokenizerFactory", "solr.LowerCaseFilterFactory"),
            prefix.queryAnalyzer!!.components.map { it.className },
        )
        assertTrue("string is unanalyzed", !model.fieldTypes.getValue("string").effective.isAnalyzed)
    }

    /**
     * The planted dangling `copyField`. If this test fails, either the parser regressed or somebody
     * fixed the demo — and the demo's whole opening depends on it being broken.
     */
    @Test
    fun `the planted dangling copyField is visible in the model`() {
        val dangling = model.copyFields.map { it.effective }.filter { model.resolve(it.source) == null }
        assertEquals(listOf("manufacturer"), dangling.map { it.source })
        assertEquals(4, model.copyFields.size)
    }

    /**
     * The cross-file references the demo turns on: `qf` and `df` in `solrconfig.xml` naming fields
     * that only `managed-schema.xml` declares, with nothing in either file connecting them.
     */
    @Test
    fun `solrconfig references resolve against the schema`() {
        val references = model.fieldReferences
        assertEquals(setOf("/select", "/suggest"), references.map { it.handlerName }.toSet())

        val qf = references.filter { it.parameterName == "qf" && it.handlerName == "/select" }
        assertEquals(listOf("name", "description", "category"), qf.map { it.fieldName })
        assertEquals("3", qf.first().boost)

        assertTrue(
            "every field the demo config names must exist in the demo schema",
            references.all { model.resolve(it.fieldName) != null },
        )
    }

    /**
     * The three claims the demo puts on screen, against the real configset rather than a
     * hand-built chain. Steps 28 to 31 of the runbook say exactly this, and step 30 invites the
     * room to disagree with the middle one — so it is worth pinning that the plugin would say it.
     */
    @Test
    fun `the demo's match-capability hints are what the runbook claims`() {
        val sku = SolrMatchAnalysis.of(model.typeOf(model.resolve("sku")!!)!!)
        assertEquals(SolrMatchGranularity.WHOLE_VALUE, sku.granularity)
        assertTrue("sku is case-sensitive", sku.caseSensitive)
        assertEquals(SolrPrefixSupport.NONE, sku.prefix)

        val name = SolrMatchAnalysis.of(model.typeOf(model.resolve("name")!!)!!)
        assertEquals(SolrMatchGranularity.TOKENS, name.granularity)
        assertTrue("name is case-insensitive", !name.caseSensitive)
        assertEquals(
            "the demo turns on `wid` not matching `widget` here",
            SolrPrefixSupport.NONE,
            name.prefix,
        )

        val prefix = SolrMatchAnalysis.of(model.typeOf(model.resolve("name_prefix")!!)!!)
        assertEquals(SolrPrefixSupport.EDGE_NGRAM, prefix.prefix)
        assertEquals("EdgeNGramFilterFactory", prefix.evidenceFor(SolrMatchTrait.PREFIX))

        for (capability in listOf(sku, name, prefix)) {
            assertTrue("every demo field must be classified confidently", capability.confident)
        }
    }

    @Test
    fun `a field the schema does not declare resolves to nothing`() {
        assertNull("the demo schema declares no dynamic fields, so nothing catches this", model.resolve("title_s"))
        assertNotNull(model.resolve("name"))
    }
}
