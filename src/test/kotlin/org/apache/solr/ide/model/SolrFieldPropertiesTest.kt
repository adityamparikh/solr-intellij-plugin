package org.apache.solr.ide.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Property resolution — the three-level answer no external documentation can give.
 *
 * The Reference Guide can say what `omitNorms` defaults to. Only this can say what it is for a
 * particular field, and whether that came from the field, from its type, or from Solr.
 */
class SolrFieldPropertiesTest {

    private val stringType = SolrFieldType(
        name = "string",
        className = "solr.StrField",
        attributes = mapOf("sortMissingLast" to "true", "docValues" to "true"),
    )

    private val field = SolrField(
        name = "sku",
        type = "string",
        attributes = mapOf("indexed" to "true", "stored" to "false"),
    )

    private fun resolve(name: String, field: SolrField = this.field, type: SolrFieldType? = stringType) =
        SolrFieldProperties.resolve(SolrFieldProperties.byName(name)!!, field, type)

    @Test
    fun `a property declared on the field comes from the field`() {
        val stored = resolve("stored")
        assertEquals("false", stored.value)
        assertEquals(SolrPropertyOrigin.FIELD, stored.origin)
    }

    /** The inheritance that makes an unset attribute different from a false one. */
    @Test
    fun `a property declared only on the type is inherited from it`() {
        val sortMissingLast = resolve("sortMissingLast")
        assertEquals("true", sortMissingLast.value)
        assertEquals(SolrPropertyOrigin.FIELD_TYPE, sortMissingLast.origin)
    }

    @Test
    fun `the field wins over the type when both declare it`() {
        val withOverride = field.copy(attributes = field.attributes + ("docValues" to "false"))
        val docValues = resolve("docValues", withOverride)
        assertEquals("false", docValues.value)
        assertEquals(SolrPropertyOrigin.FIELD, docValues.origin)
    }

    @Test
    fun `a property declared nowhere falls back to Solr's default`() {
        val multiValued = resolve("multiValued")
        assertEquals("false", multiValued.value)
        assertEquals(SolrPropertyOrigin.SOLR_DEFAULT, multiValued.origin)
    }

    /**
     * The honesty case. `omitNorms` defaults true for primitive types and false for text, so a
     * single asserted default would be wrong half the time — and a confidently wrong statement in
     * documentation is how a plugin stops being trusted.
     */
    @Test
    fun `a property whose default depends on the field type says so instead of guessing`() {
        val omitNorms = resolve("omitNorms")
        assertNull(omitNorms.value)
        assertEquals(SolrPropertyOrigin.UNDETERMINED, omitNorms.origin)
    }

    @Test
    fun `an undetermined default is still resolved when the type declares one`() {
        val typeDeclares = stringType.copy(attributes = stringType.attributes + ("omitNorms" to "true"))
        val omitNorms = resolve("omitNorms", type = typeDeclares)
        assertEquals("true", omitNorms.value)
        assertEquals(SolrPropertyOrigin.FIELD_TYPE, omitNorms.origin)
    }

    @Test
    fun `resolution works with no field type at all`() {
        val indexed = resolve("indexed", type = null)
        assertEquals("true", indexed.value)
        assertEquals(SolrPropertyOrigin.FIELD, indexed.origin)
        assertEquals(SolrPropertyOrigin.SOLR_DEFAULT, resolve("multiValued", type = null).origin)
    }

    @Test
    fun `every property the guide documents is present and described`() {
        for (expected in listOf(
            "indexed", "stored", "docValues", "multiValued", "required", "omitNorms",
            "termVectors", "sortMissingFirst", "sortMissingLast", "uninvertible",
            "useDocValuesAsStored", "large", "omitTermFreqAndPositions", "omitPositions",
        )) {
            val property = SolrFieldProperties.byName(expected)
            assertNotNull("$expected should be known", property)
            assertTrue("$expected needs a summary", property!!.summary.isNotBlank())
            assertTrue("$expected needs its accepted values", property.validValues.isNotBlank())
        }
    }

    @Test
    fun `an unknown attribute is not a known property`() {
        assertNull(SolrFieldProperties.byName("notAProperty"))
    }

    @Test
    fun `every property resolves for a bare field`() {
        val bare = SolrField("x", "string")
        val resolved = SolrFieldProperties.effectiveFor(bare, null)
        assertEquals(SolrFieldProperties.ALL.size, resolved.size)
        assertTrue(
            "a bare field takes everything from defaults or leaves it undetermined",
            resolved.all { it.origin == SolrPropertyOrigin.SOLR_DEFAULT || it.origin == SolrPropertyOrigin.UNDETERMINED },
        )
    }
}
