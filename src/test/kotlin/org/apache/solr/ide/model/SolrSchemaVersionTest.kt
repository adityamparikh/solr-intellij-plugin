package org.apache.solr.ide.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The `version` attribute on a schema's root element, which is not either version the plugin
 * already tracks.
 *
 * The value decides what several field attributes default to, so reading it wrongly is not a
 * cosmetic error — it is the difference between reporting `uninvertible` as true or false on the
 * same file.
 */
class SolrSchemaVersionTest {

    @Test
    fun `a declared version is read as written`() {
        assertEquals(1.6f, SolrSchemaVersion.of("1.6").declared, 0.0001f)
        assertEquals(1.7f, SolrSchemaVersion.of("1.7").declared, 0.0001f)
    }

    /**
     * Solr's own fallback, from `IndexSchema`: a schema with no `version` attribute is read as 1.0,
     * not as the newest. Assuming the newest would report modern defaults for a file that Solr is
     * running under 2008 semantics.
     */
    @Test
    fun `an absent version is 1_0, not the newest`() {
        assertEquals(1.0f, SolrSchemaVersion.of(null).declared, 0.0001f)
    }

    /** A half-typed file is the normal state of a file being edited, and must not throw. */
    @Test
    fun `an unparseable version falls back rather than failing`() {
        assertEquals(1.0f, SolrSchemaVersion.of("").declared, 0.0001f)
        assertEquals(1.0f, SolrSchemaVersion.of("1.").declared, 0.0001f)
        assertEquals(1.0f, SolrSchemaVersion.of("not-a-version").declared, 0.0001f)
    }

    @Test
    fun `surrounding whitespace does not change the version`() {
        assertEquals(1.7f, SolrSchemaVersion.of(" 1.7 ").declared, 0.0001f)
    }

    @Test
    fun `a range includes its lower bound and excludes its upper`() {
        val fromSix = SolrVersionRange(from = 1.6f)
        assertFalse(SolrSchemaVersion.of("1.5") in fromSix)
        assertTrue(SolrSchemaVersion.of("1.6") in fromSix)
        assertTrue(SolrSchemaVersion.of("1.7") in fromSix)

        val belowSeven = SolrVersionRange(below = 1.7f)
        assertTrue(SolrSchemaVersion.of("1.6") in belowSeven)
        assertFalse(SolrSchemaVersion.of("1.7") in belowSeven)
    }
}
