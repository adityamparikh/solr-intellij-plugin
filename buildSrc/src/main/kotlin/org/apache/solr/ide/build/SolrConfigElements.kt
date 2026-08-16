package org.apache.solr.ide.build

/**
 * One element or attribute a `solrconfig.xml` may contain.
 *
 * @property name the element or attribute as a reader writes it
 * @property parent the path it sits under, empty for a top-level element or one Solr accepts at any
 *   depth, and [SolrConfigElements.UNPLACED] for one Solr reads off a node whose own path the scan
 *   could not follow
 * @property arity how many Solr accepts, or [SolrConfigElements.ATTRIBUTE] where this is not an
 *   element at all
 * @property source which of the three declarations it came from
 * @property discontinued Solr's own words retiring it, empty for an element Solr still accepts
 */
internal data class SolrConfigElement(
    val name: String,
    val parent: String,
    val arity: String,
    val source: String,
    val discontinued: String = "",
)

/**
 * The `solrconfig.xml` vocabulary, read from the code and the resource that describe it.
 *
 * **Three declarations, none of which is a schema.** Solr publishes no XSD for this file, so the
 * vocabulary has to be recovered from what reads it: `SolrConfig.plugins` pairs 23 element tags with
 * the class each requires, `EditableSolrConfigAttributes.json` types the three subtrees the config
 * API can rewrite, and `SolrConfig` itself reads the rest through named methods with the element name
 * as a literal argument. The third is what this object adds, and it is the same technique the factory
 * attributes already use — a literal beside a known call.
 *
 * **What the call adds beyond the name is arity**, which nothing else records: `getAll("lib")` accepts
 * many, `childRequired("luceneMatchVersion", …)` demands one. That distinction cannot be recovered
 * from a name or from any shipped configset, since an example shows what one file happens to write
 * rather than what Solr accepts.
 *
 * The rules here are pure so they can be tested without a jar, exactly as [SolrConfigPlugins.pair] is.
 */
internal object SolrConfigElements {

    /** The class that reads the configuration tree, and therefore names its elements. */
    const val DECLARING_CLASS = "org/apache/solr/core/SolrConfig"

    /**
     * The node a nested read is made against, and therefore the signal that a read *is* nested.
     *
     * A read called on [DECLARING_CLASS] starts at the document, so its literal is a top-level
     * element. One called on a node is a step further down, so its literal is a child of whatever
     * that node is — known where the chain was followed, [UNPLACED] where it was not. The owner is
     * what tells the two apart, and it is why an unfollowed chain can be reported as unknown rather
     * than quietly reported as the root.
     */
    const val NODE_CLASS = "org/apache/solr/common/ConfigNode"

    /**
     * The field holding the document root, whose path is empty because it *is* the document.
     *
     * `SolrConfig` reads its top-level elements as `root.getAll("lib")` and
     * `root.childRequired("luceneMatchVersion", …)` rather than off itself, so without this the
     * owner test would call them nested, find no path for the receiver, and report [UNPLACED] for
     * two elements that really are children of `<config>`. Naming the field is reading a fact out of
     * Solr, and the type is checked with it so an unrelated field called `root` cannot stand in.
     */
    const val ROOT_FIELD = "root"

    /** The descriptor a field holding a node carries, for telling [ROOT_FIELD] from its namesakes. */
    const val NODE_DESCRIPTOR = "L$NODE_CLASS;"

    /** Solr accepts one, and the file need not contain it. */
    const val SINGLE = "single"

    /** Solr accepts many. */
    const val REPEATED = "repeated"

    /** Solr accepts one and fails without it. */
    const val REQUIRED = "required"

    /** Not an element at all — an attribute of the element it sits under. */
    const val ATTRIBUTE = "attribute"

    /** An element rather than an attribute, which is the whole of what the resource records. */
    const val ELEMENT = "element"

    /**
     * The parent of an element Solr reads off a node the scan lost track of.
     *
     * **An absent parent and an unobserved one are different claims, and conflating them is how this
     * catalog shipped a false positive.** `SolrConfig` reads `<nrtMode>` as
     * `get(indexConfigPrefix).get("nrtMode")`, where the prefix reaches the call through a local
     * variable; the pass that could not follow it recorded no parent, and an empty parent already
     * meant *top level*. So the catalog announced `<nrtMode>` as a child of `<config>` — a position
     * Solr never reads — and the discontinuation rule fired there while staying silent on
     * `<indexConfig><nrtMode>`, the position that stops a core starting.
     *
     * Chained reads are now followed, so that case is observed rather than guessed. This value is
     * what remains for the ones that are not: a read whose receiver is a node, whose own path the
     * scan could not name. A consumer must decline to place such an element rather than offering it
     * anywhere, which is the same refusal `SolrMatchAnalysis` makes of a factory it does not know.
     *
     * Not a legal XML name, so it can never collide with a path Solr actually declares.
     */
    const val UNPLACED = "?"

    /**
     * Whether [parent] names a position, as against saying there is none or that none was seen.
     *
     * @param parent the parent path from a reading
     * @return true where the path is a real one
     */
    fun isPlaced(parent: String): Boolean = parent.isNotEmpty() && parent != UNPLACED

