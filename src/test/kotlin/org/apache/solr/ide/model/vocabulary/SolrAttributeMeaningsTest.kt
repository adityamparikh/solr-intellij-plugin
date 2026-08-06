package org.apache.solr.ide.model.vocabulary

import org.apache.solr.ide.model.schema.SolrTypeDefaultRule
import org.apache.solr.ide.model.schema.SolrTypeTrait
import org.apache.solr.ide.model.schema.SolrSchemaVersion
import org.apache.solr.ide.model.schema.SolrFieldProperties
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What each attribute means, as a pure lookup.
 *
 * Plain JUnit because it is a function from two strings to prose; booting an IDE to ask what
 * `minGramSize` does would cost a second of wall-clock for nothing. The provider wiring — which
 * caret positions reach this — is a fixture test and lives with the provider.
 */
class SolrAttributeMeaningsTest {

    @Test
    fun `a field's name is described as the name documents use`() {
        val meaning = SolrAttributeMeanings.of("field", "name")!!
        assertTrue(meaning, "document" in meaning.lowercase())
    }

    /**
     * **The reason the table is keyed by the pair.** `name` on a `copyField` is not a field name,
     * and describing it as one would be worse than saying nothing.
     */
    @Test
    fun `name means something different on a field than on a dynamic field`() {
        assertNotEquals(
            SolrAttributeMeanings.of("field", "name"),
            SolrAttributeMeanings.of("dynamicField", "name"),
        )
        assertTrue("*" in SolrAttributeMeanings.of("dynamicField", "name")!!)
    }

    @Test
    fun `a copyField's two ends are told apart`() {
        val source = SolrAttributeMeanings.of("copyField", "source")!!
        val destination = SolrAttributeMeanings.of("copyField", "dest")!!
        assertNotEquals(source, destination)
    }

    @Test
    fun `a field's type is described as naming a declared field type`() {
        assertTrue("fieldType" in SolrAttributeMeanings.of("field", "type")!!)
    }

    @Test
    fun `a field type's class is described as the implementing class`() {
        assertTrue("class" in SolrAttributeMeanings.of("fieldType", "class")!!.lowercase())
    }

    /** An attribute the plugin does not model on that element answers nothing at all. */
    @Test
    fun `an unmodelled pair has no meaning`() {
        assertNull(SolrAttributeMeanings.of("copyField", "name"))
        assertNull(SolrAttributeMeanings.of("field", "sortMissingLast"))
        assertNull(SolrAttributeMeanings.of("bean", "name"))
    }

    @Test
    fun `the schema's name is described as carrying no behaviour`() {
        assertTrue(SolrAttributeMeanings.of("schema", "name")!!.isNotEmpty())
    }

    // --- factory attributes ---

    @Test
    fun `an n-gram's bounds say what they do rather than what type they are`() {
        val minimum = SolrAttributeMeanings.ofFactoryAttribute("minGramSize")!!
        val maximum = SolrAttributeMeanings.ofFactoryAttribute("maxGramSize")!!
        assertNotEquals(minimum, maximum)
        assertTrue(minimum, "shortest" in minimum.lowercase())
        assertTrue(maximum, "longest" in maximum.lowercase())
    }

    /**
     * **The bound that keeps the table from becoming the thing it is not.** An attribute nobody has
     * written prose for keeps exactly the popup it has today — owner, value type, default — rather
     * than gaining an empty *Meaning* row.
     */
    @Test
    fun `an attribute the table does not list has no meaning`() {
        assertNull(SolrAttributeMeanings.ofFactoryAttribute("luceneMatchVersion"))
        assertNull(SolrAttributeMeanings.ofFactoryAttribute("somethingNobodyWrote"))
    }

    // --- schema version ---

    /**
     * The version answers specifically, because the model already computes what it decides for
     * every field-property popup. Saying only "it decides defaults" would be the label problem
     * this step exists to fix, one level up.
     */
    @Test
    fun `schema version 1_7 reports the defaults it turned over`() {
        val meaning = SolrAttributeMeanings.ofSchemaVersion(SolrSchemaVersion(1.7f))
        assertTrue(meaning, "docValues" in meaning)
        assertTrue(meaning, "uninvertible" in meaning)
    }

    @Test
    fun `an older schema version reports different defaults from a newer one`() {
        assertNotEquals(
            SolrAttributeMeanings.ofSchemaVersion(SolrSchemaVersion(1.6f)),
            SolrAttributeMeanings.ofSchemaVersion(SolrSchemaVersion(1.7f)),
        )
    }

    /** A schema declaring no version is running 1.0's defaults, and the popup says so. */
    @Test
    fun `an absent version is reported as the assumed one`() {
        val meaning = SolrAttributeMeanings.ofSchemaVersion(SolrSchemaVersion.ASSUMED)
        assertTrue(meaning, "1.0" in meaning)
    }

    /**
     * **The anti-drift check, and the reason this sentence reads its answers rather than stating
     * them.** It renders beside the field-property popup that resolves the same defaults from
     * [SolrFieldProperties], so a threshold copied into `SolrAttributeMeanings` would let the two
     * contradict each other on one screen. Recomputing the expectation from the property here fails
     * the moment a literal comes back, at whichever version the copy and the model disagree.
     */
    @Test
    fun `each purely versioned default agrees with the property that owns it`() {
        for (name in listOf("uninvertible", "autoGeneratePhraseQueries")) {
            val range = SolrFieldProperties.byName(name)!!.defaultTrueWithin!!
            for (declared in listOf(1.0f, 1.1f, 1.4f, 1.5f, 1.6f, 1.7f)) {
                val version = SolrSchemaVersion(declared)
                val expected = if (version in range) "on" else "off"
                val meaning = SolrAttributeMeanings.ofSchemaVersion(version)
                assertTrue(
                    "at $declared, $name should read $expected: $meaning",
                    "<code>$name</code> defaults $expected" in meaning,
                )
            }
        }
    }

    /**
     * `docValues` is the one default the version does not settle alone, so it is the one that must
     * not be stated flatly. [SolrTypeDefaultRule.DOC_VALUES] opens the gate at 1.7 for a type that
     * supports doc values, while a sortable text type sets the bit at every version — so a bare
     * "defaults off" would be false for that type on exactly the older schemas this popup is most
     * often read on.
     */
    @Test
    fun `the docValues default is qualified rather than stated flatly`() {
        val meaning = SolrAttributeMeanings.ofSchemaVersion(SolrSchemaVersion(1.6f))
        assertTrue(meaning, "<code>docValues</code> defaults off for the types that support it" in meaning)
        assertTrue(meaning, "sortable text" in meaning)
    }

    /** And the gate itself is the model's, not a number in the meanings table. */
    @Test
    fun `the docValues gate follows the type default rule`() {
        val supported = setOf(SolrTypeTrait.DOC_VALUES_BY_DEFAULT)
        for (declared in listOf(1.6f, 1.7f)) {
            val version = SolrSchemaVersion(declared)
            val expected = if (SolrTypeDefaultRule.DOC_VALUES.holdsFor(supported, version)) "on" else "off"
            assertTrue(
                "at $declared, docValues should read $expected",
                "<code>docValues</code> defaults $expected" in SolrAttributeMeanings.ofSchemaVersion(version),
            )
        }
    }
}
