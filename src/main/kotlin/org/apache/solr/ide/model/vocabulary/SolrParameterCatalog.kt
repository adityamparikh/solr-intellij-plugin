package org.apache.solr.ide.model.vocabulary

import org.apache.solr.ide.model.SolrVersionSelection

/**
 * What a `solrconfig.xml` parameter is called, where Solr declares it, and what it is for.
 *
 * @property name the parameter as a configset writes it — `qf`, `facet.range.other`
 * @property owner the fully qualified interface or class declaring it, which is what makes the entry
 *   attributable rather than merely asserted
 * @property summary the first sentence of the declaring constant's Javadoc, or null where Solr wrote
 *   none. Roughly two in five carry nothing, which is the ceiling of the source and not a gap to fill
 *   by hand — a parameter with no summary shows no summary
 */
data class SolrParameterEntry(
    val name: String,
    val owner: String,
    val summary: String? = null,
)

/**
 * The request parameters a `solrconfig.xml` handler may carry defaults for, and the names `defType`
 * accepts, per supported Solr line.
 *
 * **Separate from [SolrClassCatalog] because these are not classes.** That catalog builds a
 * [SolrClassEntry] per row and resolves each row's kind through [SolrClassKind], so folding parameters
 * into it would mean teaching an enum of *class kinds* two constants that are not kinds of class — and
 * then answering, in every exhaustive `when` over that enum, which Reference Guide page documents "the
 * parameter kind". Two resources and two small readers cost less than one type meaning two things.
 *
 * **Generated at build time from the interfaces Solr declares its parameter names in.** A name is a
 * `public static final String`, so it sits in a `ConstantValue` attribute the generator reads directly;
 * the summary is the Javadoc on that same constant, read from the line's `-sources` jar. Neither is
 * written down here, and neither is copied from the Reference Guide — the plugin's standing reason for
 * linking to the guide rather than reproducing it applies to a parameter exactly as it does to a class.
 *
 * **This is a completion and documentation source. It is not a membership test.** A `solrconfig.xml`
 * may name a custom `SearchComponent` that reads parameters of its author's choosing out of the same
 * `SolrParams`, and no generator will ever see them, so a parameter absent from here is not thereby
 * invalid and nothing may report it as such. The selection rules the generator applies are deliberately
 * biased that way: they drop a few real parameter names rather than admit request paths and response
 * keys into a list a reader is using to learn the vocabulary.
 */
object SolrParameterCatalog {

    /**
     * The Solr major lines with a shipped resource, newest first.
     *
     * The same lines [SolrClassCatalog.SUPPORTED_LINES] names, and read the same way: a line with no
     * generated resource behind it reads as empty rather than failing, because a missing resource must
     * not take the editor down with it.
     */
    val SUPPORTED_LINES: List<Int> = SolrClassCatalog.SUPPORTED_LINES

    private val byLine = HashMap<Int, ParsedResource>()

    /**
     * Every request parameter the [version] line declares.
     *
     * @param version the Solr line this configset targets
     * @return the parameters, sorted by name as the resource lists them
     */
    fun parametersFor(version: SolrVersionSelection): List<SolrParameterEntry> = load(version).parameters

    /**
     * The parameter [name], or null when no interface this line ships declares it.
     *
     * Null is the ordinary answer for a custom component's parameter and must never be read as "not a
     * parameter" — see this object's own note on membership.
     *
     * @param name the parameter as the configset writes it
     * @param version the Solr line this configset targets
     * @return the entry, or null
     */
    fun parameter(name: String, version: SolrVersionSelection): SolrParameterEntry? =
        load(version).parametersByName[name]

    /**
     * The names `defType` accepts on the [version] line.
     *
     * **These are registered names, not class names.** `defType` takes `edismax`; the class behind it is
     * `solr.ExtendedDismaxQParserPlugin`, which is what a `<queryParser>` element's `class` attribute
     * names and what [SolrClassCatalog] lists under [SolrClassKind.QUERY_PARSER]. The two populations
     * describe the same plugins and are written in different places, so they are read from different
     * rows.
     *
     * @param version the Solr line this configset targets
     * @return the registered names, sorted as the resource lists them
     */
    fun queryParserNamesFor(version: SolrVersionSelection): List<SolrParameterEntry> =
        load(version).queryParserNames

