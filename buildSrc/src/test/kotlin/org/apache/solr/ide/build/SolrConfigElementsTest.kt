package org.apache.solr.ide.build

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Reading `solrconfig.xml`'s element vocabulary out of the code and the resource that describe it.
 *
 * The rules are pure and tested here without a jar, the way the plugin-root pairing already is. What
 * a jar adds is only which constants arrive; whether `childRequired` means required, and whether a
 * warning message is about the element beside it, are decisions this file owns.
 */
class SolrConfigElementsTest {

    // --- arity ------------------------------------------------------------------------------------

    /**
     * The reading method is what says how many of an element Solr accepts, and it is the only thing
     * that says so — the name alone cannot distinguish one `<dataDir>` from many `<lib>`.
     */
    @Test
    fun `the reading method decides the arity`() {
        assertEquals(SolrConfigElements.REQUIRED, SolrConfigElements.arityOf("childRequired"))
        assertEquals(SolrConfigElements.REPEATED, SolrConfigElements.arityOf("getAll"))
        assertEquals(SolrConfigElements.SINGLE, SolrConfigElements.arityOf("get"))
        assertEquals(SolrConfigElements.SINGLE, SolrConfigElements.arityOf("child"))
    }

    /** A method this does not know is not an element read, and guessing one would invent vocabulary. */
    @Test
    fun `an unknown method reads no element`() {
        assertNull(SolrConfigElements.arityOf("toString"))
        assertNull(SolrConfigElements.arityOf("getInt"))
    }

    // --- discontinued -----------------------------------------------------------------------------

    /**
     * Solr names the element in the message it warns with, which is what makes this readable at all.
     *
     * Proximity would be the obvious rule and the wrong one: the message for `<nrtMode>` is loaded
     * two instructions *before* the name itself, and the one for `indexDefaults` names two elements
     * at once. Matching on the name being mentioned is order-independent and says what it means.
     */
    @Test
    fun `an element named in a discontinuation message is discontinued`() {
        val messages = listOf(
            "The <nrtMode> config has been discontinued and NRT mode is always used by Solr.",
            "<indexDefaults> and <mainIndex> configuration sections are discontinued. Use <indexConfig> instead.",
            "Solr no longer supports forceful unlocking via the 'unlockOnStartup' option.",
        )
        assertTrue(SolrConfigElements.discontinuedBy("nrtMode", messages)!!.contains("discontinued"))
        assertTrue(SolrConfigElements.discontinuedBy("mainIndex", messages)!!.contains("discontinued"))
        assertTrue(SolrConfigElements.discontinuedBy("indexDefaults", messages)!!.contains("discontinued"))
        assertTrue(SolrConfigElements.discontinuedBy("unlockOnStartup", messages)!!.contains("no longer supports"))
    }

    /**
     * `indexConfig` is named by the very message that retires `indexDefaults` — as the replacement.
     *
     * The one case where mentioning the name is not enough, and the reason the match is on the name
     * in Solr's own bracket syntax rather than anywhere in the sentence. Getting this wrong would
     * retire the element the message is telling readers to use.
     */
    @Test
    fun `an element named only as the replacement is not discontinued`() {
        val messages = listOf(
            "<indexDefaults> and <mainIndex> configuration sections are discontinued. Use <indexConfig> instead.",
        )
        assertNull(SolrConfigElements.discontinuedBy("indexConfig", messages))
    }

    /**
     * The same exemption, for a replacement Solr names in quotes rather than in brackets.
     *
     * A mention is recognised in three spellings, because Solr writes option names in single quotes —
     * `'unlockOnStartup'` in the message above is Solr's own. A replacement check reading only the
     * bracket form would therefore see `lockType` mentioned, fail to see it recommended, and retire
     * the element the sentence exists to point at.
     */
    @Test
    fun `an element named only as the replacement in quotes is not discontinued`() {
        val messages = listOf(
            "Solr no longer supports forceful unlocking via the 'unlockOnStartup' option. " +
                "Use 'lockType' instead.",
        )
        assertNull(SolrConfigElements.discontinuedBy("lockType", messages))
        assertNotNull(SolrConfigElements.discontinuedBy("unlockOnStartup", messages))
    }

    @Test
    fun `an ordinary message retires nothing`() {
        val messages = listOf("Loaded SolrConfig: <dataDir> is set", "Using <query> defaults")
        assertNull(SolrConfigElements.discontinuedBy("dataDir", messages))
        assertNull(SolrConfigElements.discontinuedBy("query", messages))
    }

    // --- paths ------------------------------------------------------------------------------------

    /**
     * Solr writes three of its plugin tags as paths, and the nesting is the fact this file exists to
     * carry — the class catalog deliberately drops it, because a table of classes has nowhere to put
     * it.
     */
    @Test
    fun `a path names both the element and its parent`() {
        assertEquals("deletionPolicy" to "indexConfig", SolrConfigElements.split("indexConfig/deletionPolicy"))
        assertEquals("updateLog" to "updateHandler", SolrConfigElements.split("updateHandler/updateLog"))
        assertEquals("cache" to "query", SolrConfigElements.split("query/cache"))
    }

