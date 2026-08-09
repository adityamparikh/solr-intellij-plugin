package org.apache.solr.ide.configset.parsing

import org.apache.solr.ide.model.SolrFieldModel
import org.apache.solr.ide.model.schema.SolrMatchAnalysis
import org.apache.solr.ide.model.schema.SolrMatchGranularity
import org.apache.solr.ide.model.schema.SolrMatchTrait
import org.apache.solr.ide.model.schema.SolrPrefixSupport
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

    /**
     * The committed demo configset, at the path the build told the test rather than one inferred
     * from where the JVM happened to start. `user.dir` remains the fallback for a runner that does
     * not set the property; it is the project directory under Gradle, but that is a launcher's
     * choice rather than a fact about the repository.
     */
    private val configsetDirectory = System.getProperty("demo.configset.dir")?.let(::File)
        ?: File(System.getProperty("user.dir"), "demo/solr/conf")

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
            setOf("id", "sku", "name", "name_prefix", "category", "description", "text", "notes", "legacy"),
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

    /**
     * The two planted silences the inlay hint shows, pinned at the model level rather than through
     * the hint provider — [SolrMatchInlayHintsProviderTest][org.apache.solr.ide.configset.hint.SolrMatchInlayHintsProviderTest]
     * covers the rendering. `notes`' analyser names a factory the plugin does not recognise, so match
     * analysis must not be confident about it; `legacy` names a type the schema never declares, so it
     * has no field type to analyse at all.
     */
    @Test
    fun `notes and legacy demonstrate the hint's two different silences`() {
        val notesType = model.typeOf(model.resolve("notes")!!)
        assertNotNull("notes must resolve to the custom_text field type", notesType)
        val notes = SolrMatchAnalysis.of(notesType!!)
        assertTrue("an unrecognised factory must not be classified confidently", !notes.confident)

        assertNull("legacy's type is undeclared, so it has no field type to resolve", model.typeOf(model.resolve("legacy")!!))
    }

    /**
     * The demo's opening, as the inspections see it. Demo step 25 puts the dangling-copyField
     * underline on screen; `legacy`'s undeclared type is the fixture's second planted finding, added
     * for [SolrMatchInlayHintsProvider][org.apache.solr.ide.configset.hint.SolrMatchInlayHintsProvider]'s
     * two different silences. Worth pinning that exactly these two things in the committed fixture
     * are reportable — and which, so a third finding anywhere else is caught here rather than on
     * stage.
     */
    @Test
    fun `exactly two things in the demo configset are reportable`() {
        val danglingCopyFields = model.copyFields.map { it.effective }
            .filter { '*' !in it.source && model.resolve(it.source) == null }
            .map { it.source }
        assertEquals(listOf("manufacturer"), danglingCopyFields)

        val undeclaredTypes = model.fields.values.map { it.effective }
            .filter { it.type.isNotEmpty() && !model.fieldTypes.containsKey(it.type) }
            .map { it.name }
        assertEquals(listOf("legacy"), undeclaredTypes)

        val unknownReferences = model.fieldReferences
            .filter { it.fieldName != "score" && model.resolve(it.fieldName) == null }
        assertTrue("no handler parameter names a missing field: $unknownReferences", unknownReferences.isEmpty())
    }

    @Test
    fun `a field no declaration supplies resolves to nothing`() {
        assertNull("the demo declares only `*_t`, so a `_s` name has nothing to match", model.resolve("title_s"))
        assertNotNull(model.resolve("name"))
    }

    /**
     * The planted dynamic field, and the reference that only it supplies.
     *
     * `body_t` is named by the `/select` handler's `pf` and declared by nothing, so it resolves
     * through the pattern the way Solr resolves it. That pairing is the demo's only cross-file
     * reference whose target is a glob — and the only place a usage search has to reach a name the
     * pattern *supplies* rather than one that spells it, which is what
     * [declarations as targets](../../../../../../../docs/design/pending/2026-08-04-declaration-targets/design.md)
     * needs a fixture for. Pinned here so that removing either half fails a build.
     */
    @Test
    fun `the planted dynamic field supplies the name solrconfig references`() {
        assertEquals(setOf("*_t"), model.dynamicFields.keys)

        val resolved = model.resolve("body_t")
        assertNotNull("body_t must resolve through the *_t pattern", resolved)
        assertEquals("*_t", resolved!!.name)

        assertTrue(
            "the /select handler's pf must still name body_t, or the pattern has nothing referencing it",
            model.fieldReferences.any {
                it.fieldName == "body_t" && it.handlerName == "/select" && it.parameterName == "pf"
            },
        )
    }
}
