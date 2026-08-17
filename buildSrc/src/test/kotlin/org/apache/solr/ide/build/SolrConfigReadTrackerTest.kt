package org.apache.solr.ide.build

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The rules that recover an element's parent from the shape of the code reading it.
 *
 * **These are the rules that were wrong, and the reason this class exists apart from the visitor.**
 * The pass this replaced read a name from a literal beside a known call and could see no parent
 * arriving any other way, so `<nrtMode>` was recorded with none and the catalog placed it under
 * `<config>` — a position Solr never reads. Inside an ASM visitor that logic could only be exercised
 * by generating a catalog from a real jar and reading the output, which shows what the rules did to
 * one input rather than what the rules are. Here each sequence below is the instruction shape it
 * names, written out.
 */
class SolrConfigReadTrackerTest {

    private val node = SolrConfigElements.NODE_CLASS
    private val config = SolrConfigElements.DECLARING_CLASS

    /** `get(String)` hands back a node, which is what lets a chain continue. */
    private val getsNode = "(Ljava/lang/String;)${SolrConfigElements.NODE_DESCRIPTOR}"

    /** `getAll(String)` hands back a list. Solr really does declare it this way. */
    private val getsList = "(Ljava/lang/String;)Ljava/util/List;"

    private fun tracker() = SolrConfigReadTracker()

    @Test
    fun `a read off the config itself is top-level`() {
        val t = tracker()
        t.stringLoaded("dataDir")
        assertEquals(SolrConfigRead("dataDir", SolrConfigElements.SINGLE), t.called(config, "get", getsNode))
    }

    @Test
    fun `a read off a node carries the path of that node`() {
        val t = tracker()
        t.stringLoaded("indexConfig")
        t.called(config, "get", getsNode)
        t.stringLoaded("deletionPolicy")
        assertEquals(
            SolrConfigRead("indexConfig/deletionPolicy", SolrConfigElements.REPEATED),
            t.called(node, "getAll", getsList),
        )
    }

    /**
     * The defect this whole class exists for, written as the instructions that produce it.
     *
     * `SolrConfig` hoists the index prefix into a local and loads it back at the call, so the store
     * is the instruction carrying the parent *and* the one that used to discard it.
     */
    @Test
    fun `a parent reaching the call through a local is still a parent`() {
        val t = tracker()
        t.stringLoaded("indexConfig")
        t.stored(6)
        t.opaqueInstruction() // aload_0, or anything else between the store and the use
        t.loaded(6)
        t.called(config, "get", getsNode)
        t.stringLoaded("nrtMode")
        assertEquals(
            SolrConfigRead("indexConfig/nrtMode", SolrConfigElements.SINGLE),
            t.called(node, "get", getsNode),
        )
    }

    /**
     * `root` is the document, so what Solr reads off it is top-level rather than unplaced.
     *
     * Without this rule `lib` and `luceneMatchVersion` — both read as `root.getAll(…)` — would be
     * nested reads with no known receiver, and would be reported [SolrConfigElements.UNPLACED].
     */
    @Test
    fun `a read off the document root is top-level`() {
        val t = tracker()
        t.rootLoaded()
        t.stringLoaded("lib")
        assertEquals(SolrConfigRead("lib", SolrConfigElements.REPEATED), t.called(node, "getAll", getsList))
    }

    /** A nested read whose receiver was never established says so, rather than claiming the root. */
    @Test
    fun `a nested read with no known receiver is unplaced`() {
        val t = tracker()
        t.stringLoaded("somethingNested")
        assertEquals(
            SolrConfigRead("${SolrConfigElements.UNPLACED}/somethingNested", SolrConfigElements.SINGLE),
            t.called(node, "get", getsNode),
        )
    }

    /**
     * A list is not a node, so a `getAll` leaves nothing for a following read to attach to.
     *
     * Treating every read as leaving a receiver would have the pass believe a node is in hand when a
     * collection is, and attribute the next read to it — a wrong parent written silently into the
     * catalog rather than an error.
     */
    @Test
    fun `a read returning a list leaves no receiver behind`() {
        val t = tracker()
        t.rootLoaded()
        t.stringLoaded("lib")
        t.called(node, "getAll", getsList)
        t.stringLoaded("child")
        assertEquals(
            SolrConfigRead("${SolrConfigElements.UNPLACED}/child", SolrConfigElements.SINGLE),
            t.called(node, "get", getsNode),
        )
    }

    /**
     * A chain does not grow out of a position that was never established.
     *
     * Extending onto an unplaced read yields `?/a`, and a path one segment deep looks like a real
     * position to everything downstream — so the element would be recorded under a parent no source
     * ever named. The marker would then protect a single read and fail on exactly the chains that
     * make a position hard to establish in the first place.
     */
    @Test
    fun `a chain does not extend out of an unplaced read`() {
        val t = tracker()
        t.stringLoaded("a")
        t.called(node, "get", getsNode)
        t.stringLoaded("b")
        assertEquals(
            SolrConfigRead("${SolrConfigElements.UNPLACED}/b", SolrConfigElements.SINGLE),
            t.called(node, "get", getsNode),
        )
    }

    /**
     * A slot reused for something this pass cannot name forgets its string.
     *
     * Locals are reused aggressively, so a remembered name outliving its scope would be handed back
     * at an unrelated call and recorded as an element.
     */
    @Test
    fun `a slot reused for something else gives back no name`() {
        val t = tracker()
        t.stringLoaded("indexConfig")
        t.stored(6)
        t.stored(6) // nothing pending: the slot now holds something this pass cannot name
        t.loaded(6)
        assertNull(t.called(config, "get", getsNode))
    }

    /** An unrelated instruction between a name and its reader means the name was pushed for something else. */
    @Test
    fun `an instruction between a name and its reader clears the name`() {
        val t = tracker()
        t.stringLoaded("dataDir")
        t.opaqueInstruction()
        assertNull(t.called(config, "get", getsNode))
    }

    /**
     * Concatenation consumes the name and leaves the node, which is the asymmetry that matters.
     *
     * A `makeConcatWithConstants` has spent the literal building a message; the node in hand is
     * untouched. Clearing both would lose the parent of the read that follows.
     */
    @Test
    fun `string concatenation consumes the name and keeps the node`() {
        val t = tracker()
        t.stringLoaded("query")
        t.called(config, "get", getsNode)
        t.stringLoaded("some message fragment")
        t.stringConcatenated()
        t.stringLoaded("filterCache")
        assertEquals(
            SolrConfigRead("query/filterCache", SolrConfigElements.SINGLE),
            t.called(node, "get", getsNode),
        )
    }

    /** A call that reads no element also ends the chain, since it consumed whatever was on the stack. */
    @Test
    fun `a call that is not a read ends the chain`() {
        val t = tracker()
        t.stringLoaded("query")
        t.called(config, "get", getsNode)
        t.called(node, "isNull", "()Z")
        t.stringLoaded("filterCache")
        assertEquals(
            SolrConfigRead("${SolrConfigElements.UNPLACED}/filterCache", SolrConfigElements.SINGLE),
            t.called(node, "get", getsNode),
        )
    }
}
