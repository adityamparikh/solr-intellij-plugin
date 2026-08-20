package org.apache.solr.ide.model.schema

import org.apache.solr.ide.model.vocabulary.SolrClassCatalog

/**
 * Derives what a field can match from its index-time analyzer chain.
 *
 * A pure function, exhaustively testable, and the source of the plugin's most surprising output: a
 * user who has read their own schema many times still often cannot say whether a search for `wid`
 * hits a product called `widget`, because the answer is a property of the analyzer chain rather
 * than of anything written in the field declaration.
 *
 * **The factories are named here in code, deliberately, rather than read from the generated
 * factory catalog.** The spec argues this out under "The factory catalog": this set *defines* the
 * semantics rather than enumerating what exists, and it has been stable across Solr majors while
 * the surrounding list has not. A catalog regenerated for a new Solr line must not silently change
 * what the plugin claims a field matches.
 *
 * **The catalog is consulted for one thing only: which spelling names which class.** A schema may
 * write `<filter name="lowercase"/>` instead of `<filter class="solr.LowerCaseFilterFactory"/>`,
 * and no rule turns one into the other — the mapping is a fact about Lucene's registrations, read
 * from the `NAME` constant each factory declares. That is a different question from what a factory
 * *does*, which is still answered only by the sets below. The distinction is what keeps the
 * paragraph above true: a regenerated catalog can change whether a component is recognized, and a
 * component that stops being recognized drops [SolrMatchCapability.confident] and is reported as
 * nothing rather than as something new.
 *
 * **Only the index-time chain is analyzed.** What is in the index is what determines whether a term
 * can be found at all. A query-time chain that disagrees with it is a real defect, but a different
 * one — and one that needs both chains rather than this classification of a single chain.
 */
object SolrMatchAnalysis {

    /**
     * What a field of type [fieldType] can match.
     *
     * A type with no index-time analyzer — `StrField`, the numeric and date types — matches whole
     * values, case-sensitively. That is not a fallback: an unanalyzed field really does hold its
     * input as one term.
     *
     * @param fieldType the type to classify
     * @return its match capability
     */
    fun of(fieldType: SolrFieldType): SolrMatchCapability = of(fieldType.indexAnalyzer)

    /**
     * What a field analyzed by [chain] can match.
     *
     * @param chain the index-time chain, or null when the type declares none
     * @return the capability the chain produces
     */
    fun of(chain: SolrAnalyzerChain?): SolrMatchCapability {
        if (chain == null) return UNANALYZED

        var granularity = SolrMatchGranularity.TOKENS
        var caseSensitive = true
        var prefix = SolrPrefixSupport.NONE
        var confident = true
        val evidence = mutableListOf<SolrMatchEvidence>()

        val tokenizer = chain.tokenizer
        if (tokenizer == null) {
            // A chain declared as a single analyzer class, with no components to walk. Its
            // behaviour lives inside that class, which is exactly what this analysis cannot see.
            return UNANALYZED.copy(granularity = SolrMatchGranularity.TOKENS, confident = false)
        }

        when (val name = simpleName(tokenizer.className)) {
            in WHOLE_VALUE_TOKENIZERS -> {
                granularity = SolrMatchGranularity.WHOLE_VALUE
                evidence += SolrMatchEvidence(SolrMatchTrait.GRANULARITY, name)
            }
            in PREFIX_TOKENIZERS -> {
                prefix = PREFIX_TOKENIZERS.getValue(name)
                evidence += SolrMatchEvidence(SolrMatchTrait.GRANULARITY, name)
                evidence += SolrMatchEvidence(SolrMatchTrait.PREFIX, name)
            }
            in CASE_FOLDING_TOKENIZERS -> {
                caseSensitive = false
                evidence += SolrMatchEvidence(SolrMatchTrait.GRANULARITY, name)
                evidence += SolrMatchEvidence(SolrMatchTrait.CASE, name)
            }
            in SPLITTING_TOKENIZERS -> evidence += SolrMatchEvidence(SolrMatchTrait.GRANULARITY, name)
            else -> confident = false
        }

        for (filter in chain.filters) {
            when (val name = simpleName(filter.className)) {
                in CASE_FOLDING_FILTERS -> if (caseSensitive) {
                    caseSensitive = false
                    evidence += SolrMatchEvidence(SolrMatchTrait.CASE, name)
                }
                in PARTIAL_MATCH_FILTERS -> {
                    // A later n-gram filter replaces an earlier one's contribution: the terms in the
                    // index are whatever the last one produced.
                    prefix = PARTIAL_MATCH_FILTERS.getValue(name)
                    evidence.removeAll { it.trait == SolrMatchTrait.PREFIX }
                    evidence += SolrMatchEvidence(SolrMatchTrait.PREFIX, name)
                }
                in TOKEN_SPLITTING_FILTERS -> if (granularity == SolrMatchGranularity.WHOLE_VALUE) {
                    // The ordering case that changes the answer: a keyword tokenizer produces one
                    // term, and a splitting filter downstream takes it apart again. The field is
                    // tokenized despite its tokenizer.
                    granularity = SolrMatchGranularity.TOKENS
                    evidence.removeAll { it.trait == SolrMatchTrait.GRANULARITY }
                    evidence += SolrMatchEvidence(SolrMatchTrait.GRANULARITY, name)
                }
                in NEUTRAL_FILTERS -> Unit
                else -> confident = false
            }
        }

        for (charFilter in chain.charFilters) {
            if (simpleName(charFilter.className) !in NEUTRAL_CHAR_FILTERS) confident = false
        }

        return SolrMatchCapability(granularity, caseSensitive, prefix, evidence.toList(), confident)
    }

