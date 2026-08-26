package org.apache.solr.ide.model

import org.apache.solr.ide.model.vocabulary.SolrClassCatalog
import org.apache.solr.ide.model.vocabulary.SolrClassKind

/**
 * Builds links into the Apache Solr Reference Guide.
 *
 * **Links, never copies.** The Reference Guide explains concepts with examples and context that
 * javadoc does not; carrying that prose inside the plugin would mean maintaining a second body of
 * documentation that goes stale on its own schedule. A link costs no licensing question and is
 * always current.
 *
 * **A dead link is worse than no link.** Anchors in the guide are generated from headings and drift
 * between releases, so this returns page-level links only, and returns null rather than guessing
 * when there is no page it is confident about. A user who follows one link into a 404 stops trusting
 * the next one.
 *
 * Nothing here fetches anything. Links are constructed and displayed; the browser resolves them if
 * the user clicks. Documentation therefore works offline, minus the link.
 */
object SolrReferenceGuide {

    /**
     * The guide page describing Solr's built-in field types.
     *
     * Page-level by design: field types are rows in a table on this page and have no per-class
     * anchor, so a link to `#StrField` would be a link to nothing.
     *
     * @param version the Solr line the configset targets, as [SolrVersionSelection] resolved it
     * @return an absolute URL
     */
    fun fieldTypesPage(version: SolrVersionSelection): String =
        "${base(version)}/indexing-guide/field-types-included-with-solr.html"

    /**
     * The guide page describing the analyzer component named by [className], or null if none is
     * known for it.
     *
     * Only the three top-level pages are used — tokenizers, filters, char filters — chosen by what
     * kind of component this is. That is coarse, and deliberately so: per-factory anchors exist on
     * some of these pages and not others, and verifying each one against each supported release is
     * work that buys a scroll position.
     *
     * @param className a factory class name, in either the `solr.` or fully qualified form
     * @param version the Solr line the configset targets
     * @return an absolute URL, or null when the class is not a recognized kind of component
     */
    fun analyzerComponentPage(className: String, version: SolrVersionSelection): String? {
        val simpleName = className.substringAfterLast('.')
        val page = when {
            // `charfilters`, not `charfilterfactories`. The page was renamed between 9.0 and 9.7:
            // measured against the published guide, `charfilters.html` answers on 9_7, 9_8, 9_10,
            // 9_11 and `latest`, and `charfilterfactories.html` answers only on 9_0 and `latest`.
            // The old name looked correct for as long as every Solr 9 configset was sent to the 9.0
            // guide, which is the defect [SolrVersionSelection.fromLuceneMatchVersion] fixes -- so
            // these two corrections are one change, and either alone is worse than neither.
            simpleName.endsWith("CharFilterFactory") -> "charfilters"
            simpleName.endsWith("TokenizerFactory") -> "tokenizers"
            simpleName.endsWith("FilterFactory") -> "filters"
            else -> return null
        }
        return "${base(version)}/indexing-guide/$page.html"
    }

