package org.apache.solr.ide.model.schema

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
 * What a boolean property's value means for a field, in the two registers the plugin renders.
 *
 * Two lengths rather than one because the two surfaces have incompatible budgets. The popup has
 * room for a consequence in a full sentence; the inlay is beside the declaration and each phrase
 * is its own segment against a 30-character renderer limit. Deriving one from the other would mean
 * truncating a sentence, which is how a hint ends up saying "The original value is not retu…".
 *
 * Held here rather than in either feature's presentation code for the reason
 * [org.apache.solr.ide.model.schema.SolrMatchCapability.summary] already gives: the same field described
 * two ways is two chances to be doubted.
 *
 * @property whenTrue the consequence of the value being `true`, as a sentence for the popup
 * @property whenFalse the consequence of the value being `false`, as a sentence for the popup
 * @property inlineWhenTrue the terse phrase shown inline when the value is `true`, or null for a
 *   property that is explained on request but does not earn space beside every declaration
 * @property inlineWhenFalse the terse phrase shown inline when the value is `false`, null on the
 *   same terms — never null alone, since a property that speaks for one value must speak for both
 */
data class SolrPropertyMeaning(
    val whenTrue: String,
    val whenFalse: String,
    val inlineWhenTrue: String? = null,
    val inlineWhenFalse: String? = null,
)

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
 * @property meaning what each of its two values means for a field, or null where the property
 *   takes something other than a boolean and so has no two consequences to state
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
    val meaning: SolrPropertyMeaning? = null,
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
        boolean(
            "indexed", "Whether the field can be searched or sorted on.", "true",
            whenTrue = "Can be searched, filtered and sorted on.",
            whenFalse = "Cannot be searched or filtered — the value is carried but never queryable.",
            inlineWhenTrue = "indexed",
            inlineWhenFalse = "not indexed",
        ),
        boolean(
            "stored", "Whether the original value can be returned in results.", "true",
            whenTrue = "The original value is returned in results and available to highlighting.",
            whenFalse = "No original value is kept for retrieval, and highlighting has none to work from; " +
                "doc values, if this field has them, can still put a value in results.",
            inlineWhenTrue = "stored",
            inlineWhenFalse = "not stored",
        ),
        boolean(
            "docValues",
            "Whether a column-oriented structure is built, used for sorting, faceting and grouping.",
            null,
            typeDefault = SolrTypeDefaultRule.DOC_VALUES,
            whenTrue = "A column store is built, so sorting, faceting, grouping and function queries are efficient.",
            whenFalse = "No column store; sorting and faceting must un-invert the index at query time, or fail outright.",
            inlineWhenTrue = "doc values",
            inlineWhenFalse = "no doc values",
        ),
        boolean(
            "multiValued",
            "Whether one document may hold several values.",
            null,
            defaultTrueWithin = SolrVersionRange(below = 1.1f),
            whenTrue = "One document may hold several values for this field.",
            whenFalse = "One document may hold at most one value; a second causes an indexing error.",
            inlineWhenTrue = "multi-valued",
            inlineWhenFalse = "single-valued",
        ),
        boolean(
            "required", "Whether a document lacking this field is rejected.", "false",
            whenTrue = "A document lacking this field is rejected at index time.",
            whenFalse = "A document may omit this field.",
        ),
        SolrFieldProperty("default", "A value used when the document supplies none.", "any value of the type", null),
        boolean(
            "omitNorms",
            "Whether length normalisation and index-time boosts are discarded, saving memory.",
            null,
            typeDefault = SolrTypeDefaultRule.OMIT_NORMS,
            whenTrue = "Length normalisation and index-time boosts are discarded, saving memory; short and long values score alike.",
            whenFalse = "Norms are kept, so shorter values score higher for the same match.",
        ),
        boolean(
            "omitTermFreqAndPositions",
            "Whether term frequency and position data are discarded. Phrase queries stop working.",
            null,
            whenTrue = "Term frequency and position data are discarded. Phrase and proximity queries stop working on this field.",
            whenFalse = "Frequencies and positions are kept, so phrase and proximity queries work.",
        ),
        boolean(
            "omitPositions", "Whether positions are discarded while frequencies are kept.", null,
            whenTrue = "Positions are discarded while frequencies are kept, so scoring still reflects repetition but phrase queries stop working.",
            whenFalse = "Positions are kept, so phrase queries work.",
        ),
        boolean(
            "termVectors", "Whether term vectors are stored, used by highlighting and more-like-this.", "false",
            whenTrue = "Term vectors are stored, which highlighting and more-like-this can use instead of re-analysing the value.",
            whenFalse = "No term vectors; highlighting re-analyses the stored value instead.",
        ),
        boolean(
            "termPositions", "Whether positions are stored in the term vector.", "false",
            whenTrue = "Positions are stored in the term vector.",
            whenFalse = "The term vector carries no positions.",
        ),
        boolean(
            "termOffsets", "Whether offsets are stored in the term vector.", "false",
            whenTrue = "Offsets are stored in the term vector, which is what fast vector highlighting needs.",
            whenFalse = "The term vector carries no offsets.",
        ),
        boolean(
            "termPayloads", "Whether payloads are stored in the term vector.", "false",
            whenTrue = "Payloads are stored in the term vector.",
            whenFalse = "The term vector carries no payloads.",
        ),
        boolean(
            "sortMissingFirst", "Whether documents lacking this field sort first.", "false",
            whenTrue = "Documents lacking this field sort before all others, in either direction.",
            whenFalse = "Nothing forces documents lacking this field to sort first.",
        ),
        boolean(
            "sortMissingLast", "Whether documents lacking this field sort last.", "false",
            whenTrue = "Documents lacking this field sort after all others, in either direction.",
            whenFalse = "Nothing forces documents lacking this field to sort last.",
        ),
        boolean(
            "uninvertible",
            "Whether the field may be un-inverted at query time when it has no doc values.",
            null,
            defaultTrueWithin = SolrVersionRange(below = 1.7f),
            whenTrue = "The field may be un-inverted at query time when it has no doc values — correct, but memory-hungry on a large index.",
            whenFalse = "Sorting or faceting without doc values fails rather than silently building a field cache.",
        ),
        boolean(
            "useDocValuesAsStored",
            "Whether doc values are returned as though the field were stored.",
            null,
            defaultTrueWithin = SolrVersionRange(from = 1.6f),
            whenTrue = "Doc values are returned as though the field were stored, so even a wildcard fl gets a value back.",
            whenFalse = "A wildcard fl leaves this field out unless it is stored; naming it explicitly still returns its doc values.",
        ),
        boolean(
            "large", "Whether the value is lazily loaded and not cached above 512KB.", "false",
            whenTrue = "The value is loaded lazily and not held in the document cache above 512KB.",
            whenFalse = "The value is loaded and cached like any other.",
        ),
        SolrFieldProperty(
            "positionIncrementGap",
            "Distance inserted between the values of a multi-valued field, so a phrase query cannot match across two of them.",
            "an integer",
            null,
            SolrPropertyScope.TYPE_ONLY,
        ),
        boolean(
            "autoGeneratePhraseQueries",
            "Whether adjacent terms are turned into a phrase query automatically.",
            null,
            scope = SolrPropertyScope.TYPE_ONLY,
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
        boolean(
            "enableGraphQueries",
            "Whether graph-aware filters produce graph queries. Relevant to text fields queried with sow=false.",
            "true",
            scope = SolrPropertyScope.TYPE_ONLY,
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

    /**
     * One boolean entry of [ALL], with the consequences its two values carry.
     *
     * Most of the table takes `true` or `false`, and writing that out as a literal on each of them
     * was as many chances to write it differently — [SolrFieldProperty.BOOLEAN_VALUES] is the
     * spelling [SolrFieldProperty.valueType] reads, so a typo would silently demote a property to
     * free text. The rows keep their order and their strings; this removes only the scaffolding
     * they had in common. A property with no [SolrFieldProperty.meaning] passes neither sentence
     * and gets none.
     */
    private fun boolean(
        name: String,
        summary: String,
        default: String?,
        whenTrue: String? = null,
        whenFalse: String? = null,
        inlineWhenTrue: String? = null,
        inlineWhenFalse: String? = null,
        scope: SolrPropertyScope = SolrPropertyScope.FIELD_AND_TYPE,
        defaultTrueWithin: SolrVersionRange? = null,
        typeDefault: SolrTypeDefaultRule? = null,
    ): SolrFieldProperty = SolrFieldProperty(
        name = name,
        summary = summary,
        validValues = SolrFieldProperty.BOOLEAN_VALUES,
        defaultValue = default,
        scope = scope,
        defaultTrueWithin = defaultTrueWithin,
        typeDefault = typeDefault,
        meaning = if (whenTrue != null && whenFalse != null) {
            SolrPropertyMeaning(whenTrue, whenFalse, inlineWhenTrue, inlineWhenFalse)
        } else {
            null
        },
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