    /**
     * What a merged entry is written as: an element, or an attribute of the one above it.
     *
     * **The arities decide this and are then discarded, which is the point.** `single`, `repeated` and
     * `required` are real distinctions Solr's reading code makes, and they are what
     * [merge] needs in order to resolve a name two sources disagree about — a `getAll` reading beats
     * an attribute, an attribute beats a `get`. What no consumer has ever asked is *how many* of
     * something Solr accepts, so shipping that would be a claim the catalog is never held to. Worth
     * recording again the day a feature wants it.
     *
     * @param arity the merged arity
     * @return the word the resource carries
     */
    fun kindOf(arity: String): String = if (arity == ATTRIBUTE) ATTRIBUTE else ELEMENT

    /** Read from `SolrConfig.plugins`, which also names the class the element must implement. */
    const val FROM_PLUGINS = "plugin"

    /** Read from a literal beside a call in `SolrConfig`. */
    const val FROM_CONFIG = "config"

    /** Read from `EditableSolrConfigAttributes.json`. */
    const val FROM_EDITABLE = "editable"

    /**
     * The methods that read a child by name, and what each says about how many Solr accepts.
     *
     * A closed set rather than a prefix rule, because `SolrConfig` also calls `getInt`, `getBool` and
     * `getVal` — readers of a *value* whose first argument is a path expression rather than an element
     * name. Treating every `get*` as an element read would put `solr.config.lib.enabled` in the
     * vocabulary.
     */
    private val READERS = mapOf(
        "get" to SINGLE,
        "child" to SINGLE,
        "childRequired" to REQUIRED,
        "getAll" to REPEATED,
    )

    /**
     * Phrases Solr retires an element with.
     *
     * Small and literal on purpose. A rule inferring disapproval from tone would retire elements over
     * an ordinary warning about how one is configured, and the cost of a false positive here is an
     * element the plugin refuses to complete.
     */
    private val RETIRING = listOf("discontinued", "no longer supports", "no longer supported")

    /**
     * How many of an element the method reading it accepts, or null when this is not an element read.
     *
     * @param method the method name from the call site
     * @return the arity, or null
     */
    fun arityOf(method: String): String? = READERS[method]

    /**
     * Solr's own words retiring [name], or null where Solr still accepts it.
     *
     * **Matched on the name as Solr spells it, not on the name appearing anywhere.** The message that
     * retires `<indexDefaults>` names `<indexConfig>` too — as the replacement — so a looser match
     * would retire the element the message exists to recommend.
     *
     * @param name the element name
     * @param messages every string constant in the declaring class
     * @return the message, or null
     */
    fun discontinuedBy(name: String, messages: List<String>): String? {
        val mentions = mentionsOf(name)
        return messages.firstOrNull { message ->
            RETIRING.any { message.contains(it, ignoreCase = true) } &&
                mentions.any { message.contains(it) } &&
                !isReplacementIn(message, name)
        }
    }

    /**
     * The spellings a message may name an element by.
     *
     * **One list, because a mention and a replacement have to be recognised by the same spellings.**
     * Reading the bracket form here and all three at the match would let a message that recommends
     * `'lockType'` retire the very element it recommends — and Solr writes option names in single
     * quotes, so that is the ordinary spelling rather than an exotic one.
     */
    private fun mentionsOf(name: String) = listOf("<$name>", "'$name'", "\"$name\"")

    /**
     * Whether [message] names [name] only as the thing to use instead.
     *
     * Solr writes the replacement after "Use", which is enough to tell the two apart in the one
     * message that does both. A name appearing *before* that word is the retired one however it is
     * spelled, so the earliest mention in any spelling is what decides.
     */
    private fun isReplacementIn(message: String, name: String): Boolean {
        val use = message.indexOf("Use ", ignoreCase = true)
        if (use < 0) return false
        val earliest = mentionsOf(name)
            .map { message.indexOf(it) }
            .filter { it >= 0 }
            .minOrNull()
            ?: return false
        return earliest >= use
    }

    /**
     * The element and its parent, from the path Solr declares it under.
     *
     * `//listener` is Solr's syntax for an element accepted at any depth; it yields no parent rather
     * than a parent named by the empty string, which is the same answer a top-level element gives and
     * the same thing a consumer should do with it.
     *
     * @param tag the tag or path as Solr writes it
     * @return the element name, and the path it sits under
     */
    fun split(tag: String): Pair<String, String> {
        val cleaned = tag.removePrefix("//")
        val name = cleaned.substringAfterLast('/')
        val parent = if ('/' in cleaned) cleaned.substringBeforeLast('/') else ""
        return name to parent
    }

