package org.apache.solr.ide.model

/**
 * What each schema element's attributes accept.
 *
 * Answered here rather than in the inspection that asks, because *what does this attribute accept*
 * and *is this the complete set of attributes* are two questions with very different answers, and
 * conflating them is how a checker starts underlining correct files. Only the first is answered so
 * far; the second arrives with the inspection that needs it.
 */
object SolrAttributeVocabulary {

    /**
     * Attributes that name a thing rather than configure it.
     *
     * The generated catalog strips `class` and `name` on purpose — a component's base class consumes
     * them with `args.remove`, which reads identically to a real attribute — so anything comparing
     * against the catalog has to allow them here or it would flag the very `class` attribute it used
     * to find the entry. `type` and `dest`/`source` are structural in the same way.
     */
    private val STRUCTURAL: Set<String> = setOf("class", "name", "type", "source", "dest")

    /**
     * The analysis elements, whose `class` names an entry in the generated catalog.
     *
     * Mapped to the kind so a `<filter>` is checked against filters rather than against every class
     * Solr ships; offering or accepting a tokenizer's attributes on a filter would be wrong in a way
     * a reader has no way to detect.
     */
    private val ANALYSIS_KINDS: Map<String, SolrClassKind> = mapOf(
        "tokenizer" to SolrClassKind.TOKENIZER,
        "filter" to SolrClassKind.TOKEN_FILTER,
        "charFilter" to SolrClassKind.CHAR_FILTER,
    )

    /**
     * What [attribute] accepts on [tag], or null when there is nothing to check.
     *
     * **Non-null means checkable.** A [SolrValueType.FREE] attribute answers null rather than
     * returning a type that accepts everything, so a caller cannot mistake "any value is legal" for
     * "this value was verified". Null is also the answer whenever the plugin cannot be certain, and
     * every caller must treat both cases as "say nothing".
     *
     * @param tag the element's name, as written
     * @param attribute the attribute's name, as written
     * @param className the element's `class` attribute, where it has one
     * @param version the Solr line this configset targets
     * @return the value type and its members, or null when there is nothing to check
     */
    fun typeOf(
        tag: String,
        attribute: String,
        className: String?,
        version: SolrVersionSelection,
    ): SolrTypedAttribute? {
        if (attribute in STRUCTURAL) return null
        val property = propertiesFor(tag)?.firstOrNull { it.name == attribute }
        if (property != null) return checkable(property.valueType, property.closedValues)
        val kind = ANALYSIS_KINDS[tag] ?: return null
        val entry = SolrClassCatalog.find(className ?: return null, version)
            ?.takeIf { it.kind == kind }
            ?: return null
        val known = entry.attribute(attribute) ?: return null
        return checkable(known.valueType, emptyList())
    }

    private fun checkable(type: SolrValueType, members: List<String>): SolrTypedAttribute? =
        if (type == SolrValueType.FREE) null else SolrTypedAttribute(type, members)

    private fun propertiesFor(tag: String): List<SolrFieldProperty>? = when (tag) {
        in SolrSchemaElementNames.FIELD -> SolrFieldProperties.FOR_FIELD
        in SolrSchemaElementNames.FIELD_TYPE -> SolrFieldProperties.FOR_FIELD_TYPE
        else -> null
    }
}

/**
 * One attribute's accepted values.
 *
 * @property valueType what kind of value it takes
 * @property members the legal names when [valueType] is [SolrValueType.ENUM]
 */
data class SolrTypedAttribute(
    val valueType: SolrValueType,
    val members: List<String> = emptyList(),
) {

    /**
     * Whether [value] is one this attribute accepts.
     *
     * @param value the attribute value exactly as written
     * @return false only when the value definitely cannot satisfy this attribute
     */
    fun accepts(value: String): Boolean = valueType.accepts(value, members)
}

/**
 * The schema element names this model reasons about.
 *
 * Duplicated from the activation package deliberately: nothing in `model` may import an IntelliJ
 * type, and keeping this here is what lets the vocabulary be tested as a pure function.
 */
object SolrSchemaElementNames {

    /** The elements that declare a field. */
    val FIELD: Set<String> = setOf("field", "dynamicField")

    /** The elements that declare a field type, in both spellings Solr has used. */
    val FIELD_TYPE: Set<String> = setOf("fieldType", "fieldtype")
}
