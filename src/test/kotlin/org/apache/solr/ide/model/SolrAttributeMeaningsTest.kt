package org.apache.solr.ide.model

import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What each attribute means, as a pure lookup.
 *
 * Plain JUnit because it is a function from two strings to prose; booting an IDE to ask what a
 * `copyField`'s `dest` means would cost a second of wall-clock for nothing. The provider wiring —
 * which caret positions reach this — is a fixture test and lives with the provider.
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
}
