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

    /**
     * Declared in neither, and Solr's default for the version this schema declares applies.
     *
     * Distinct from [SOLR_DEFAULT], which is a default Solr has never made conditional. The
     * difference is what the user would have to change: a value that holds *because the schema says
     * 1.6* is one they can move by editing the root element, and saying so is most of the answer.
     */
    SCHEMA_VERSION_DEFAULT,

    /**
     * Declared in neither, and Solr's default for this field type's class applies.
     *
     * The answer the plugin exists to give: `omitNorms` is true for a `solr.StrField` and false for
     * a `solr.TextField`, and no external documentation can say which of those the field in front
     * of the reader is.
     */
    FIELD_TYPE_DEFAULT,

    /**
     * Declared in neither, and Solr's default depends on a field type class the catalog does not
     * know — a custom plugin type, or one from a Solr line this build has never seen.
     */
    UNDETERMINED,
}

/**
 * One property a `<field>` or `<fieldType>` may carry.
 *
 * @property name the attribute name as written in the schema
 * @property summary what it does, in one line
 * @property validValues the values it accepts
 * @property defaultValue Solr's default, or null when the default depends on the field type
 * @property scope where the property may be written
 * @property closedValues the values it accepts when that set is closed and not boolean; empty when
 *   any value is legal, in which case nothing should be offered rather than a partial list
 * @property defaultTrueWithin the schema versions over which this defaults to `true`, for the
 *   properties Solr made conditional on the schema's declared version; null for the rest, which is
 *   most of them. Set together with a null [defaultValue], since a property cannot both have one
 *   default and have it depend on the version
 * @property typeDefault how the default is decided when it turns on the field type's class as well,
 *   or null when it does not. Also set together with a null [defaultValue], and resolvable only
 *   where the catalog knows the class the type names
 */
data class SolrFieldProperty(
    val name: String,
    val summary: String,
    val validValues: String,
    val defaultValue: String?,
    val scope: SolrPropertyScope = SolrPropertyScope.FIELD_AND_TYPE,
    val closedValues: List<String> = emptyList(),
    val defaultTrueWithin: SolrVersionRange? = null,
    val typeDefault: SolrTypeDefaultRule? = null,
) {

    /**
     * Every value this property accepts, or empty when any value is legal.
     *
     * Booleans are expanded here rather than repeated in [closedValues] on the sixteen entries that
     * take one, which would be the same two strings written sixteen times. A caller offering
     * choices should ask this rather than reading [validValues], which is prose meant for a reader.
     */
    val offerableValues: List<String>
        get() = if (valueType == SolrValueType.BOOLEAN) BOOLEAN_OFFERS else closedValues

    /**
     * What this property accepts, as something a check can act on.
     *
     * Derived from [validValues] rather than declared beside it, because that prose is what the
     * table actually maintains and a second field would be a second thing to keep in step. The two
     * constants below are the whole vocabulary that carries meaning; every other spelling is a
     * sentence for a reader and means "any value of this kind is legal".
     */
    val valueType: SolrValueType
        get() = when {
            validValues == BOOLEAN_VALUES -> SolrValueType.BOOLEAN
            validValues == INTEGER_VALUES -> SolrValueType.INTEGER
            closedValues.isNotEmpty() -> SolrValueType.ENUM
            else -> SolrValueType.FREE
        }

    /** The spellings this table uses for value sets it can act on. */
    companion object {
        /** The [validValues] prose that marks a property as taking only `true` or `false`. */
        const val BOOLEAN_VALUES: String = "true or false"

        /** The [validValues] prose that marks a property as taking a whole number. */
        const val INTEGER_VALUES: String = "an integer"

        private val BOOLEAN_OFFERS = listOf("true", "false")
    }
}

/**
 * Where a property may be written.
 *
 * A field inherits everything its type declares, so most properties are legal on both. A handful
 * configure the type's own behaviour and mean nothing on a field, and offering those on a `<field>`
 * would be offering an error.
 */
