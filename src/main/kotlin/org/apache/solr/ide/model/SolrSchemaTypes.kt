package org.apache.solr.ide.model

/**
 * One component of an analyzer chain — a char filter, a tokenizer or a token filter.
 *
 * @property className the factory named by the `class` attribute, as written (`solr.LowerCaseFilterFactory`)
 * @property attributes every other attribute on the element, by name
 */
data class SolrAnalyzerComponent(
    val className: String,
    val attributes: Map<String, String> = emptyMap(),
) {
    /**
     * The value of the attribute naming an external resource, or null if this component names none.
     *
     * The attribute differs by factory — `words` on a stop filter, `synonyms` on a synonym filter,
     * `protected` on a stemmer — so all three are checked rather than the caller guessing. This is
     * what makes a filter's resource navigable to the file beside it.
     */
    val resourceAttribute: String?
        get() = RESOURCE_ATTRIBUTES.firstNotNullOfOrNull { attributes[it] }

    /** Attribute names that name a file inside the configset. */
    private companion object {
        val RESOURCE_ATTRIBUTES = listOf("words", "synonyms", "protected", "dictionary", "stopwords")
    }
}

/**
 * An index-time or query-time analyzer chain.
 *
 * @property charFilters char filters, in declaration order
 * @property tokenizer the tokenizer, or null if the chain declares none (`class` on the analyzer itself)
 * @property filters token filters, in declaration order
 * @property className the analyzer's own `class`, set when a chain is declared as a single class
 */
data class SolrAnalyzerChain(
    val charFilters: List<SolrAnalyzerComponent> = emptyList(),
    val tokenizer: SolrAnalyzerComponent? = null,
    val filters: List<SolrAnalyzerComponent> = emptyList(),
    val className: String? = null,
) {
    /** Every component in pipeline order, which is the order that determines what the field matches. */
    val components: List<SolrAnalyzerComponent>
        get() = charFilters + listOfNotNull(tokenizer) + filters
}

/**
 * A field type declaration.
 *
 * @property name the type's name, as fields reference it
 * @property className the implementing class named by `class`
 * @property attributes every other attribute on the `fieldType` element
 * @property indexAnalyzer the index-time chain, or null if none is declared
 * @property queryAnalyzer the query-time chain, or null if none is declared
 */
data class SolrFieldType(
    val name: String,
    val className: String,
    val attributes: Map<String, String> = emptyMap(),
    val indexAnalyzer: SolrAnalyzerChain? = null,
    val queryAnalyzer: SolrAnalyzerChain? = null,
) {
    /**
     * Whether this type analyzes its input at all.
     *
     * A type with no chain — `StrField`, the numeric and date types — matches whole values only,
     * which is the distinction match analysis is built on.
     */
    val isAnalyzed: Boolean get() = indexAnalyzer != null || queryAnalyzer != null
}

/**
 * A declared field.
 *
 * Boolean attributes are nullable because Solr treats *unset* differently from *false*: an unset
 * attribute inherits from the field type, so collapsing the two would silently invent a value the
 * configset never stated.
 *
 * @property name the field name
 * @property type the name of its field type
 * @property indexed whether the field is indexed, or null if unset
 * @property stored whether the field is stored, or null if unset
 * @property docValues whether the field has doc values, or null if unset
 * @property multiValued whether the field is multi-valued, or null if unset
 * @property required whether the field is required, or null if unset
 * @property defaultValue the `default` attribute, or null if unset
 */
data class SolrField(
    val name: String,
    val type: String,
    val indexed: Boolean? = null,
    val stored: Boolean? = null,
    val docValues: Boolean? = null,
    val multiValued: Boolean? = null,
    val required: Boolean? = null,
    val defaultValue: String? = null,
)

/**
 * A dynamic field declaration, whose [pattern] is matched against field names at index time.
 *
 * @property pattern the glob, with at most one leading or trailing `*` — Solr allows no others
 * @property field the declaration itself, whose `name` is the pattern
 */
data class SolrDynamicField(val pattern: String, val field: SolrField) {

    /**
     * Whether [fieldName] matches this pattern.
     *
     * Solr's globs are deliberately impoverished — a single `*` at one end, or the bare `*` — so
     * this is prefix and suffix matching rather than a regular expression. Matching a name against
     * a pattern that is neither is treated as an exact comparison, which is what Solr does with a
     * `dynamicField` whose name contains no wildcard at all.
     *
     * @param fieldName the concrete field name to test
     * @return true if this dynamic field would supply [fieldName]
     */
    fun matches(fieldName: String): Boolean = when {
        pattern == "*" -> true
        pattern.startsWith("*") -> fieldName.endsWith(pattern.substring(1))
        pattern.endsWith("*") -> fieldName.startsWith(pattern.dropLast(1))
        else -> fieldName == pattern
    }

    /**
     * How specific this pattern is, used to pick a winner when several match.
     *
     * Solr resolves a name against the *longest* matching pattern, so a name matching both `*_str`
     * and `*_s` takes the former. Length of the literal part is exactly that ordering.
     */
    val specificity: Int get() = pattern.count { it != '*' }
}

/**
 * A `copyField` directive.
 *
 * @property source the source field name or glob
 * @property destination the destination field name
 * @property maxChars the `maxChars` limit, or null if unset
 */
data class SolrCopyField(
    val source: String,
    val destination: String,
    val maxChars: Int? = null,
)

/**
 * A field name referenced from `solrconfig.xml` rather than declared in the schema.
 *
 * These are the references that cross the file boundary — a handler's `qf` naming fields the schema
 * defines — and nothing in either file connects them, which is why they are modelled explicitly.
 *
 * @property handlerName the `name` of the request handler, such as `/select`
 * @property parameterName the parameter naming the field, such as `qf`
 * @property fieldName the field named
 * @property boost the `^`-suffixed boost, when the parameter carries one
 */
data class SolrFieldReference(
    val handlerName: String,
    val parameterName: String,
    val fieldName: String,
    val boost: String? = null,
)
