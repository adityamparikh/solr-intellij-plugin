package org.apache.solr.ide.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Merge and lookup rules for [SolrFieldModel].
 *
 * The server half is synthesized here rather than fetched. The server reader has not landed, and
 * waiting for it to test the merge would mean building the seam blind — the point of building it now
 * is that it can be proven now.
 */
class SolrFieldModelTest {

    private val id = SolrField("id", "string", indexed = true, required = true)
    private val name = SolrField("name", "text_general", indexed = true)
    private val price = SolrField("price", "pdouble", indexed = true)

    private fun repositoryOf(vararg fields: SolrField) = SolrConfigsetFacts(fields = fields.toList())

    // --- the four agreement states -----------------------------------------------------------

    @Test
    fun `a field only the repository declares is repository only`() {
        val model = SolrFieldModel.of(repositoryOf(id))
        assertEquals(SolrAgreement.REPOSITORY_ONLY, model.fields.getValue("id").agreement)
    }

    @Test
    fun `a field only the server has is server only`() {
        val model = SolrFieldModel.of(repository = null, server = repositoryOf(price))
        assertEquals(SolrAgreement.SERVER_ONLY, model.fields.getValue("price").agreement)
    }

    @Test
    fun `identical declarations agree`() {
        val model = SolrFieldModel.of(repositoryOf(id), repositoryOf(id))
        assertEquals(SolrAgreement.AGREEING, model.fields.getValue("id").agreement)
    }

    /** The state worth showing a user: deployed and committed versions of the same field differ. */
    @Test
    fun `differing declarations disagree`() {
        val deployed = id.copy(required = false)
        val model = SolrFieldModel.of(repositoryOf(id), repositoryOf(deployed))
        val fact = model.fields.getValue("id")
        assertEquals(SolrAgreement.DISAGREEING, fact.agreement)
        assertEquals(id, fact.repository)
        assertEquals(deployed, fact.server)
    }

    @Test
    fun `disagreements are collected across every kind of fact`() {
        val model = SolrFieldModel.of(
            SolrConfigsetFacts(fields = listOf(id, name), fieldTypes = listOf(SolrFieldType("string", "solr.StrField"))),
            SolrConfigsetFacts(
                fields = listOf(id.copy(stored = false), name),
                fieldTypes = listOf(SolrFieldType("string", "solr.TextField")),
            ),
        )
        assertEquals(2, model.disagreements.size)
    }

    /** The editor reasons about the file in front of the user, so the repository wins a tie. */
    @Test
    fun `the repository is the effective version when the two differ`() {
        val model = SolrFieldModel.of(repositoryOf(id), repositoryOf(id.copy(type = "text_general")))
        assertEquals("string", model.fields.getValue("id").effective.type)
    }

    @Test
    fun `a fact known to neither source cannot be constructed`() {
        val thrown = runCatching { SolrFact<SolrField>(null, null) }.exceptionOrNull()
        assertTrue("expected construction to fail", thrown is IllegalArgumentException)
    }

    // --- the repository-only reality the plugin ships with -----------------------------------

    @Test
    fun `with no server half every fact is repository only`() {
        val model = SolrFieldModel.of(repositoryOf(id, name))
        assertTrue(model.fields.values.all { it.agreement == SolrAgreement.REPOSITORY_ONLY })
        assertTrue("a model with no server half has no drift to report", model.disagreements.isEmpty())
    }

    @Test
    fun `merging two null halves yields an empty model`() {
        val model = SolrFieldModel.of(null, null)
        assertTrue(model.fields.isEmpty())
        assertNull(model.uniqueKey)
    }

    // --- dynamic fields ----------------------------------------------------------------------

    private val dynamicModel = SolrFieldModel.of(
        SolrConfigsetFacts(
            fields = listOf(name),
            dynamicFields = listOf(
                SolrDynamicField("*_s", SolrField("*_s", "string")),
                SolrDynamicField("*_str", SolrField("*_str", "strings")),
                SolrDynamicField("attr_*", SolrField("attr_*", "text_general")),
                SolrDynamicField("*", SolrField("*", "ignored")),
            ),
        ),
    )

    @Test
    fun `a declared field beats every dynamic pattern`() {
        assertEquals("text_general", dynamicModel.resolve("name")!!.type)
    }

    /** Solr resolves against the longest matching pattern, so `*_str` beats `*_s` and `*`. */
    @Test
    fun `the most specific dynamic pattern wins`() {
        assertEquals("strings", dynamicModel.resolve("title_str")!!.type)
        assertEquals("string", dynamicModel.resolve("title_s")!!.type)
        assertEquals("text_general", dynamicModel.resolve("attr_colour")!!.type)
    }

    @Test
    fun `a catch-all pattern still matches when nothing better does`() {
        assertEquals("ignored", dynamicModel.resolve("anything_at_all")!!.type)
    }

