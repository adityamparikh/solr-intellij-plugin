package org.apache.solr.ide.model

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
            simpleName.endsWith("CharFilterFactory") -> "charfilterfactories"
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
         * The line a `<luceneMatchVersion>` implies.
         *
         * `luceneMatchVersion` names a *Lucene* version, not a Solr one — Solr 10.0 pairs with
         * Lucene 10.3, Solr 9.10 with Lucene 9.12 — so only the major component is used to pick a
         * line. Deriving a full Solr version from a Lucene one is not possible without a table that
         * would need updating on every release, and the major is what decides which guide applies.
         *
         * @param luceneMatchVersion the declared value, such as `9.12.0`
         * @return the selection, or [DEFAULT] when the value is not a recognized version
         */
        fun fromLuceneMatchVersion(luceneMatchVersion: String): SolrVersionSelection {
            val major = luceneMatchVersion.trim().substringBefore('.').toIntOrNull() ?: return DEFAULT
            if (major < MINIMUM_GUIDE_MAJOR) return DEFAULT
            return SolrVersionSelection("${major}_0", SolrVersionSource.CONFIGSET)
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
