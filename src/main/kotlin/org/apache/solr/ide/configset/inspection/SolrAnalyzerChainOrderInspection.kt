package org.apache.solr.ide.configset.inspection

import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.psi.PsiElementVisitor
import com.intellij.psi.XmlElementVisitor
import com.intellij.psi.xml.XmlAttributeValue
import com.intellij.psi.xml.XmlTag
import org.apache.solr.ide.SolrBundle
import org.apache.solr.ide.configset.activation.SolrConfigsetDetector
import org.apache.solr.ide.configset.activation.SolrConfigsetFileKind
import org.apache.solr.ide.configset.activation.SolrSchemaTags
import org.apache.solr.ide.model.SolrMatchAnalysis

/**
 * Reports an analyzer component placed where the components above it have made its work impossible.
 *
 * An analyzer chain is a pipeline, and the order of its filters is the whole of its meaning — which
 * is why a mis-ordered chain is the hardest kind of schema defect to see. Nothing is misspelled,
 * every class exists, every attribute is legal, and Solr starts without a word of complaint. The
 * filter simply never does anything, and the only evidence is a search that quietly fails to match.
 *
 * **The bar for reporting here is that the chain's order makes the filter's work impossible, not
 * that the order is unusual.** Analyzer chains are the part of a schema where expert users
 * deliberately do surprising things — a stemmer before a synonym filter, a pattern replacement
 * between two tokenizing steps — and an inspection with opinions about style would fire constantly
 * on chains that are working exactly as intended. Both rules below are provable from the order
 * alone:
 *
 *  - **A graph flattener above every filter that produces a graph.** `FlattenGraphFilterFactory`
 *    exists to flatten the token graph a `SynonymGraphFilterFactory` or
 *    `WordDelimiterGraphFilterFactory` emits, and a pipeline stage cannot flatten output that is
 *    produced after it runs. Reported only when no graph filter precedes it *and* one follows: a
 *    chain may legitimately flatten twice, once after each producer, and the second flattener has a
 *    producer above it. A tokenizer that emits a graph itself — Kuromoji, Nori — runs above every
 *    filter and silences the rule for the whole chain.
 *  - **`splitOnCaseChange` below a filter that folds case.** The option splits `iPhone` into `i`
 *    and `Phone` by finding the case transition. A `LowerCaseFilterFactory` above it has already
 *    turned that into `iphone`, and there is no transition left to find.
 *
 * **The case-folding rule fires only on the attribute written in the file, never on the default.**
 * `splitOnCaseChange` is on unless set, so the majority of chains with a lowercase filter above a
 * word-delimiter filter are technically in this state — and their authors never asked for case
 * splitting, so telling them they are not getting it is noise. Written, the attribute is a stated
 * intention the chain cannot honour, which is a defect worth a warning. Defaulted, it is Solr's
 * opinion rather than the author's.
 *
 * **What was considered and left out.** A `KeywordMarkerFilterFactory` with no stemmer below it is
 * equally inert, and it was left out because Solr ships dozens of language stemmers: recognizing a
 * handful of them would report a German chain as broken for using
 * `GermanLightStemFilterFactory`, and enumerating them all would put this inspection in the business
 * of tracking Lucene's analysis modules. A `FlattenGraphFilterFactory` in a chain with *no* graph
 * filter at all is also inert, but proving it needs to know that the *tokenizer* produces no graph
 * either, and `JapaneseTokenizerFactory` does. Both are true findings that this rule set cannot
 * establish from ordering alone, which is the line it holds to.
 *
 * **The graph-producing tokenizers are named, and naming too few is the safe direction.** Missing
 * one costs a false positive on a correct chain, which is the failure this package is organised to
 * avoid; listing one that does not produce a graph costs only a report this inspection would
 * otherwise have made. [GRAPH_PRODUCING_TOKENIZERS] is therefore read as *do not report*, never as
 * evidence for reporting.
 *
 * **No quick-fix.** The repair is to move a filter, and where to move it to is a question about
 * intent: the flattener may belong after the graph filter, or the graph filter may belong first.
 * Reordering a pipeline on the user's behalf, in the one place where order is meaning, is more
 * confidence than the plugin has earned.
 *
 * Both the index-time and query-time chains are checked. The claim is about a pipeline, and it holds
 * wherever the pipeline runs.
 */