    /**
     * The guide page describing the class a `class` attribute names, chosen by what the class is.
     *
     * One mapping on purpose: the popup's footer link and the external-documentation URL must
     * never disagree about where a class is documented, and an exhaustive `when` here is what
     * makes a new [SolrClassKind] update both under compiler pressure.
     *
     * @param kind what the class is
     * @param className the class name, in either the `solr.` or fully qualified form
     * @param version the Solr line the configset targets
     * @return an absolute URL, or null when no page is known for it
     */
    fun classPage(kind: SolrClassKind, className: String, version: SolrVersionSelection): String? =
        when (kind) {
            SolrClassKind.FIELD_TYPE -> fieldTypesPage(version)
            SolrClassKind.TOKENIZER,
            SolrClassKind.TOKEN_FILTER,
            SolrClassKind.CHAR_FILTER,
            -> analyzerComponentPage(className, version)

            // `solrconfig.xml`. Every page named here was checked to exist on both supported lines
            // and to describe the element it is reached from; the two kinds whose page could not be
            // established are null below rather than pointed at a plausible neighbour, because a
            // link that lands somewhere unrelated costs more than an absent one.
            SolrClassKind.REQUEST_HANDLER,
            SolrClassKind.SEARCH_COMPONENT,
            -> "${base(version)}/configuration-guide/requesthandlers-searchcomponents.html"

            SolrClassKind.QUERY_PARSER -> "${base(version)}/query-guide/query-syntax-and-parsers.html"
            SolrClassKind.QUERY_RESPONSE_WRITER -> "${base(version)}/query-guide/response-writers.html"
            SolrClassKind.UPDATE_PROCESSOR -> "${base(version)}/configuration-guide/update-request-processors.html"
            SolrClassKind.TRANSFORMER -> "${base(version)}/query-guide/document-transformers.html"
            SolrClassKind.EXPRESSIBLE -> "${base(version)}/query-guide/streaming-expressions.html"
            SolrClassKind.CODEC_FACTORY -> "${base(version)}/configuration-guide/codec-factory.html"
            SolrClassKind.SCHEMA_FACTORY -> "${base(version)}/configuration-guide/schema-factory.html"
            SolrClassKind.DIRECTORY_FACTORY -> "${base(version)}/configuration-guide/index-location-format.html"
            SolrClassKind.DELETION_POLICY -> "${base(version)}/configuration-guide/index-segments-merging.html"
            SolrClassKind.CIRCUIT_BREAKER -> "${base(version)}/deployment-guide/circuit-breakers.html"
            SolrClassKind.QUERY_CONVERTER -> "${base(version)}/query-guide/spell-checking.html"

            // A cache and a searcher listener are the same subject: warming is what a listener is
            // for, and the page treats them together.
            SolrClassKind.CACHE,
            SolrClassKind.LISTENER,
            -> "${base(version)}/configuration-guide/caches-warming.html"

            // The page does not use the word `valueSourceParser`, but it is what the element is for:
            // a value source parser registers a function, and this is the page about functions.
            SolrClassKind.VALUE_SOURCE_PARSER -> "${base(version)}/query-guide/function-queries.html"

            // No page describes either. `indexReaderFactory` is absent from the index-location page
            // that covers its neighbour `directoryFactory`, and `statsCache` appears on no page that
            // could be found -- so both link nowhere rather than somewhere adjacent and wrong.
            SolrClassKind.INDEX_READER_FACTORY,
            SolrClassKind.STATS_CACHE,
            -> null
        }

    /**
     * The guide page describing field type definitions and the properties fields may carry.
     *
     * This is the page behind every entry in
     * [SolrFieldProperties][org.apache.solr.ide.model.schema.SolrFieldProperties]: it holds the property table
     * with descriptions, accepted values and defaults.
     *
     * @param version the Solr line the configset targets
     * @return an absolute URL
     */
    fun fieldPropertiesPage(version: SolrVersionSelection): String =
        "${base(version)}/indexing-guide/field-type-definitions-and-properties.html"

    private fun base(version: SolrVersionSelection): String =
        "https://solr.apache.org/guide/solr/${version.guidePathSegment}"
}

/**
 * Which Solr line the plugin is reasoning about, and how it worked that out.
 *
 * The source is carried alongside the answer so a user can tell whether documentation and completion
 * came from their server, from their configset, or from a default — which is the difference between
 * a fact and a guess.
 *
 * @property guidePathSegment the path segment naming this line in Reference Guide URLs
 * @property source how the line was decided
 */
