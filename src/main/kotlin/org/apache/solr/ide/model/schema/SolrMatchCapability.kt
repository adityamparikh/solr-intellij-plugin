package org.apache.solr.ide.model.schema

/**
 * Whether a field is indexed as one value or broken into tokens.
 *
 * The distinction users get wrong most often: a `string` field holds `Widget Pro` as a single term,
 * so a search for `Widget` does not match it, while a tokenized field holds `widget` and `pro`
 * separately, so a search for the whole phrase needs a phrase query.
 */
enum class SolrMatchGranularity {

    /** Indexed as a single term. Only an exact match on the entire value hits it. */
    WHOLE_VALUE,

    /** Broken into terms. Individual terms match; the whole value needs a phrase query. */
    TOKENS,
}

/**
 * How, if at all, a field supports partial matching *efficiently* at index time.
 *
 * **Efficiently is the whole point of this type.** A wildcard query like `wid*` works against any
 * indexed field, by expanding the term across the dictionary at query time — so a plain boolean
 * "supports prefix" would be simultaneously true and useless. The distinction users care about is
 * whether the index was built to answer that question cheaply, which is why this names the
 * mechanism rather than asserting a capability.
 */
enum class SolrPrefixSupport {

    /** Nothing in the index-time chain produces partial terms. Wildcards still work, and are slow. */
    NONE,

    /** An edge n-gram filter or tokenizer: every term is ground into its prefixes at index time. */
    EDGE_NGRAM,

    /** An n-gram filter or tokenizer: every substring, so prefixes *and* infixes match. */
    N_GRAM,

    /** A path-hierarchy tokenizer: prefixes at path separators, as `a/b/c` yields `a`, `a/b`, `a/b/c`. */
    PATH_HIERARCHY,
}

/**
 * Which conclusion a factory in the chain was responsible for.
 *
 * Kept because the hint has to be defensible. The demo puts this claim in front of a room and
 * invites the objection, so "tokenized, case-insensitive" is worth much less than the same
 * statement able to name the filter that made it true.
 */
enum class SolrMatchTrait {

    /** Decided [SolrMatchCapability.granularity]. */
    GRANULARITY,

    /** Decided [SolrMatchCapability.caseSensitive]. */
    CASE,

    /** Decided [SolrMatchCapability.prefix]. */
    PREFIX,
}

/**
 * One factory, and the conclusion it was responsible for.
 *
 * @property trait what this factory decided
 * @property factory the factory's simple class name, as `EdgeNGramFilterFactory`
 */
data class SolrMatchEvidence(val trait: SolrMatchTrait, val factory: String)

/**
 * What a field can actually match, derived from its index-time analyzer chain.
 *
 * This is the plugin's most surprising output and the one most likely to tell an experienced user
 * something they did not know, so it is also the one that has to be right. It is a pure function of
 * the chain — see [org.apache.solr.ide.model.schema.SolrMatchAnalysis] — and carries [evidence] so that
 * every claim can name the factory behind it.
 *
 * @property granularity whole value or tokens
 * @property caseSensitive whether case must match; false when something in the chain folds case
 * @property prefix how partial matching is supported at index time, if at all
 * @property evidence the factories responsible, in the order the chain was walked
 * @property confident false when the chain contained a factory this analysis does not know
 */
data class SolrMatchCapability(
    val granularity: SolrMatchGranularity,
    val caseSensitive: Boolean,
    val prefix: SolrPrefixSupport = SolrPrefixSupport.NONE,
    val evidence: List<SolrMatchEvidence> = emptyList(),
    val confident: Boolean = true,
) {

    /** Whether arbitrary substrings match efficiently, which only full n-grams provide. */
    val substringSupported: Boolean get() = prefix == SolrPrefixSupport.N_GRAM

    /**
     * The [summary] as its parts: granularity, case, and how partial matching is supported.
     *
     * Split out for the inline hint, whose renderer truncates any single text segment past a
     * 30-character budget; a part per segment keeps the whole summary visible. [summary] is
     * this list joined, so the two can never disagree.
     */
    val summaryParts: List<String>
        get() = buildList {
            add(if (granularity == SolrMatchGranularity.WHOLE_VALUE) "whole value" else "tokenised")
            add(if (caseSensitive) "case-sensitive" else "case-insensitive")
            when (prefix) {
                SolrPrefixSupport.NONE -> Unit
                SolrPrefixSupport.EDGE_NGRAM -> add("prefix-capable")
                SolrPrefixSupport.N_GRAM -> add("substring-capable")
                SolrPrefixSupport.PATH_HIERARCHY -> add("path-prefix-capable")
            }
        }

    /**
     * A one-line summary: granularity, case, and how partial matching is supported.
     *
     * Lives on the capability rather than in any one feature's presentation code, because the
     * inline hint, the documentation popup and the completion lookup all show it and must never
     * word it differently — the same field described three ways is three chances to be doubted.
     */
    val summary: String
        get() = summaryParts.joinToString(", ")

    /** The factory responsible for [trait], or null if nothing in the chain decided it. */
    fun evidenceFor(trait: SolrMatchTrait): String? = evidence.firstOrNull { it.trait == trait }?.factory
}