    /**
     * One row per element, folding together what each source that names it knows.
     *
     * **A reading that names no position is absorbed into one that does, and that is the only merge
     * that moves an element.** `SolrConfig` reads a nested element off its parent node —
     * `get("indexConfig").getAll("deletionPolicy")` — so the literal names the child while the
     * receiver carries the parent. Kept as its own row it would announce `<deletionPolicy>` as a
     * top-level element and a consumer would offer it inside `<config>`, where Solr ignores it. What
     * that reading *did* observe is the arity, and that survives the fold.
     *
     * Both kinds of positionless reading absorb: a genuinely parentless one, and an
     * [UNPLACED] one from a chain the scan could not follow. Neither counts as a position when
     * deciding what to absorb *into*, or an unplaced row would capture the parentless readings of a
     * name nothing ever placed.
     *
     * Parentless is therefore not read as "unknown": `<dataDir>` really is top-level, and stays so
     * because no source ever gives it a parent. That distinction used to be carried by the empty
     * string alone, and [UNPLACED] exists because it could not carry it — see that constant for the
     * defect this cost.
     *
     * Arity takes the most specific answer rather than the first, since `single` is also what a
     * source says when it has nothing to add, while `required` and `repeated` are things one saw.
     *
     * @param entries every element every source named, in any order
     * @return one entry per distinct placement, sorted by parent then name
     */
    fun merge(entries: List<SolrConfigElement>): List<SolrConfigElement> {
        val parented = entries.filter { isPlaced(it.parent) }.map { it.name }.toSet()
        val placed = entries.filterNot { !isPlaced(it.parent) && it.name in parented }
        val absorbed = entries.filter { !isPlaced(it.parent) && it.name in parented }.groupBy { it.name }

        return placed
            .groupBy { it.parent to it.name }
            .map { (key, found) ->
                val contributing = found + absorbed[key.second].orEmpty()
                SolrConfigElement(
                    name = key.second,
                    parent = key.first,
                    arity = strongestArity(contributing.map { it.arity }),
                    source = contributing.map { it.source }.distinct().sorted().joinToString(","),
                    discontinued = contributing.firstNotNullOfOrNull { it.discontinued.ifEmpty { null } }.orEmpty(),
                )
            }
            .sortedWith(compareBy({ it.parent }, { it.name }))
    }

    private fun strongestArity(arities: List<String>): String = when {
        REQUIRED in arities -> REQUIRED
        REPEATED in arities -> REPEATED
        ATTRIBUTE in arities -> ATTRIBUTE
        else -> SINGLE
    }

    /**
     * The elements and attributes `EditableSolrConfigAttributes.json` describes.
     *
     * **Not JSON, quite.** The shipped resource carries `//` comments and a trailing comma before its
     * closing brace, so a strict parser refuses it outright. Both are removed before reading rather
     * than tolerated during it, which keeps the reader below a description of the shape: a name maps
     * either to an object, which is an element with children, or to a code from the file's own legend.
     *
     * The legend is the whole type system and it is arithmetic: the tens digit says what the leaf
     * holds, and the units digit says whether it is written as an attribute or as a child element.
     *
     * @param json the resource's contents
     * @return one entry per element and attribute, parents before children
     */
    fun readEditable(json: String): List<SolrConfigElement> {
        val stripped = json.lines()
            .joinToString("\n") { it.substringBefore("//") }
            .replace(Regex(",\\s*([}\\]])"), "$1")
        val found = mutableListOf<SolrConfigElement>()
        readObject(Cursor(stripped, stripped.indexOf('{') + 1), "", found)
        return found
    }

    /** Where the read has reached, so the recursion can advance one shared position. */
    private class Cursor(val text: String, var at: Int)

    private fun readObject(cursor: Cursor, parent: String, found: MutableList<SolrConfigElement>) {
        while (cursor.at < cursor.text.length) {
            val quote = cursor.text.indexOf('"', cursor.at)
            val close = cursor.text.indexOf('}', cursor.at)
            if (quote < 0 || (close in 0..<quote)) {
                cursor.at = if (close < 0) cursor.text.length else close + 1
                return
            }
            val end = cursor.text.indexOf('"', quote + 1)
            if (end < 0) return
            val name = cursor.text.substring(quote + 1, end)
            val colon = cursor.text.indexOf(':', end)
            if (colon < 0) return
            val value = cursor.text.drop(colon + 1).trimStart().firstOrNull()
            if (value == '{') {
                found += SolrConfigElement(name, parent, SINGLE, FROM_EDITABLE)
                cursor.at = cursor.text.indexOf('{', colon) + 1
                readObject(cursor, if (parent.isEmpty()) name else "$parent/$name", found)
            } else {
                val digits = cursor.text.drop(colon + 1).trimStart().takeWhile { it.isDigit() }
                val code = digits.toIntOrNull() ?: 0
                // Only the units digit is read. The legend's tens digit says what the leaf holds —
                // string, boolean, int or float — and nothing consumes that, so recording it would
                // put a column in the resource that no reader ever asks for.
                found += SolrConfigElement(
                    name = name,
                    parent = parent,
                    arity = if (code % 10 == 0) ATTRIBUTE else SINGLE,
                    source = FROM_EDITABLE,
                )
                cursor.at = colon + 1 + digits.length + (cursor.text.drop(colon + 1).length - cursor.text.drop(colon + 1).trimStart().length)
            }
        }
    }
}
