package org.apache.solr.ide.model.vocabulary

import org.apache.solr.ide.model.SolrVersionSelection

/**
 * How many of an element Solr accepts where it sits.
 *
 * Recoverable only from the code that reads the element — `getAll` against `childRequired` — which is
 * why it is a generated fact rather than a hand-written one. No shipped configset could supply it,
 * since an example shows what one file happens to write rather than what Solr accepts.
 */
enum class SolrElementArity {

    /** Solr accepts one, and the file need not carry it. */
    SINGLE,

    /** Solr accepts several. */
    REPEATED,

    /** Solr accepts one and fails without it. */
    REQUIRED,

    /** Not an element at all — an attribute of the element it sits under. */
    ATTRIBUTE,
}

/**
 * One element or attribute a `solrconfig.xml` may contain.
 *
 * @property name the element or attribute as a reader writes it
 * @property parent the path it sits under; empty for a top-level element, and also for one Solr
 *   accepts at any depth, since a consumer does the same thing with both
 * @property arity how many Solr accepts, or [SolrElementArity.ATTRIBUTE] where this is not an element
 * @property discontinued Solr's own words retiring it, empty for an element Solr still accepts
 */
data class SolrElementEntry(
    val name: String,
    val parent: String,
    val arity: SolrElementArity,
    val discontinued: String = "",
) {

    /** Whether Solr still accepts this, which is what decides if it may be offered. */
    val isCurrent: Boolean get() = discontinued.isEmpty()
}

/**
 * The elements and attributes a `solrconfig.xml` may contain, per Solr line.
 *
 * **A separate resource and a separate reader from the class catalog, because these are not classes.**
 * The same reasoning [SolrParameterCatalog] records: folding them in would give that catalog's `kind`
 * column entries which are not kinds of class, and then answering for them in every exhaustive `when`
 * over it. Two small readers cost less than one type meaning two things.
 *
 * **Carries what Solr retired rather than dropping it.** `nrtMode` and its three siblings are elements
 * Solr reads only in order to warn about them, and the entry keeps that notice. Nothing offers them —
 * [offerableChildrenOf] is what completion asks — but a name Solr explicitly discontinued is a
 * different thing from a name it has never heard of, and only a catalog that carries both can say so.
 */
object SolrElementCatalog {

    /**
     * The Solr major lines with a shipped resource, newest first.
     *
     * Kept in step with the `supportedSolrLines` the build declares, exactly as the class catalog's
     * list is. A line named here with no generated resource behind it reads as empty rather than
     * failing, because a missing resource must not take the editor down with it.
     */
    private val SUPPORTED_LINES: List<Int> = listOf(10, 9)

    private val byLine = HashMap<Int, List<SolrElementEntry>>()

    /**
     * The entry for [name] under [parent], or null when no source names it there.
     *
     * @param name the element or attribute as written
     * @param parent the path it sits under, empty for a top-level element
     * @param version the Solr line this configset targets
     * @return the entry, or null
     */
    fun element(name: String, parent: String, version: SolrVersionSelection): SolrElementEntry? =
        load(version).firstOrNull { it.name == name && it.parent == parent }

    /**
     * Everything that may appear directly under [parent], discontinued entries included.
     *
     * @param parent the path being asked about, empty for the file's own root
     * @param version the Solr line this configset targets
     * @return the entries, in the order the resource lists them
     */
    fun childrenOf(parent: String, version: SolrVersionSelection): List<SolrElementEntry> =
        load(version).filter { it.parent == parent }

    /**
     * What may be *offered* under [parent], which is [childrenOf] minus what Solr has retired.
     *
     * The distinction completion wants. Offering `<nrtMode>` would be the plugin recommending a
     * configuration Solr warns about on startup, and dropping it from the catalog entirely would
     * leave nothing able to explain why it is absent.
     *
     * @param parent the path being completed inside
     * @param version the Solr line this configset targets
     * @return the entries Solr still accepts
     */
    fun offerableChildrenOf(parent: String, version: SolrVersionSelection): List<SolrElementEntry> =
        childrenOf(parent, version).filter { it.isCurrent }

    private fun load(version: SolrVersionSelection): List<SolrElementEntry> = synchronized(byLine) {
        byLine.getOrPut(lineFor(version)) { read(lineFor(version)) }
    }

    /**
     * The line whose resource answers for [version], falling back to the newest.
     *
     * The rule the other two catalogs follow: someone reading a configset for a Solr this plugin does
     * not support is better served by the current vocabulary than by silence.
     */
    private fun lineFor(version: SolrVersionSelection): Int {
        val declared = version.guidePathSegment.substringBefore('_').toIntOrNull()
        return declared?.takeIf { it in SUPPORTED_LINES } ?: SUPPORTED_LINES.first()
    }

    private fun read(line: Int): List<SolrElementEntry> {
        val stream = SolrElementCatalog::class.java.getResourceAsStream("/solr-catalog/elements-$line.tsv")
            ?: return emptyList()
        return stream.bufferedReader().useLines { parse(it) }
    }

    /**
     * The entries in [rows], dropping anything that is not one.
     *
     * Separate from reading the resource so it can be tested against input the generator would never
     * produce, for the reason the other catalogs' parsers give: this is the one place a malformed or
     * half-written resource reaches the editor, and the guarantee worth having is that a bad row costs
     * a row rather than the file.
     *
     * **An arity this build does not recognise falls back rather than dropping the element.** A row
     * from a newer generator still says an element exists and where it sits, which is most of what a
     * consumer needs; refusing it over one unrecognised column would lose the element entirely.
     *
     * @param rows the resource's lines
     * @return the entries, in the order they appear
     */
    internal fun parse(rows: Sequence<String>): List<SolrElementEntry> {
        val entries = mutableListOf<SolrElementEntry>()
        for (row in rows) {
            if (row.startsWith("#") || row.isBlank()) continue
            val columns = row.split('\t')
            if (columns.size < 4) continue
            entries += SolrElementEntry(
                name = columns[0].takeIf { it.isNotBlank() } ?: continue,
                parent = columns[1],
                arity = arityOf(columns[2]),
                discontinued = columns.getOrElse(4) { "" },
            )
        }
        return entries
    }

    private fun arityOf(written: String): SolrElementArity = when (written) {
        "required" -> SolrElementArity.REQUIRED
        "repeated" -> SolrElementArity.REPEATED
        "attribute" -> SolrElementArity.ATTRIBUTE
        else -> SolrElementArity.SINGLE
    }
}