    /**
     * Whether the factory [className] folds case, so that nothing downstream can see a case
     * boundary.
     *
     * Asked here rather than answered again elsewhere. The sets below are the plugin's one authority
     * on which factories do this, and the ordering inspection needs the same answer for a different
     * question — whether a filter that splits on case transitions can still find any. A second copy
     * of these names is how the two would come to disagree about `ICUFoldingFilterFactory`.
     *
     * Tokenizers count as well as filters: `LowerCaseTokenizerFactory` folds as it splits, so a
     * chain using it has no case boundaries left even with no filter at all.
     *
     * @param className the factory as written — `solr.X`, fully qualified, or the SPI short name
     * @return true if a component of this class removes case distinctions
     */
    fun foldsCase(className: String): Boolean =
        simpleName(className).let { it in CASE_FOLDING_FILTERS || it in CASE_FOLDING_TOKENIZERS }

    /**
     * A factory's simple name, however the schema spelled it.
     *
     * Three spellings reach here. `solr.X` and the fully qualified form both carry the class name
     * and differ only in what precedes the last dot. The third carries no class name at all:
     * `<tokenizer name="standard"/>` names the factory by the SPI name it registers under, and that
     * is the spelling Solr's own `_default` and techproducts configsets use exclusively.
     *
     * The sets below are keyed by class name, so an SPI name is resolved through the catalog before
     * it is looked up. A name that resolves to nothing is returned unchanged and therefore matches
     * nothing, which is the correct outcome — an unrecognized component drops
     * [SolrMatchCapability.confident] rather than being guessed at.
     *
     * Public because the ordering inspection needs the same normalization for its own questions, and
     * kept it as a private copy until this resolution step made the two answers differ.
     *
     * @param className the factory as written — `solr.X`, fully qualified, or the SPI short name
     * @return the simple class name to look up, or [className] unchanged if it resolves to nothing
     */
    fun simpleName(className: String): String {
        if ('.' in className) return className.substringAfterLast('.')
        return SolrClassCatalog.classForSpiName(className)?.substringAfterLast('.') ?: className
    }

    /** The capability of a field with no index-time analysis at all. */
    private val UNANALYZED = SolrMatchCapability(
        granularity = SolrMatchGranularity.WHOLE_VALUE,
        caseSensitive = true,
        prefix = SolrPrefixSupport.NONE,
    )

