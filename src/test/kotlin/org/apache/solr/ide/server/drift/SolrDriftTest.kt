package org.apache.solr.ide.server.drift

import org.apache.solr.ide.model.SolrAgreement
import org.apache.solr.ide.model.SolrConfigsetFacts
import org.apache.solr.ide.model.schema.SolrCopyField
import org.apache.solr.ide.model.schema.SolrDynamicField
import org.apache.solr.ide.model.schema.SolrField
import org.apache.solr.ide.model.schema.SolrFieldType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Comparing a configset against a collection.
 *
 * Plain JUnit 4: this is one data shape against another. The comparison is the whole point of the
 * two-source model, and the mistakes worth preventing are all about *silence* — a difference that
 * does not appear, or an agreement rendered as a difference.
 */
class SolrDriftTest {

    private fun field(name: String, type: String = "string", indexed: Boolean? = true, stored: Boolean? = true) =
        SolrField(name = name, type = type, indexed = indexed, stored = stored)

    private fun facts(
        fields: List<SolrField> = emptyList(),
        dynamicFields: List<SolrDynamicField> = emptyList(),
        fieldTypes: List<SolrFieldType> = emptyList(),
        copyFields: List<SolrCopyField> = emptyList(),
        uniqueKey: String? = null,
    ) = SolrConfigsetFacts(
        fields = fields,
        dynamicFields = dynamicFields,
        fieldTypes = fieldTypes,
        copyFields = copyFields,
        uniqueKey = uniqueKey,
    )

    private fun driftBetween(repository: SolrConfigsetFacts, server: SolrConfigsetFacts) =
        SolrDrift.between(repository, server)

    private fun entryNamed(drift: SolrDrift, name: String) = drift.entries.single { it.name == name }

    // --- the three categories the step names --------------------------------------------------------

    /** Declared in the repository, absent from the server: added to the schema and not deployed. */
    @Test
    fun `a field the repository declares and the server lacks is repository-only`() {
        val drift = driftBetween(facts(fields = listOf(field("subtitle"))), facts())

        val entry = entryNamed(drift, "subtitle")
        assertEquals(SolrAgreement.REPOSITORY_ONLY, entry.agreement)
        assertNotNull("the side that has it must be shown", entry.repository)
        assertNull("the side that lacks it has nothing to show", entry.server)
    }

    /** Present on the server, absent from the repository: added through the Schema API, never committed. */
    @Test
    fun `a field only the server has is server-only`() {
        val drift = driftBetween(facts(), facts(fields = listOf(field("added_via_api"))))

        val entry = entryNamed(drift, "added_via_api")
        assertEquals(SolrAgreement.SERVER_ONLY, entry.agreement)
        assertNull(entry.repository)
        assertNotNull(entry.server)
    }

    /** Present in both and different: the case with the most to say. */
    @Test
    fun `a field both have but define differently is disagreeing`() {
        val drift = driftBetween(
            facts(fields = listOf(field("title", type = "text_general"))),
            facts(fields = listOf(field("title", type = "string"))),
        )

        val entry = entryNamed(drift, "title")
        assertEquals(SolrAgreement.DISAGREEING, entry.agreement)
        assertTrue(entry.repository!!, entry.repository!!.contains("type=text_general"))
        assertTrue(entry.server!!, entry.server!!.contains("type=string"))
    }

    /**
     * Both sides are kept, and neither is resolved away.
     *
     * `SolrFact.effective` silently prefers the repository. Using it here would render a disagreeing
     * field as its repository version alone — hiding the disagreement in the one view whose purpose
     * is showing it, and doing so in a way that looks entirely correct.
     */
    @Test
    fun `a disagreement shows both sides rather than the effective one`() {
        val drift = driftBetween(
            facts(fields = listOf(field("title", indexed = true))),
            facts(fields = listOf(field("title", indexed = false))),
        )

        val entry = entryNamed(drift, "title")
        assertTrue(entry.repository!!, entry.repository!!.contains("indexed=true"))
        assertTrue(entry.server!!, entry.server!!.contains("indexed=false"))
    }

    // --- what must not appear ---------------------------------------------------------------------

    /** Agreement is not drift, and a schema that matches produces no rows at all. */
    @Test
    fun `identical schemas produce no entries`() {
        val both = facts(fields = listOf(field("id"), field("title")))

        val drift = driftBetween(both, both)

        assertTrue(drift.entries.toString(), drift.isClean)
        assertEquals(2, drift.agreeingCount)
    }

    /**
     * Two hundred agreeing fields do not become two hundred rows.
     *
     * A view that listed everything and marked three would be a view nobody reads, and the three
     * that matter would be the hardest part of it to find.
     */
    @Test
    fun `only the disagreements are listed`() {
        val shared = (1..200).map { field("field_$it") }
        val drift = driftBetween(
            facts(fields = shared + field("only_here")),
            facts(fields = shared),
        )

        assertEquals(1, drift.entries.size)
        assertEquals("only_here", drift.entries.single().name)
        assertEquals(200, drift.agreeingCount)
    }