data class SolrVersionSelection(
    val guidePathSegment: String,
    val source: SolrVersionSource,
) {

    /**
     * How this selection came about, phrased for a user rather than for a log.
     *
     * Shown beside the link so a reader can tell whether the documentation matches what they run,
     * what their configset says, or merely the newest release.
     *
     * @return a short phrase naming the line and its source
     */
    fun describeSource(): String = when (source) {
        SolrVersionSource.SERVER -> "the connected server"
        SolrVersionSource.CONFIGSET -> "the version this configset declares"
        SolrVersionSource.DEFAULT -> "the latest release, since this configset declares no version"
    }

    /** Where a version came from. */
    companion object {

        /**
         * The fallback used when nothing declares a version.
         *
         * `latest` rather than a pinned line, because a pinned one goes stale on a release the
         * plugin was not rebuilt for, and stale is how links start 404ing.
         */
        val DEFAULT: SolrVersionSelection = SolrVersionSelection("latest", SolrVersionSource.DEFAULT)

        /**
         * The line a connected server reported.
         *
         * **No Lucene translation, unlike [fromLuceneMatchVersion], and that is the whole difference
         * between them.** A configset declares a *Lucene* back-compat target and only ever *implies*
         * a Solr line — Solr 10.0 pairs with Lucene 10.3. A server states its own Solr version
         * outright, so the two must not share a path: reading a server's `10.0.0` through the
         * configset arm would be translating something that was never encoded.
         *
         * **A line this build ships no catalog for keeps [SolrVersionSource.SERVER] and names the
         * newest guide**, where [fromLuceneMatchVersion] falls back to [DEFAULT] entirely. The two
         * alternatives are both worse: falling back would discard the fact that a server answered at
         * all, and constructing a segment would invent a guide URL for a release that may not be
         * published. The version string itself stays on the model for display, which is where a
         * reader looks to find out what they actually reached.
         *
         * @param serverVersion the version a server reported, such as `10.0.0`
         * @return the selection, always sourced to the server
         */
        fun fromServerVersion(serverVersion: String): SolrVersionSelection {
            val major = serverVersion.trim().substringBefore('.').toIntOrNull()
            val segment = major
                ?.takeIf { it >= MINIMUM_GUIDE_MAJOR }
                ?.let { SolrClassCatalog.guideSegmentFor(it) }
            return SolrVersionSelection(segment ?: DEFAULT.guidePathSegment, SolrVersionSource.SERVER)
        }

        /**
         * The line a `<luceneMatchVersion>` implies.
         *
         * `luceneMatchVersion` names a *Lucene* version, not a Solr one — Solr 10.0 pairs with
         * Lucene 10.3, Solr 9.10 with Lucene 9.12 — so only the major component is read from it.
         * Deriving a full Solr version from a Lucene one is not possible without a table that would
         * need updating on every release.
         *
         * **The minor comes from the catalog rather than from a guess, and that is the correction.**
         * This previously assembled the segment as `${major}_0`, which is a real guide for a release
         * nobody here runs: every Solr 9 configset was sent to the Solr **9.0** documentation while
         * every fact rendered beside the link came from the 9.10.1 catalog. Nothing failed, because
         * `9_0` is published and answers — the links were live and about the wrong Solr, which is
         * harder to notice than a 404 and worse than one.
         * [SolrClassCatalog.guideSegmentFor][org.apache.solr.ide.model.vocabulary.SolrClassCatalog.guideSegmentFor]
         * reads the release out of the catalog header, so the link and the facts cannot disagree
         * and no supported release is named outside the build.
         *
         * Compare [fromServerVersion], which translates nothing because a server states its own
         * Solr version rather than implying one.
         *
         * A major this build ships no catalog for falls back to [DEFAULT] rather than to a
         * constructed segment. Solr 11 has no guide at `11_0` until it is released, and `latest` is
         * the honest answer for a configset from the future.
         *
         * @param luceneMatchVersion the declared value, such as `9.12.0`
         * @return the selection, or [DEFAULT] when the value names no line this build ships
         */
        fun fromLuceneMatchVersion(luceneMatchVersion: String): SolrVersionSelection {
            val major = luceneMatchVersion.trim().substringBefore('.').toIntOrNull() ?: return DEFAULT
            if (major < MINIMUM_GUIDE_MAJOR) return DEFAULT
            val segment = SolrClassCatalog.guideSegmentFor(major) ?: return DEFAULT
            return SolrVersionSelection(segment, SolrVersionSource.CONFIGSET)
        }

        /**
         * The oldest major with a Reference Guide at the modern `guide/solr/<line>` path.
         *
         * Older guides live at a different path shape entirely, and the plugin does not support
         * those Solr lines anyway, so a link to one would be a link to documentation for a version
         * this plugin declines to support.
         */
        private const val MINIMUM_GUIDE_MAJOR = 9
    }
}

/** How the Solr line in use was decided. */
enum class SolrVersionSource {

    /** A connected server reported it. The authority, when there is one. */
    SERVER,

    /** `<luceneMatchVersion>` in the configset declared it. */
    CONFIGSET,

    /** Nothing declared anything, so the newest documentation is used. */
    DEFAULT,
}
