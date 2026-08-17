package org.apache.solr.ide.build

/**
 * One element read recovered from `SolrConfig`, as a path and how many Solr accepts.
 *
 * @property path the element and the parent it was read under, as [SolrConfigElements.split] parses
 * @property arity what the reading method says about how many Solr accepts
 */
internal data class SolrConfigRead(val path: String, val arity: String)

/**
 * The state a pass over `SolrConfig`'s bytecode has to carry to name an element's parent.
 *
 * **This exists as its own class because the rules below were wrong once and nothing could have told
 * us.** The pass they replace read a name from a literal beside a known call and could see no parent
 * that arrived any other way — so `<nrtMode>`, which Solr reads as
 * `get(indexConfigPrefix).get("nrtMode")`, was recorded with no parent at all and the catalog placed
 * it at the top level. Living inside an ASM visitor, that logic could only be exercised by generating
 * a catalog from a real jar and reading the output, which proves what the rules did to one input
 * rather than what the rules are. Kept here it is a state machine over named events, and a synthetic
 * instruction sequence is a test.
 *
 * The ASM visitor is the adapter: it translates instructions into the calls below and owns nothing
 * else. [SolrConfigElements] holds the rules that need no state at all.
 *
 * **Everything not modelled clears.** A name reaches its reader immediately, through a local, or with
 * one `invokedynamic` in between; anything else between the two means the string was pushed for
 * something other than this call. Clearing on the unknown is what keeps a discontinuation notice from
 * being recorded as an element name, which an earlier pass did.
 */
internal class SolrConfigReadTracker {

    /** The last string loaded, and so a candidate element name for the next call. */
    private var pending: String? = null

    /**
     * Strings that reached a local, so a name arriving through one is still a name.
     *
     * `SolrConfig` hoists the prefix it reads the index subtree under and loads it back at each use.
     * Without this the store cleared the pending literal, the reload put nothing in its place, and
     * the elements read that way were recorded with no parent.
     */
    private val locals = mutableMapOf<Int, String>()

    /**
     * The path of the node a chained read would be made against, or null where none is in hand.
     *
     * Null and empty are different: empty is the document root, which is a real position, and null is
     * *no node*, which becomes [SolrConfigElements.UNPLACED] if a nested read happens anyway.
     */
    private var receiver: String? = null

    /** A string literal was pushed. It is the candidate name until something consumes or clears it. */
    fun stringLoaded(value: String) {
        pending = value
    }

    /**
     * A non-string constant was pushed.
     *
     * Cleared rather than ignored: an unrelated constant between a name and its reader means the name
     * was pushed for something else, and letting it survive is how a pass records the wrong word.
     */
    fun nonStringLoaded() {
        pending = null
        receiver = null
    }

    /**
     * A value was stored to [slot].
     *
     * A string is remembered so [loaded] can give it back. Anything else forgets the slot rather than
     * leaving the previous string in it — locals are reused aggressively, and a stale name recovered
     * from a reused slot would be recorded as an element.
     */
    fun stored(slot: Int) {
        val literal = pending
        pending = null
        if (literal != null) locals[slot] = literal else locals.remove(slot)
        receiver = null
    }

    /** [slot] was loaded. A remembered string becomes the pending name again; anything else clears. */
    fun loaded(slot: Int) {
        pending = locals[slot]
        if (pending == null) receiver = null
    }

    /**
     * The document root was loaded, which is the one field read that supplies a receiver.
     *
     * Its path is empty because it *is* the document, so what Solr reads off it is top-level. Without
     * this the elements read as `root.getAll("lib")` would be nested reads with no known parent, and
     * `lib` and `luceneMatchVersion` would be reported unplaced rather than at the root.
     */
    fun rootLoaded() {
        pending = null
        receiver = ""
    }

    /** Any other instruction this pass does not model. Both the name and the node stop being current. */
    fun opaqueInstruction() {
        pending = null
        receiver = null
    }

    /**
     * A `makeConcatWithConstants` consumed the pending literal to build a longer string.
     *
     * The receiver survives because concatenation does not touch the node in hand; only the name is
     * gone, having become part of a message rather than staying an element name.
     */
    fun stringConcatenated() {
        pending = null
    }

    /**
     * A call was made. Returns the element read it performs, or null where it reads no element.
     *
     * **The owner decides whether the read is nested**, and it is the only thing that can. A read on
     * the config itself starts at the document, so its literal is top-level. One on a node is a step
     * further down, so its literal belongs to whatever that node is — known where the chain was
     * followed, [SolrConfigElements.UNPLACED] where it was not. Reporting the unfollowed case as the
     * root is what put `<nrtMode>` under `<config>`.
     *
     * **The return type decides whether a node is left in hand.** `get` yields a node and `getAll`
     * yields a list, so treating every read as leaving a receiver would have the pass believe a node
     * is on the stack when a collection is — and attribute a following read to it. Nothing in
     * `SolrConfig` currently reaches that state, because the instructions after a `getAll` clear it
     * anyway; the point is that if one ever does, it is a wrong parent written silently into the
     * catalog rather than an error.
     *
     * @param owner the class the method is called on
     * @param method the method name
     * @param descriptor the method descriptor, whose return type says what is left on the stack
     * @return the read, or null where this call reads no element
     */
    fun called(owner: String?, method: String?, descriptor: String?): SolrConfigRead? {
        val literal = pending
        pending = null
        val arity = method?.let { SolrConfigElements.arityOf(it) }
        if (arity == null || literal == null) {
            receiver = null
            return null
        }
        val parent = when {
            owner != SolrConfigElements.NODE_CLASS -> ""
            else -> receiver ?: SolrConfigElements.UNPLACED
        }
        val path = if (parent.isEmpty()) literal else "$parent/$literal"
        receiver = if (returnsNode(descriptor)) path else null
        return SolrConfigRead(path, arity)
    }

    private fun returnsNode(descriptor: String?): Boolean =
        descriptor?.substringAfterLast(')') == SolrConfigElements.NODE_DESCRIPTOR
}