    @Test
    fun `an unmatched name resolves to nothing when there is no catch-all`() {
        val model = SolrFieldModel.of(
            SolrConfigsetFacts(dynamicFields = listOf(SolrDynamicField("*_i", SolrField("*_i", "pint")))),
        )
        assertNull(model.resolve("title"))
        assertEquals("pint", model.resolve("count_i")!!.type)
    }

    /** A `dynamicField` with no wildcard is legal, and matches only itself. */
    @Test
    fun `a pattern with no wildcard matches exactly`() {
        val exact = SolrDynamicField("literal", SolrField("literal", "string"))
        assertTrue(exact.matches("literal"))
        assertTrue(!exact.matches("literal_s"))
    }

    // --- lookups the features are built on ---------------------------------------------------

    @Test
    fun `a field resolves to its type, and to null when the type is undeclared`() {
        val model = SolrFieldModel.of(
            SolrConfigsetFacts(fields = listOf(id), fieldTypes = listOf(SolrFieldType("string", "solr.StrField"))),
        )
        assertEquals("solr.StrField", model.typeOf(id)!!.className)
        assertNull("a dangling type reference is a finding, not an error", model.typeOf(name))
    }

    @Test
    fun `copy fields are found by source, including through a glob`() {
        val model = SolrFieldModel.of(
            SolrConfigsetFacts(
                copyFields = listOf(
                    SolrCopyField("name", "text"),
                    SolrCopyField("*_t", "text"),
                    SolrCopyField("description", "text"),
                ),
            ),
        )
        assertEquals(listOf("text"), model.copyFieldsFrom("name").map { it.destination })
        assertEquals(listOf("text"), model.copyFieldsFrom("body_t").map { it.destination })
        assertTrue(model.copyFieldsFrom("unrelated").isEmpty())
    }

    @Test
    fun `copy fields with the same source but different destinations are distinct facts`() {
        val model = SolrFieldModel.of(
            SolrConfigsetFacts(copyFields = listOf(SolrCopyField("name", "text"), SolrCopyField("name", "name_prefix"))),
        )
        assertEquals(2, model.copyFields.size)
    }

    @Test
    fun `the unique key merges like any other fact`() {
        assertEquals(SolrAgreement.DISAGREEING, SolrFieldModel.of(
            SolrConfigsetFacts(uniqueKey = "id"),
            SolrConfigsetFacts(uniqueKey = "sku"),
        ).uniqueKey!!.agreement)
    }

    /**
     * A server reports its configuration, not the file that produced it, so it has no
     * `solrconfig.xml` references to contribute. The repository's survive the merge intact.
     */
    @Test
    fun `field references come from the repository half only`() {
        val model = SolrFieldModel.of(
            SolrConfigsetFacts(fieldReferences = listOf(SolrFieldReference("/select", "qf", "name", "3"))),
            SolrConfigsetFacts(fields = listOf(name)),
        )
        assertEquals(1, model.fieldReferences.size)
        assertEquals("name", model.fieldReferences.single().fieldName)
    }

    // --- solr version and type traits ---------------------------------------------------------

    @Test
    fun `the solr line is derived from the configsets luceneMatchVersion`() {
        val model = SolrFieldModel(luceneMatchVersion = "9.8.0")
        assertEquals(SolrVersionSelection.fromLuceneMatchVersion("9.8.0"), model.solrVersion)
    }

    @Test
    fun `a configset declaring no luceneMatchVersion falls back to the default line`() {
        assertEquals(SolrVersionSelection.DEFAULT, SolrFieldModel().solrVersion)
    }

    @Test
    fun `a known field type class yields the catalogs traits for it`() {
        val model = SolrFieldModel(luceneMatchVersion = "10.0.0")
        val traits = model.traitsOf(SolrFieldType("string", "solr.StrField"))
        assertNotNull("solr.StrField is in the catalog", traits)
    }

    /**
     * The distinction the whole trait resolution rests on. An empty set says "known class, no traits",
     * which makes a type-dependent default a definite false. Null says nothing is known. Collapsing
     * the two makes every custom plugin type report a confident wrong omitNorms.
     */
    @Test
    fun `a class the catalog does not carry yields null rather than no traits`() {
        val model = SolrFieldModel(luceneMatchVersion = "10.0.0")
        assertNull(model.traitsOf(SolrFieldType("mystery", "com.example.MysteryFieldType")))
    }

    @Test
    fun `a type naming no class at all yields null`() {
        assertNull(SolrFieldModel().traitsOf(SolrFieldType("nameless", "")))
    }

    @Test
    fun `no type at all yields null`() {
        assertNull(SolrFieldModel().traitsOf(null))
    }

    /**
     * A tokenizer factory is in the catalog but is not a field type, and its traits are meaningless
     * as a field's. Reading them would attribute a tokenizer's properties to a field.
     */
    @Test
    fun `a class that is in the catalog but is not a field type yields null`() {
        val model = SolrFieldModel(luceneMatchVersion = "10.0.0")
        assertNull(model.traitsOf(SolrFieldType("wrong", "solr.StandardTokenizerFactory")))
    }
}