    @Test
    fun `a bare name has no parent`() {
        assertEquals("dataDir" to "", SolrConfigElements.split("dataDir"))
    }

    /**
     * `//listener` is Solr's syntax for an element accepted at any depth, and it must not read as a
     * child of an element named by the empty string.
     */
    @Test
    fun `an any-depth path has no parent either`() {
        assertEquals("listener" to "", SolrConfigElements.split("//listener"))
    }

    // --- merging the three sources ----------------------------------------------------------------

    /**
     * A reading that could not see the parent defers to a source that could.
     *
     * `SolrConfig` reads a deletion policy as `get("indexConfig").getAll("deletionPolicy")`, so the
     * literal names the child and the *receiver* carries the parent — which a pass reading literals
     * cannot see. Left alone, that entry says `<deletionPolicy>` is a top-level element, and a
     * consumer would offer it directly inside `<config>` where Solr ignores it.
     *
     * The arity it observed is still worth keeping, and is: only the placement was unknown.
     */
    @Test
    fun `a parentless reading defers to a source that knows the parent`() {
        val merged = SolrConfigElements.merge(
            listOf(
                SolrConfigElement("deletionPolicy", "", SolrConfigElements.REPEATED, SolrConfigElements.FROM_CONFIG),
                SolrConfigElement("deletionPolicy", "indexConfig", SolrConfigElements.SINGLE, SolrConfigElements.FROM_PLUGINS),
            ),
        )
        val single = merged.single { it.name == "deletionPolicy" }
        assertEquals("indexConfig", single.parent)
        assertEquals(SolrConfigElements.REPEATED, single.arity)
    }

    /**
     * An element every source agrees is top-level keeps its place.
     *
     * The rule must not read "parentless means unknown" — `<dataDir>` really does sit directly under
     * `<config>`, and nothing else ever names a parent for it.
     */
    @Test
    fun `an element no source gives a parent stays top-level`() {
        val merged = SolrConfigElements.merge(
            listOf(SolrConfigElement("dataDir", "", SolrConfigElements.SINGLE, SolrConfigElements.FROM_CONFIG)),
        )
        assertEquals("", merged.single().parent)
    }

    /** Two genuinely different placements of one name are two elements, not a conflict. */
    @Test
    fun `the same name under two parents stays two entries`() {
        val merged = SolrConfigElements.merge(
            listOf(
                SolrConfigElement("maxDocs", "updateHandler/autoCommit", SolrConfigElements.ATTRIBUTE, SolrConfigElements.FROM_EDITABLE),
                SolrConfigElement("maxDocs", "updateHandler/autoSoftCommit", SolrConfigElements.ATTRIBUTE, SolrConfigElements.FROM_EDITABLE),
            ),
        )
        assertEquals(2, merged.size)
    }

    // --- the editable-attributes resource ---------------------------------------------------------

    private val editable = """
        {
        //-------legend----------
        // 0  = string attribute
        // 21 = int node
        //------------------------
          "updateHandler":{
            "autoCommit":{
              "maxDocs":20,
              "openSearcher":11}},
          "query":{
            "filterCache":{
              "class":0,
              "size":20},
            "maxBooleanClauses":1},
        }
    """.trimIndent()

    /**
     * The resource is not JSON, quite: it carries `//` comments and a trailing comma, so a strict
     * parser refuses it. Both are stripped rather than tolerated, which keeps what remains readable
     * by an ordinary reading of the shape.
     */
    @Test
    fun `comments and a trailing comma do not stop the read`() {
        val read = SolrConfigElements.readEditable(editable)
        assertTrue("expected entries, got $read", read.isNotEmpty())
    }

    /** A nested object is an element, and its path is its parent. */
    @Test
    fun `a nested object is an element under its parent`() {
        val read = SolrConfigElements.readEditable(editable)
        val autoCommit = read.single { it.name == "autoCommit" }
        assertEquals("updateHandler", autoCommit.parent)
        assertEquals(SolrConfigElements.SINGLE, autoCommit.arity)
    }

    /**
     * The legend's codes are the whole type system: even is an attribute, odd is a child element, and
     * the tens digit is what it holds.
     */
    @Test
    fun `an even code is an attribute and an odd code is a child element`() {
        val read = SolrConfigElements.readEditable(editable)
        assertEquals(SolrConfigElements.ATTRIBUTE, read.single { it.name == "maxDocs" }.arity)
        assertEquals("int", read.single { it.name == "maxDocs" }.valueType)

        val openSearcher = read.single { it.name == "openSearcher" }
        assertEquals(SolrConfigElements.SINGLE, openSearcher.arity)
        assertEquals("boolean", openSearcher.valueType)

        assertEquals("string", read.single { it.name == "class" }.valueType)
        assertEquals(SolrConfigElements.SINGLE, read.single { it.name == "maxBooleanClauses" }.arity)
    }

    /** Depth is kept, because `size` under `filterCache` is not `size` anywhere else. */
    @Test
    fun `a leaf two levels down keeps its full parent path`() {
        val read = SolrConfigElements.readEditable(editable)
        assertEquals("query/filterCache", read.single { it.name == "size" }.parent)
    }
}
