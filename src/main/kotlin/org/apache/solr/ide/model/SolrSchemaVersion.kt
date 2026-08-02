package org.apache.solr.ide.model

/**
 * The `version` attribute on a schema's root element, which decides what several field attributes
 * default to.
 *
 * **This is a third version number, and not either one the plugin already tracks.**
 * [SolrVersionSelection] is the Solr *line* a configset targets, derived from `luceneMatchVersion`
 * in `solrconfig.xml`, and it decides which generated catalog to read. This is a property of the
 * schema file itself, it runs from `1.0` to `1.7`, and it decides what Solr falls back to for an
 * attribute the file does not declare.
 *
 * The two are independent: Solr 10 honours a `version="1.1"` schema's 2008 defaults, because this
 * attribute is how Solr changed its defaults without breaking schemas already in the field. Schema
 * version 1.7 arrived in Solr 9.7 and turned `docValues` on and `uninvertible` off by default;
 * upgrading Solr does not rewrite the attribute, and moving a schema up generally requires
 * re-indexing, so both sides of that change are current and long-lived.
 *
 * @property declared the version the schema declares, as a number
 */
@JvmInline
value class SolrSchemaVersion(val declared: Float) {

    /** Reading a schema's declaration, including the case where it makes none. */
    companion object {

        /**
         * What Solr assumes when a schema declares no version.
         *
         * `IndexSchema` parses the attribute with `"1.0"` as its fallback, so a schema without one
         * is not a modern schema — it is the oldest one, with the oldest defaults. Assuming the
         * newest here would report today's answers for a file Solr is running under 2008 semantics.
         */
        val ASSUMED: SolrSchemaVersion = SolrSchemaVersion(1.0f)

        /**
         * Reads a declared version, falling back to [ASSUMED] when there is none to read.
         *
         * Unparseable text falls back rather than failing: a half-typed attribute is the normal
         * state of a file being edited, and the model has to keep answering while it is.
         *
         * @param declared the attribute's value, or null when the schema omits it
         * @return the version to resolve defaults against
         */
        fun of(declared: String?): SolrSchemaVersion =
            declared?.trim()?.toFloatOrNull()?.let(::SolrSchemaVersion) ?: ASSUMED
    }
}

/**
 * The span of schema versions over which a default holds.
 *
 * Solr's own rules are written as one-sided comparisons against a literal — `schemaVersion >= 1.6f`,
 * `schemaVersion < 1.7f` — so both bounds are optional and an omitted one is unbounded. The lower
 * bound is inclusive and the upper exclusive, matching the comparisons this is read from.
 *
 * @property from the first version the default holds at, or null when it holds from the beginning
 * @property below the first version the default stops holding at, or null when it never stops
 */
data class SolrVersionRange(val from: Float? = null, val below: Float? = null) {

    /**
     * Whether [version] falls in this range.
     *
     * @param version the schema's declared version
     * @return true when the default this range qualifies applies
     */
    operator fun contains(version: SolrSchemaVersion): Boolean =
        (from == null || version.declared >= from) && (below == null || version.declared < below)
}