    // --- the named factories ------------------------------------------------------------------
    //
    // The sets below decide these three questions. Everything else in a chain changes which *terms*
    // end up in the index without changing whether the field matches whole values or tokens,
    // prefixes or not, case-sensitively or not.
    //
    // Deliberately not counted here. An earlier version of this comment said "roughly fifteen",
    // which the sets outgrew without anyone noticing, and the number had been copied into the spec,
    // the plan and the user guide by the time it was wrong in all four. A reader who needs the count
    // can read the sets; a reader who needs the rule is not helped by it.

    /** Tokenizers emitting the entire input as one term. */
    private val WHOLE_VALUE_TOKENIZERS = setOf("KeywordTokenizerFactory")

    /** Tokenizers that also decide partial-match support, mapped to what they provide. */
    private val PREFIX_TOKENIZERS = mapOf(
        "EdgeNGramTokenizerFactory" to SolrPrefixSupport.EDGE_NGRAM,
        "NGramTokenizerFactory" to SolrPrefixSupport.N_GRAM,
        "PathHierarchyTokenizerFactory" to SolrPrefixSupport.PATH_HIERARCHY,
    )

    /** Tokenizers that lowercase as they split, so the field is case-insensitive with no filter. */
    private val CASE_FOLDING_TOKENIZERS = setOf("LowerCaseTokenizerFactory")

    /** Tokenizers that split into terms and do nothing else relevant here. */
    private val SPLITTING_TOKENIZERS = setOf(
        "StandardTokenizerFactory",
        "ClassicTokenizerFactory",
        "WhitespaceTokenizerFactory",
        "LetterTokenizerFactory",
        "UAX29URLEmailTokenizerFactory",
        "ICUTokenizerFactory",
        "PatternTokenizerFactory",
        "SimplePatternTokenizerFactory",
        "SimplePatternSplitTokenizerFactory",
    )

    /** Filters that fold case, making the field case-insensitive. */
    private val CASE_FOLDING_FILTERS = setOf(
        "LowerCaseFilterFactory",
        "ICUFoldingFilterFactory",
        "GreekLowerCaseFilterFactory",
        "TurkishLowerCaseFilterFactory",
        "IrishLowerCaseFilterFactory",
    )

    /** Filters producing partial terms, mapped to the matching they make efficient. */
    private val PARTIAL_MATCH_FILTERS = mapOf(
        "EdgeNGramFilterFactory" to SolrPrefixSupport.EDGE_NGRAM,
        "NGramFilterFactory" to SolrPrefixSupport.N_GRAM,
    )

    /** Filters that split one term into several, which can undo a keyword tokenizer. */
    private val TOKEN_SPLITTING_FILTERS = setOf(
        "WordDelimiterGraphFilterFactory",
        "WordDelimiterFilterFactory",
    )

    /**
     * Filters known to change the terms without changing any of the three answers.
     *
     * Listed rather than assumed. An unrecognized filter drops [SolrMatchCapability.confident],
     * because the alternative — treating anything unknown as harmless — is how a wrong hint gets
     * shown confidently, and a wrong hint here is worse than none.
     */
    private val NEUTRAL_FILTERS = setOf(
        "StopFilterFactory",
        "SynonymGraphFilterFactory",
        "SynonymFilterFactory",
        "ASCIIFoldingFilterFactory",
        "PorterStemFilterFactory",
        "SnowballPorterFilterFactory",
        "KStemFilterFactory",
        "EnglishPossessiveFilterFactory",
        "EnglishMinimalStemFilterFactory",
        "RemoveDuplicatesTokenFilterFactory",
        "TrimFilterFactory",
        "LengthFilterFactory",
        "FlattenGraphFilterFactory",
        "ManagedStopFilterFactory",
        "ManagedSynonymGraphFilterFactory",
        "KeywordMarkerFilterFactory",
        "KeywordRepeatFilterFactory",
        "ReversedWildcardFilterFactory",
        "PatternReplaceFilterFactory",
        "ShingleFilterFactory",
    )

    /** Char filters that do not affect any of the three answers. */
    private val NEUTRAL_CHAR_FILTERS = setOf(
        "HTMLStripCharFilterFactory",
        "MappingCharFilterFactory",
        "PatternReplaceCharFilterFactory",
        "ICUNormalizer2CharFilterFactory",
    )
}