enum class SolrPropertyScope {

    /** Legal on both a `field` and a `fieldType`; a field inherits the type's value. */
    FIELD_AND_TYPE,

    /** Legal only on a `fieldType`. */
    TYPE_ONLY,
}

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
 * **Some defaults depend on the field type**, and those carry a [SolrTypeDefaultRule] rather than a
 * value. `omitNorms` defaults true for primitive types and false for text; `docValues` turns on the
 * class as well as the version. Both resolve once the catalog supplies the class's traits, and both
 * report [SolrPropertyOrigin.UNDETERMINED] where it cannot — a type naming a class the catalog does
 * not carry is exactly where asserting a default would be inventing one.
 *
 * **Others depend on the schema's declared version**, and those carry a [SolrVersionRange] instead
 * of a flat default. `uninvertible` defaults true below schema version 1.7 and false from it;
 * `useDocValuesAsStored` is the same shape around 1.6. This is a different dependency from the one
 * above and answerable where that one is not — the schema states its version, so the plugin can
 * resolve these outright rather than deferring to the guide.
 *
 * Both sets of rules stay hand-written beside the properties they qualify, for the same reason the
 * table is: there are a handful, the Reference Guide states them in prose, and they moved once
 * between 2019 and 2026. Recovering them from bytecode would mean interpreting branches on a float
 * comparison, a materially different extractor from the literal-reading one the catalog generator
 * has. The enumerative half — which of Solr's sixty-odd field-type classes carry which trait — is
 * the generator's job, and arrives as [SolrTypeTrait] on the catalog entry.
 *
 * It covers both Reference Guide tables: the field properties a `field` may carry and inherit, and
 * the general properties that only configure a `fieldType`. An earlier revision had only the first,
 * which left six properties invisible to documentation and completion alike.
 *
 * The table is verified against the Reference Guide for the supported lines, which currently agree
 * on all of it. If a future line diverges, this grows a per-line dimension; adding one before that
 * happens would be inventing a distinction Solr does not currently make.
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
            typeDefault = SolrTypeDefaultRule.DOC_VALUES,
        ),
        SolrFieldProperty(
            "multiValued",
            "Whether one document may hold several values.",
            "true or false",
            null,
            defaultTrueWithin = SolrVersionRange(below = 1.1f),
        ),
        SolrFieldProperty("required", "Whether a document lacking this field is rejected.", "true or false", "false"),
        SolrFieldProperty("default", "A value used when the document supplies none.", "any value of the type", null),
        SolrFieldProperty(
            "omitNorms",
            "Whether length normalisation and index-time boosts are discarded, saving memory.",
            "true or false",
            null,
            typeDefault = SolrTypeDefaultRule.OMIT_NORMS,
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
        SolrFieldProperty(
            "uninvertible",
            "Whether the field may be un-inverted at query time when it has no doc values.",
            "true or false",
            null,
            defaultTrueWithin = SolrVersionRange(below = 1.7f),
        ),
        SolrFieldProperty(
            "useDocValuesAsStored",
            "Whether doc values are returned as though the field were stored.",
            "true or false",
            null,
            defaultTrueWithin = SolrVersionRange(from = 1.6f),
        ),
        SolrFieldProperty("large", "Whether the value is lazily loaded and not cached above 512KB.", "true or false", "false"),
        SolrFieldProperty(
            "positionIncrementGap",
            "Distance inserted between the values of a multi-valued field, so a phrase query cannot match across two of them.",
            "an integer",
            null,
            SolrPropertyScope.TYPE_ONLY,
        ),
        SolrFieldProperty(
            "autoGeneratePhraseQueries",
            "Whether adjacent terms are turned into a phrase query automatically.",
            "true or false",
            null,
            SolrPropertyScope.TYPE_ONLY,
            defaultTrueWithin = SolrVersionRange(below = 1.4f),
        ),
        SolrFieldProperty(
            "synonymQueryStyle",
            "How overlapping terms from a synonym filter are combined at query time.",
            "as_same_term, pick_best or as_distinct_terms",
            "as_same_term",
            SolrPropertyScope.TYPE_ONLY,
            listOf("as_same_term", "pick_best", "as_distinct_terms"),
        ),
        SolrFieldProperty(
            "enableGraphQueries",
            "Whether graph-aware filters produce graph queries. Relevant to text fields queried with sow=false.",
            "true or false",
            "true",
            SolrPropertyScope.TYPE_ONLY,
        ),
        SolrFieldProperty(
            "docValuesFormat",
            "A named DocValuesFormat to use instead of the codec default.",
            "a format name registered with the codec",
            null,
            SolrPropertyScope.TYPE_ONLY,
        ),
        SolrFieldProperty(
            "postingsFormat",
            "A named PostingsFormat to use instead of the codec default.",
            "a format name registered with the codec",
            null,
            SolrPropertyScope.TYPE_ONLY,
        ),
    )

    /** The properties legal on a `field` or `dynamicField`. */
    val FOR_FIELD: List<SolrFieldProperty> = ALL.filter { it.scope == SolrPropertyScope.FIELD_AND_TYPE }

    /** The properties legal on a `fieldType`, which is every one of them. */
    val FOR_FIELD_TYPE: List<SolrFieldProperty> = ALL

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
    fun effectiveFor(
        field: SolrField,
        fieldType: SolrFieldType?,
        schemaVersion: SolrSchemaVersion,
        typeTraits: Set<SolrTypeTrait>? = null,
    ): List<SolrEffectiveProperty> =
        FOR_FIELD.map { property -> resolve(property, field, fieldType, schemaVersion, typeTraits) }

    /**
     * One property's effective value for [field].
     *
     * @param property the property to resolve
     * @param field the field to resolve for
     * @param fieldType its type, or null when undeclared
     * @return the effective value and where it came from
     */
    fun resolve(
        property: SolrFieldProperty,
        field: SolrField,
        fieldType: SolrFieldType?,
        schemaVersion: SolrSchemaVersion,
        typeTraits: Set<SolrTypeTrait>? = null,
    ): SolrEffectiveProperty {
        field.attributes[property.name]?.let {
            return SolrEffectiveProperty(property, it, SolrPropertyOrigin.FIELD)
        }
        fieldType?.attributes?.get(property.name)?.let {
            return SolrEffectiveProperty(property, it, SolrPropertyOrigin.FIELD_TYPE)
        }
        // Ahead of the flat default, and only reached when the file declares nothing: Solr's
        // version-conditional branches all sit behind a "was it set explicitly?" guard, and the two
        // branches above are that guard. By here the answer is no, so the comparison is all there is.
        property.defaultTrueWithin?.let { range ->
            val value = if (schemaVersion in range) "true" else "false"
            return SolrEffectiveProperty(property, value, SolrPropertyOrigin.SCHEMA_VERSION_DEFAULT)
        }
        // Null traits and empty traits are different answers, and conflating them is how this would
        // start asserting things. Null means the catalog does not know the class the type names, so
        // nothing can be said; empty means it does know it and the class carries no trait, which
        // makes false a definite answer rather than a guess.
        property.typeDefault?.let { rule ->
            if (typeTraits == null) return SolrEffectiveProperty(property, null, SolrPropertyOrigin.UNDETERMINED)
            val value = if (rule.holdsFor(typeTraits, schemaVersion)) "true" else "false"
            return SolrEffectiveProperty(property, value, SolrPropertyOrigin.FIELD_TYPE_DEFAULT)
        }
        return if (property.defaultValue != null) {
            SolrEffectiveProperty(property, property.defaultValue, SolrPropertyOrigin.SOLR_DEFAULT)
        } else {
            SolrEffectiveProperty(property, null, SolrPropertyOrigin.UNDETERMINED)
        }
    }
}