class SolrAnalyzerChainOrderInspection : LocalInspectionTool() {

    /**
     * Runs while the project is still indexing.
     *
     * Everything this reads is in the tag being visited and its siblings. Nothing consults an index,
     * so waiting for one would withhold a working feature for no reason.
     */
    override fun isDumbAware(): Boolean = true

    /**
     * @param holder collects the problems found
     * @param isOnTheFly whether this is an editor pass rather than a batch run
     * @return a visitor over analyzer filters, or an empty one outside a schema
     */
    override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean): PsiElementVisitor {
        // Analyzer chains live in the schema. The gate is the file's kind rather than its name,
        // because Solr has used three names for it, and then the activation gate, because a schema
        // outside a configset is not this plugin's business.
        if (SolrConfigsetFileKind.forFileName(holder.file.name)?.isSchema != true) {
            return PsiElementVisitor.EMPTY_VISITOR
        }
        if (!SolrConfigsetDetector.isConfigsetFile(holder.file)) return PsiElementVisitor.EMPTY_VISITOR

        return object : XmlElementVisitor() {
            override fun visitXmlTag(tag: XmlTag) {
                if (tag.localName != SolrSchemaTags.FILTER) return
                val chain = tag.parentTag?.takeIf { it.localName == SolrSchemaTags.ANALYZER } ?: return
                // Filters run in the order they are written, so the tags before this one in the
                // chain are exactly the stages upstream of it. Only filter tags are counted: a
                // comment or a stray element between two filters is not a stage.
                val filters = chain.subTags.filter { it.localName == SolrSchemaTags.FILTER }
                val above = filters.takeWhile { it !== tag }
                val below = filters.dropWhile { it !== tag }.drop(1)
                // Solr collects a chain's components by element name and runs the tokenizer first
                // whatever position it is written in, so it is upstream of every filter here. Both
                // rules ask something of it, and they must ask the same element.
                val tokenizer = chain.subTags.firstOrNull { it.localName == SolrSchemaTags.TOKENIZER }
                reportFlattenerAboveEveryGraph(holder, tag, tokenizer, above, below)
                reportCaseSplitBelowCaseFolding(holder, tag, tokenizer, above)
            }
        }
    }

    private companion object {

        /**
         * Reports a graph flattener that runs before anything has produced a graph to flatten.
         *
         * The [below] half of the condition is what keeps this honest. A flattener with no graph
         * filter anywhere in the chain is also doing nothing, but establishing that needs to know
         * the tokenizer produces no graph either — and `JapaneseTokenizerFactory` does, so the
         * finding would be wrong on a Japanese chain. With a producer *below* it, the ordering
         * alone proves the mistake: that producer's graph cannot reach a stage that has already run.
         *
         * **A [tokenizer] that emits a graph itself silences the rule outright**, and for the same
         * reason. It runs above every filter, so the flattener has real work to do no matter what
         * follows: a Kuromoji chain that flattens the tokenizer's compound splitting and then flattens
         * a synonym graph further down is two producers answered in order, not a misplaced stage.
         */
        fun reportFlattenerAboveEveryGraph(
            holder: ProblemsHolder,
            filter: XmlTag,
            tokenizer: XmlTag?,
            above: List<XmlTag>,
            below: List<XmlTag>,
        ) {
            val declaration = classValueOf(filter) ?: return
            if (simpleName(declaration.value) != FLATTEN_GRAPH) return
            if (tokenizer != null && simpleName(classOf(tokenizer)) in GRAPH_PRODUCING_TOKENIZERS) return
            if (above.any { producesGraph(it) }) return
            val producer = below.firstOrNull { producesGraph(it) } ?: return
            SolrInspections.reportOnValue(
                holder,
                declaration,
                SolrBundle.message("inspection.analyzerChainOrder.flattenerAboveGraph", classOf(producer)),
            )
        }

        /**
         * Reports a request to split on case transitions that the chain has already folded away.
         *
         * The tokenizer is consulted whatever position it is written in: Solr collects the chain's
         * components by element name and runs the tokenizer first regardless, so a
         * `LowerCaseTokenizerFactory` written below a filter still runs above it. For the filters,
         * written order *is* running order, which is why they are taken as they come.
         */
        fun reportCaseSplitBelowCaseFolding(
            holder: ProblemsHolder,
            filter: XmlTag,
            tokenizer: XmlTag?,
            above: List<XmlTag>,
        ) {
            if (simpleName(classOf(filter)) !in WORD_DELIMITERS) return
            val request = filter.getAttribute(SPLIT_ON_CASE_CHANGE)?.valueElement ?: return
            if (request.value !in ASKED_FOR) return
            val folder = (listOfNotNull(tokenizer) + above)
                .firstOrNull { SolrMatchAnalysis.foldsCase(classOf(it)) }
                ?: return
            SolrInspections.reportOnValue(
                holder,
                request,
                SolrBundle.message("inspection.analyzerChainOrder.caseAlreadyFolded", classOf(folder)),
            )
        }

        /** Whether [component] emits a token graph, which is the thing a flattener flattens. */
        fun producesGraph(component: XmlTag): Boolean = simpleName(classOf(component)) in GRAPH_PRODUCERS

        /** The `class` attribute's value element, the anchor a finding about the component sits on. */
        fun classValueOf(component: XmlTag): XmlAttributeValue? = component.getAttribute(CLASS)?.valueElement

        /** The class a component names, as written, which is also how a message should quote it. */
        fun classOf(component: XmlTag): String = component.getAttributeValue(CLASS).orEmpty()

        /** A factory's simple name, whether it was written as `solr.X` or fully qualified. */
        fun simpleName(className: String): String = className.substringAfterLast('.')

        /** The filter whose only purpose is to flatten what a graph filter above it produced. */
        const val FLATTEN_GRAPH = "FlattenGraphFilterFactory"

        /**
         * Filters emitting a token graph.
         *
         * The `Graph` suffix is the convention and not the rule, so these are named rather than
         * matched: `ManagedSynonymGraphFilterFactory` follows it and a custom filter may not.
         */
        val GRAPH_PRODUCERS = setOf(
            "SynonymGraphFilterFactory",
            "ManagedSynonymGraphFilterFactory",
            "WordDelimiterGraphFilterFactory",
        )

        /**
         * Tokenizers emitting a token graph, which makes a flattener anywhere below them useful.
         *
         * The morphological analyzers for languages written without spaces, where a compound is
         * segmented into its parts *and* kept whole at the same position. Kuromoji does this in the
         * `search` mode it defaults to; Nori does it under `decompoundMode="mixed"`, having defaulted
         * to discarding the original since it was contributed.
         *
         * **The mode attribute is deliberately not read.** Doing so would turn a question this rule
         * declines to answer — is this flattener useful? — into one it would have to get right on a
         * chain configured either way, and the cost is asymmetric: a Nori chain left in its default
         * mode merely keeps a flattener this inspection would otherwise have reported, while reading
         * the attribute wrongly underlines a working chain.
         *
         * Short on purpose, and it makes the rule's silence wider rather than its reporting: an
         * unlisted graph-producing tokenizer is the one way this can still be wrong on a correct
         * chain, so a name belongs here the moment there is doubt.
         */
        val GRAPH_PRODUCING_TOKENIZERS = setOf(
            "JapaneseTokenizerFactory",
            "KoreanTokenizerFactory",
        )

        /** The filters accepting [SPLIT_ON_CASE_CHANGE], both the graph-aware one and its predecessor. */
        val WORD_DELIMITERS = setOf(
            "WordDelimiterGraphFilterFactory",
            "WordDelimiterFilterFactory",
        )

        /** The attribute asking a word-delimiter filter to split where the case changes. */
        const val SPLIT_ON_CASE_CHANGE = "splitOnCaseChange"

        /**
         * Values that turn [SPLIT_ON_CASE_CHANGE] on.
         *
         * Solr reads it as an integer, so `1` is the documented spelling; `true` is accepted here
         * because configsets are full of it and the author's intention is not in doubt. Anything
         * else — `0`, `false`, or a typo — leaves this silent, since a value Solr cannot read is the
         * invalid-attribute-value inspection's finding and not this one's.
         */
        val ASKED_FOR = setOf("1", "true")

        /** The attribute naming a component's factory. */
        const val CLASS = "class"
    }
}