    /**
     * A field type whose attributes arrive in a different order is not a difference.
     *
     * A map's iteration order is not a fact about the schema, and two identical types rendering
     * differently would report drift that does not exist — the failure mode that makes a drift view
     * untrustworthy, because a view that cries wolf is one users stop reading.
     */
    @Test
    fun `attribute order does not manufacture a difference`() {
        val repository = SolrFieldType("text", "solr.TextField", mapOf("a" to "1", "b" to "2"))
        val server = SolrFieldType("text", "solr.TextField", mapOf("b" to "2", "a" to "1"))

        val drift = driftBetween(facts(fieldTypes = listOf(repository)), facts(fieldTypes = listOf(server)))

        assertTrue(drift.entries.toString(), drift.isClean)
    }

    // --- every kind of declaration ------------------------------------------------------------------

    @Test
    fun `a dynamic pattern drifts like a field`() {
        val drift = driftBetween(
            facts(dynamicFields = listOf(SolrDynamicField("*_s", field("*_s")))),
            facts(),
        )

        val entry = entryNamed(drift, "*_s")
        assertEquals(SolrDriftKind.DYNAMIC_FIELD, entry.kind)
        assertEquals(SolrAgreement.REPOSITORY_ONLY, entry.agreement)
    }

    @Test
    fun `a field type drifts and names its class`() {
        val drift = driftBetween(
            facts(fieldTypes = listOf(SolrFieldType("text", "solr.TextField"))),
            facts(fieldTypes = listOf(SolrFieldType("text", "solr.StrField"))),
        )

        val entry = entryNamed(drift, "text")
        assertEquals(SolrDriftKind.FIELD_TYPE, entry.kind)
        assertTrue(entry.repository!!, entry.repository!!.contains("solr.TextField"))
        assertTrue(entry.server!!, entry.server!!.contains("solr.StrField"))
    }

    @Test
    fun `a copy-field directive drifts and names both ends`() {
        val drift = driftBetween(facts(copyFields = listOf(SolrCopyField("title", "text"))), facts())

        val entry = drift.entries.single { it.kind == SolrDriftKind.COPY_FIELD }
        assertTrue(entry.name, entry.name.contains("title"))
        assertTrue(entry.name, entry.name.contains("text"))
    }

    @Test
    fun `a unique key drifts`() {
        val drift = driftBetween(facts(uniqueKey = "id"), facts(uniqueKey = "doc_id"))

        val entry = drift.entries.single { it.kind == SolrDriftKind.UNIQUE_KEY }
        assertEquals(SolrAgreement.DISAGREEING, entry.agreement)
        assertEquals("id", entry.repository)
        assertEquals("doc_id", entry.server)
    }

    @Test
    fun `a unique key neither side declares produces no entry`() {
        val drift = driftBetween(facts(), facts())

        assertTrue(drift.entries.none { it.kind == SolrDriftKind.UNIQUE_KEY })
    }

    // --- how it reads ------------------------------------------------------------------------------

    /** A stable order, so two runs of the same comparison read the same way. */
    @Test
    fun `entries are ordered by kind then name`() {
        val drift = driftBetween(
            facts(
                fields = listOf(field("zebra"), field("alpha")),
                fieldTypes = listOf(SolrFieldType("some_type", "solr.StrField")),
            ),
            facts(),
        )

        assertEquals(listOf("alpha", "zebra", "some_type"), drift.entries.map { it.name })
    }

    @Test
    fun `the counts add up to the entries`() {
        val drift = driftBetween(
            facts(fields = listOf(field("repo_only"), field("shared", type = "string"))),
            facts(fields = listOf(field("server_only"), field("shared", type = "text_general"))),
        )

        assertEquals(
            mapOf(
                SolrAgreement.REPOSITORY_ONLY to 1,
                SolrAgreement.SERVER_ONLY to 1,
                SolrAgreement.DISAGREEING to 1,
            ),
            drift.countsByAgreement,
        )
        assertEquals(3, drift.entries.size)
    }

    /**
     * An empty configset against a populated server reports every field as server-only.
     *
     * Correct, and worth stating: it is what comparing against a configset that was never written
     * looks like, and it must not be confused with the case the class comment warns about — a
     * comparison that was never made at all. That one cannot be expressed here, because both halves
     * are required.
     */
    @Test
    fun `an empty repository against a real server is all server-only`() {
        val drift = driftBetween(facts(), facts(fields = listOf(field("id"), field("title"))))

        assertEquals(2, drift.entries.size)
        assertTrue(drift.entries.all { it.agreement == SolrAgreement.SERVER_ONLY })
        assertFalse(drift.isClean)
    }
}
