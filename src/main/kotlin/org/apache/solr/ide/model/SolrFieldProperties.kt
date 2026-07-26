package org.apache.solr.ide.model

/**
 * Where a property's effective value came from.
 *
 * The three-level resolution is the thing no external documentation can do for a user: the
 * Reference Guide can say what `omitNorms` defaults to, but only the plugin can say what it is
 * *for this field in this schema*, and whether that came from the field, from its type, or from
 * Solr.
 */
enum class SolrPropertyOrigin {

    /** Declared on the `<field>` itself. */
    FIELD,

    /** Not on the field, but declared on its `<fieldType>`, from which the field inherits it. */
    FIELD_TYPE,

    /** Declared in neither, so Solr's own default applies. */
    SOLR_DEFAULT,

    /** Declared in neither, and Solr's default depends on the field type's implementing class. */
    UNDETERMINED,
}

/**
 * One property a `<field>` or `<fieldType>` may carry.
 *
 * @property name the attribute name as written in the schema
 * @property summary what it does, in one line
 * @property validValues the values it accepts
 * @property defaultValue Solr's default, or null when the default depends on the field type
 * @property sinceMajor the oldest supported Solr major that has this property
 */
data class SolrFieldProperty(
    val name: String,
    val summary: String,
    val validValues: String,
    val defaultValue: String?,
    val sinceMajor: Int = 9,
)

/**
 * One property's value for a particular field, and where that value came from.
 *
 * @property property the property in question
 * @property value the effective value, or null when it cannot be determined
 * @property origin where the value came from
 */
data class SolrEffectiveProperty(
    val property: SolrFieldProperty,
    val value: String?,
    val origin: SolrPropertyOrigin,
)

/**
 * The properties a schema field or field type may declare, what they mean, and what they default to.
 *
 * **Hand-maintained, and that is a deliberate exception** to the rule that Solr's vocabulary is
 * generated at build time. The spec argues the general case under "The factory catalog": the
 * roughly 130 analysis factories are too many to hand-maintain and change with Solr versions. This
 * set is neither. It is about twenty entries, it has been stable across Solr majors, and — like
 * match analysis — it *defines* the semantics rather than enumerating what happens to exist.
 *
 * It is also not reachable by the catalog's machinery: the catalog reflects over analysis factory
 * classes, and these properties are read by `SchemaField` and `FieldType` from an argument map,
 * with defaults that live in branching code rather than in any enumerable structure.
 *
 * **Some defaults genuinely depend on the field type**, and those are recorded as null rather than
 * given a plausible value. `omitNorms` defaults true for primitive types and false for text;
 * `docValues` is documented as "true for most fields". Asserting one answer where Solr has two is
 * exactly the kind of confident wrong statement that gets a plugin distrusted, so this reports
 * [SolrPropertyOrigin.UNDETERMINED] and links to the guide instead.
 *
 * The table is verified against the Reference Guide for the supported lines. Both currently agree;
 * [SolrFieldProperty.sinceMajor] carries the difference if they ever stop agreeing.
 */
object SolrFieldProperties {

    /** Every property this knows about, in the order the Reference Guide lists them. */
    val ALL: List<SolrFieldProperty> = listOf(
        SolrFieldProperty("indexed", "Whether the field can be searched or sorted on.", "true or false", "true"),
        SolrFieldProperty("stored", "Whether the original value can be returned in results.", "true or false", "true"),
        SolrFieldProperty(
            "docValues",
            "Whether a column-oriented structure is built, used for sorting, faceting and grouping.",
            "true or false",
            null,
        ),
        SolrFieldProperty("multiValued", "Whether one document may hold several values.", "true or false", "false"),
        SolrFieldProperty("required", "Whether a document lacking this field is rejected.", "true or false", "false"),
        SolrFieldProperty("default", "A value used when the document supplies none.", "any value of the type", null),
        SolrFieldProperty(
            "omitNorms",
            "Whether length normalisation and index-time boosts are discarded, saving memory.",
            "true or false",
            null,
        ),
        SolrFieldProperty(
            "omitTermFreqAndPositions",
            "Whether term frequency and position data are discarded. Phrase queries stop working.",
            "true or false",
            null,
        ),
        SolrFieldProperty("omitPositions", "Whether positions are discarded while frequencies are kept.", "true or false", null),
        SolrFieldProperty("termVectors", "Whether term vectors are stored, used by highlighting and more-like-this.", "true or false", "false"),
        SolrFieldProperty("termPositions", "Whether positions are stored in the term vector.", "true or false", "false"),
        SolrFieldProperty("termOffsets", "Whether offsets are stored in the term vector.", "true or false", "false"),
        SolrFieldProperty("termPayloads", "Whether payloads are stored in the term vector.", "true or false", "false"),
        SolrFieldProperty("sortMissingFirst", "Whether documents lacking this field sort first.", "true or false", "false"),
        SolrFieldProperty("sortMissingLast", "Whether documents lacking this field sort last.", "true or false", "false"),
        SolrFieldProperty("uninvertible", "Whether the field may be un-inverted at query time when it has no doc values.", "true or false", "false"),
        SolrFieldProperty("useDocValuesAsStored", "Whether doc values are returned as though the field were stored.", "true or false", "true"),
        SolrFieldProperty("large", "Whether the value is lazily loaded and not cached above 512KB.", "true or false", "false"),
    )

    /** Lookup by attribute name. */
    private val BY_NAME: Map<String, SolrFieldProperty> = ALL.associateBy { it.name }

    /**
     * The property called [name], or null if it is not one this knows about.
     *
     * @param name an attribute name from a schema
     * @return the property, or null
     */
    fun byName(name: String): SolrFieldProperty? = BY_NAME[name]

    /**
     * Every property's effective value for [field], resolved through its [fieldType].
     *
     * Only properties that resolve to something are returned. A property declared nowhere, whose
     * default depends on the field type, is reported with [SolrPropertyOrigin.UNDETERMINED] and a
     * null value rather than being omitted — a user asking about `omitNorms` is better served by
     * "depends on the type, see the guide" than by silence.
     *
     * @param field the field to resolve for
     * @param fieldType its type, or null when the type it names is not declared
     * @return one entry per known property, in Reference Guide order
     */
    fun effectiveFor(field: SolrField, fieldType: SolrFieldType?): List<SolrEffectiveProperty> =
        ALL.map { property -> resolve(property, field, fieldType) }

    /**
     * One property's effective value for [field].
     *
     * @param property the property to resolve
     * @param field the field to resolve for
     * @param fieldType its type, or null when undeclared
     * @return the effective value and where it came from
     */
    fun resolve(property: SolrFieldProperty, field: SolrField, fieldType: SolrFieldType?): SolrEffectiveProperty {
        field.attributes[property.name]?.let {
            return SolrEffectiveProperty(property, it, SolrPropertyOrigin.FIELD)
        }
        fieldType?.attributes?.get(property.name)?.let {
            return SolrEffectiveProperty(property, it, SolrPropertyOrigin.FIELD_TYPE)
        }
        return if (property.defaultValue != null) {
            SolrEffectiveProperty(property, property.defaultValue, SolrPropertyOrigin.SOLR_DEFAULT)
        } else {
            SolrEffectiveProperty(property, null, SolrPropertyOrigin.UNDETERMINED)
        }
    }
}
