package org.apache.solr.ide.model

/**
 * What each schema element accepts, and whether that list is the whole story.
 *
 * Two questions are answered here rather than in the inspections that ask them, because both
 * inspections need the same answer and a second copy would drift. The questions are deliberately
 * separate: *what does this attribute accept* is answerable far more often than *is this the
 * complete set of attributes*, and conflating them is how a checker starts underlining correct
 * files.
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
     * Field attributes Solr accepts that the Reference Guide's field table never lists.
     *
     * `FieldProperties` in solr-core is the authority on what a `<field>` may carry, and it accepts
     * three names the guide's table omits — `tokenized` and `binary` are index-detail bits a schema
     * may still set, and `storeOffsetsWithPositions` is documented only alongside the highlighter
     * that reads it. `postingsFormat` and `docValuesFormat` are stranger: `isPropertyIgnored`
     * exempts them by name, so on a field they load without error and do nothing, being read only
     * from the type. All five are names Solr accepts — solr-core 9 and 10 agree on the list — so a
     * closed vocabulary omitting them would underline a file Solr loads. None of them join
     * [SolrFieldProperties], because completion and documentation offer what the guide describes,
     * not everything the parser tolerates.
     */
    private val FIELD_ACCEPTED_UNDOCUMENTED: Set<String> = setOf(
        "tokenized",
        "binary",
        "storeOffsetsWithPositions",
        "postingsFormat",
        "docValuesFormat",
    )

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

    /**
     * Every attribute [tag] accepts, or null when its vocabulary is not closed.
     *
     * **Null is not "none".** It means an attribute absent from any list the plugin holds is not
     * thereby wrong, and the caller must not report one. Three cases answer null, each for its own
     * reason:
     *
     * A `<fieldType>` always does. It delegates to classes its own configuration names — a
     * `providerClass` picks the `ExchangeRateProvider` that reads `currencyConfig`, and no walk from
     * the field type reaches a collaborator chosen at runtime. Solr's own
     * `sample_techproducts_configs` writes exactly that attribute, so treating a field type's list as
     * complete would underline a configset Solr ships.
     *
     * An analysis element whose `class` the catalog does not know does too, which is how a custom
     * plugin class stays unflagged without anything having to recognize it as custom.
     *
     * So does any element this does not model, which is most of a schema.
     *
     * @param tag the element's name, as written
     * @param className the element's `class` attribute, where it has one
     * @param version the Solr line this configset targets
     * @return the complete set of legal attribute names, or null when it cannot be known
     */
    fun closedVocabularyFor(
        tag: String,
        className: String?,
        version: SolrVersionSelection,
    ): Set<String>? {
        if (tag in SolrSchemaElementNames.FIELD) {
            return SolrFieldProperties.FOR_FIELD.mapTo(mutableSetOf()) { it.name } +
                STRUCTURAL + FIELD_ACCEPTED_UNDOCUMENTED
        }
        val kind = ANALYSIS_KINDS[tag] ?: return null
        val entry = SolrClassCatalog.find(className ?: return null, version)
            ?.takeIf { it.kind == kind }
            ?: return null
        // A known class with no recovered attributes is not evidence that it accepts none. Every
        // analysis class Solr ships recovers at least `luceneMatchVersion` from the root of the
        // hierarchy, so an empty list means the extraction failed rather than that the class is bare.
        if (entry.attributes.isEmpty()) return null
        return entry.attributes.mapTo(mutableSetOf()) { it.name } + STRUCTURAL
    }

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