    /**
     * The registered query parser [name], or null when this line registers none by that name.
     *
     * @param name the name as written in a `defType`
     * @param version the Solr line this configset targets
     * @return the entry, or null
     */
    fun queryParserName(name: String, version: SolrVersionSelection): SolrParameterEntry? =
        load(version).queryParsersByName[name]

    private fun load(version: SolrVersionSelection): ParsedResource = synchronized(byLine) {
        byLine.getOrPut(lineFor(version)) { read(lineFor(version)) }
    }

    /**
     * The line whose resource answers for [version], falling back to the newest.
     *
     * The same rule [SolrClassCatalog] follows: someone reading a configset for a Solr this plugin does
     * not support is better served by the current vocabulary than by silence.
     */
    private fun lineFor(version: SolrVersionSelection): Int {
        val declared = version.guidePathSegment.substringBefore('_').toIntOrNull()
        return declared?.takeIf { it in SUPPORTED_LINES } ?: SUPPORTED_LINES.first()
    }

    private fun read(line: Int): ParsedResource {
        val stream = SolrParameterCatalog::class.java.getResourceAsStream("/solr-catalog/parameters-$line.tsv")
            ?: return ParsedResource(emptyList(), emptyList())
        return stream.bufferedReader().useLines { parse(it) }
    }

    /**
     * The entries in [rows], skipping anything that is not one.
     *
     * Separate from reading the resource so it can be tested against input the generator would never
     * produce, for the reason [SolrClassCatalog.parse] gives: this parser is the one place a malformed or
     * half-written resource reaches the editor, and the guarantee worth having is that it drops the bad
     * row rather than the file.
     *
     * A row whose kind is neither `parameter` nor `queryParserName` is dropped, which is what lets a
     * resource written by a newer build cost one row rather than everything. That silence is a hazard as
     * much as a safeguard — it is exactly how the class catalog's `solrconfig.xml` rows arrived and
     * changed nothing — so `SolrParameterCatalogTest` asserts the shipped resources name no kind this
     * reader does not know.
     *
     * @param rows the resource's lines
     * @return the parameters and the query parser names, each in the order they appear
     */
    internal fun parse(rows: Sequence<String>): ParsedResource {
        val parameters = mutableListOf<SolrParameterEntry>()
        val queryParsers = mutableListOf<SolrParameterEntry>()
        for (row in rows) {
            if (row.startsWith("#") || row.isBlank()) continue
            val columns = row.split('\t')
            if (columns.size < 3) continue
            val entry = SolrParameterEntry(
                name = columns[1].takeIf { it.isNotBlank() } ?: continue,
                owner = columns[2],
                // Absent on a resource generated before the column existed, and blank where Solr wrote
                // no comment on the constant — both read as "nothing to show" rather than as an empty
                // sentence.
                summary = columns.getOrNull(3)?.takeIf { it.isNotBlank() },
            )
            when (columns[0]) {
                PARAMETER_KIND -> parameters += entry
                QUERY_PARSER_KIND -> queryParsers += entry
            }
        }
        return ParsedResource(parameters, queryParsers)
    }

    /** The row kind naming a request parameter. */
    internal const val PARAMETER_KIND = "parameter"

    /** The row kind naming a registered query parser, which is what a `defType` holds. */
    internal const val QUERY_PARSER_KIND = "queryParserName"

    /**
     * One line's resource, parsed once.
     *
     * The by-name maps are built here rather than searched per lookup because documentation asks for one
     * parameter by name on every hover, and the resource carries several hundred rows. The class catalog
     * answers the same question with a linear scan over a comparable population, which is a measurement
     * worth making there rather than a pattern worth copying here.
     *
     * @property parameters every request parameter, in resource order
     * @property queryParserNames every registered query parser name, in resource order
     */
    internal class ParsedResource(
        val parameters: List<SolrParameterEntry>,
        val queryParserNames: List<SolrParameterEntry>,
    ) {
        val parametersByName: Map<String, SolrParameterEntry> = parameters.associateBy { it.name }
        val queryParsersByName: Map<String, SolrParameterEntry> = queryParserNames.associateBy { it.name }
    }
}
