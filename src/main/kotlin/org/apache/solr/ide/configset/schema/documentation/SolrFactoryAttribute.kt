package org.apache.solr.ide.configset.schema.documentation

import org.apache.solr.ide.model.vocabulary.SolrClassAttribute

/**
 * Where one factory attribute's effective value came from.
 *
 * Deliberately narrower than [org.apache.solr.ide.model.schema.SolrPropertyOrigin]. A factory has no type
 * layer and no schema-version defaults — only what the tag wrote and what the catalog recorded as
 * Solr's literal fallback — so the four states below are the whole story. Collapsing required and
 * unset into a single "unknown" would lose the one fact the catalog *can* cite about a missing
 * required attribute, which is that Solr will refuse the configuration without it.
 */
enum class SolrFactoryAttributeOrigin {

    /** Written on the factory tag itself. */
    TAG,

    /** The catalog's literal default, used because the tag omits the attribute. */
    SOLR_DEFAULT,

    /** Required by the class and absent from the tag — Solr will reject the configuration. */
    REQUIRED,

    /**
     * Optional, unwritten, and the catalog carries no literal default.
     *
     * Distinct from [REQUIRED]: the configuration is legal, and the value Solr will use is either
     * computed at runtime or simply absent. The popup must not invent one.
     */
    UNSET,
}

/**
 * One factory attribute at its effective value for a particular tag.
 *
 * @property attribute the catalog's description of the attribute
 * @property value the value in effect — written or defaulted — or null when neither is known
 * @property origin where that value came from, or why there is none
 */
data class SolrEffectiveFactoryAttribute(
    val attribute: SolrClassAttribute,
    val value: String?,
    val origin: SolrFactoryAttributeOrigin,
)
