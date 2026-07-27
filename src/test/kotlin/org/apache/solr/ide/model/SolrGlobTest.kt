package org.apache.solr.ide.model

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Solr's glob, which is deliberately impoverished: one `*` at one end, or the bare `*`.
 *
 * Pinned in one place because there used to be two implementations that disagreed about a pattern
 * containing no wildcard — one matched it exactly, the other matched nothing. The disagreement was
 * masked by a caller that checked equality first, which is how a duplicated rule survives long
 * enough to matter.
 */
class SolrGlobTest {

    @Test
    fun `a bare star matches anything`() {
        assertTrue(SolrGlob.matches("*", "anything"))
        assertTrue(SolrGlob.matches("*", ""))
    }

    @Test
    fun `a leading star matches by suffix`() {
        assertTrue(SolrGlob.matches("*_s", "title_s"))
        assertFalse(SolrGlob.matches("*_s", "title_str"))
    }

    @Test
    fun `a trailing star matches by prefix`() {
        assertTrue(SolrGlob.matches("attr_*", "attr_colour"))
        assertFalse(SolrGlob.matches("attr_*", "colour_attr"))
    }

    /** The case the two implementations disagreed on. */
    @Test
    fun `a pattern with no wildcard matches only itself`() {
        assertTrue(SolrGlob.matches("literal", "literal"))
        assertFalse(SolrGlob.matches("literal", "literal_s"))
        assertFalse(SolrGlob.matches("literal", "other"))
    }

    /** Dynamic fields delegate here, so the two can no longer drift. */
    @Test
    fun `dynamic field matching uses the same rule`() {
        val dynamic = SolrDynamicField("*_str", SolrField("*_str", "strings"))
        assertTrue(dynamic.matches("title_str"))
        assertFalse(dynamic.matches("title_s"))
    }
}
