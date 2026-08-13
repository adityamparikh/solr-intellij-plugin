package org.apache.solr.ide.model.schema

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Property resolution — the three-level answer no external documentation can give.
 *
 * The Reference Guide can say what `omitNorms` defaults to. Only this can say what it is for a
 * particular field, and whether that came from the field, from its type, or from Solr.
 */
class SolrFieldPropertiesTest {

    private val stringType = SolrFieldType(
        name = "string",
        className = "solr.StrField",
        attributes = mapOf("sortMissingLast" to "true", "docValues" to "true"),
    )

    private val field = SolrField(
        name = "sku",
        type = "string",
        attributes = mapOf("indexed" to "true", "stored" to "false"),
    )

    private fun resolve(
        name: String,
        field: SolrField = this.field,
        type: SolrFieldType? = stringType,
        version: SolrSchemaVersion = SolrSchemaVersion.of("1.7"),
    ) = SolrFieldProperties.resolve(SolrFieldProperties.byName(name)!!, field, type, version)

    // --- restated defaults -------------------------------------------------------------------
    //
    // Whether deleting an attribute would change anything, which is the question the editor dims on.
    // Every case here is a *correct* file, so the failure that matters is claiming a value is
    // removable when removing it would change the field.

    private fun restates(
        name: String,
        written: String,
        type: SolrFieldType? = stringType,
        version: SolrSchemaVersion = SolrSchemaVersion.of("1.7"),
        traits: Set<SolrTypeTrait>? = null,
    ) = SolrFieldProperties.restatesDefault(
        SolrFieldProperties.byName(name)!!,
        written,
        type,
        version,
        traits,
    )

    @Test
    fun `an attribute repeating Solr's own default restates it`() {
        assertTrue(restates("indexed", "true", type = null))
    }

    @Test
    fun `an attribute that changes the value restates nothing`() {
        assertFalse(restates("indexed", "false", type = null))
    }

    @Test
    fun `an attribute repeating what its field type already supplies restates it`() {
        // The type declares docValues="true"; a field writing the same is inherited, not decisive.
        assertTrue(restates("docValues", "true"))
    }

    @Test
    fun `an attribute repeating a version default restates it`() {
        assertTrue(restates("uninvertible", "false", type = null))
        assertFalse(restates("uninvertible", "true", type = null))
    }

    @Test
    fun `a default the catalog cannot determine restates nothing`() {
        // omitNorms depends on the type's class, and null traits mean the catalog does not carry it.
        // Dimming here would tell the reader a value is removable on the strength of a guess.
        assertFalse(restates("omitNorms", "true", traits = null))
        assertFalse(restates("omitNorms", "false", traits = null))
    }

    @Test
    fun `a type default the catalog proves restates it`() {
        assertTrue(restates("omitNorms", "true", traits = setOf(SolrTypeTrait.PRIMITIVE)))
        assertFalse(restates("omitNorms", "false", traits = setOf(SolrTypeTrait.PRIMITIVE)))
    }

    @Test
    fun `a spelling Solr accepts but the table does not match restates nothing`() {
        // Only an exact match dims. Solr would read TRUE as true, so this is a value that could have
        // been removed and is not offered as such — the safe direction to be wrong in.
        assertFalse(restates("indexed", "TRUE", type = null))
    }

    @Test
    fun `a property declared on the field comes from the field`() {
        val stored = resolve("stored")
        assertEquals("false", stored.value)
        assertEquals(SolrPropertyOrigin.FIELD, stored.origin)
    }

    /** The inheritance that makes an unset attribute different from a false one. */
    @Test
    fun `a property declared only on the type is inherited from it`() {
        val sortMissingLast = resolve("sortMissingLast")
        assertEquals("true", sortMissingLast.value)
        assertEquals(SolrPropertyOrigin.FIELD_TYPE, sortMissingLast.origin)
    }

    @Test
    fun `the field wins over the type when both declare it`() {
        val withOverride = field.copy(attributes = field.attributes + ("docValues" to "false"))
        val docValues = resolve("docValues", withOverride)
        assertEquals("false", docValues.value)
        assertEquals(SolrPropertyOrigin.FIELD, docValues.origin)
    }

    /** `required` is one of the defaults Solr has never made conditional on the schema version. */
    @Test
    fun `a property declared nowhere falls back to Solr's default`() {
        val required = resolve("required")
        assertEquals("false", required.value)
        assertEquals(SolrPropertyOrigin.SOLR_DEFAULT, required.origin)
    }

    /**
     * The honesty case. `omitNorms` defaults true for primitive types and false for text, so a
     * single asserted default would be wrong half the time — and a confidently wrong statement in
     * documentation is how a plugin stops being trusted.
     */
    @Test
    fun `a property whose default depends on the field type says so instead of guessing`() {
        val omitNorms = resolve("omitNorms")
        assertNull(omitNorms.value)
        assertEquals(SolrPropertyOrigin.UNDETERMINED, omitNorms.origin)
    }

    @Test
    fun `an undetermined default is still resolved when the type declares one`() {
        val typeDeclares = stringType.copy(attributes = stringType.attributes + ("omitNorms" to "true"))
        val omitNorms = resolve("omitNorms", type = typeDeclares)
        assertEquals("true", omitNorms.value)
        assertEquals(SolrPropertyOrigin.FIELD_TYPE, omitNorms.origin)
    }

    @Test
    fun `resolution works with no field type at all`() {
        val indexed = resolve("indexed", type = null)
        assertEquals("true", indexed.value)
        assertEquals(SolrPropertyOrigin.FIELD, indexed.origin)
        assertEquals(SolrPropertyOrigin.SOLR_DEFAULT, resolve("required", type = null).origin)
    }

    @Test
    fun `every property the guide documents is present and described`() {
        for (expected in listOf(
            "indexed", "stored", "docValues", "multiValued", "required", "omitNorms",
            "termVectors", "sortMissingFirst", "sortMissingLast", "uninvertible",
            "useDocValuesAsStored", "large", "omitTermFreqAndPositions", "omitPositions",
        )) {
            val property = SolrFieldProperties.byName(expected)
            assertNotNull("$expected should be known", property)
            assertTrue("$expected needs a summary", property!!.summary.isNotBlank())
            assertTrue("$expected needs its accepted values", property.validValues.isNotBlank())
        }
    }

    @Test
    fun `an unknown attribute is not a known property`() {
        assertNull(SolrFieldProperties.byName("notAProperty"))
    }

    @Test
    fun `every property a field can carry resolves for a bare field`() {
        val bare = SolrField("x", "string")
        val resolved = SolrFieldProperties.effectiveFor(bare, null, SolrSchemaVersion.of("1.7"))
        assertEquals(SolrFieldProperties.FOR_FIELD.size, resolved.size)
        assertTrue(
            "a bare field takes everything from defaults or leaves it undetermined",
            resolved.all {
                it.origin == SolrPropertyOrigin.SOLR_DEFAULT ||
                    it.origin == SolrPropertyOrigin.SCHEMA_VERSION_DEFAULT ||
                    it.origin == SolrPropertyOrigin.UNDETERMINED
            },
        )
    }

    // --- defaults that follow the schema version ---------------------------------------------

    /**
     * The case that prompted all of this. Solr 9.7 flipped `uninvertible` at schema version 1.7, and
     * left every earlier schema alone — so the same field in the same Solr answers differently
     * depending on one attribute on the root element.
     */
    @Test
    fun `uninvertible defaults true below schema version 1_7 and false from it`() {
        val below = resolve("uninvertible", version = SolrSchemaVersion.of("1.6"))
        assertEquals("true", below.value)
        assertEquals(SolrPropertyOrigin.SCHEMA_VERSION_DEFAULT, below.origin)

        val from = resolve("uninvertible", version = SolrSchemaVersion.of("1.7"))
        assertEquals("false", from.value)
        assertEquals(SolrPropertyOrigin.SCHEMA_VERSION_DEFAULT, from.origin)
    }

    @Test
    fun `useDocValuesAsStored defaults false below schema version 1_6 and true from it`() {
        assertEquals("false", resolve("useDocValuesAsStored", version = SolrSchemaVersion.of("1.5")).value)
        assertEquals("true", resolve("useDocValuesAsStored", version = SolrSchemaVersion.of("1.6")).value)
    }

    /** Below 1.1 every field was multi-valued, which is what a schema with no `version` declares. */
    @Test
    fun `multiValued follows the schema version too`() {
        assertEquals("true", resolve("multiValued", version = SolrSchemaVersion.of("1.0")).value)
        assertEquals("false", resolve("multiValued", version = SolrSchemaVersion.of("1.7")).value)
        assertEquals("true", resolve("multiValued", version = SolrSchemaVersion.of(null)).value)
    }

    /**
     * Solr turned this *off* at schema version 1.4, so the pre-1.4 answer is the surprising one and
     * the one a reader of an old schema most needs. Reporting a flat `false` everywhere would have
     * described the field as querying one way while Solr queried it the other.
     */
    @Test
    fun `autoGeneratePhraseQueries defaults true below schema version 1_4 and false from it`() {
        val below = resolve("autoGeneratePhraseQueries", version = SolrSchemaVersion.of("1.3"))
        assertEquals("true", below.value)
        assertEquals(SolrPropertyOrigin.SCHEMA_VERSION_DEFAULT, below.origin)

        assertEquals("false", resolve("autoGeneratePhraseQueries", version = SolrSchemaVersion.of("1.4")).value)
    }

    /** A version rule is a *default*, so anything written in the file still outranks it. */
    @Test
    fun `a declared value still wins over a version-dependent default`() {
        val declared = field.copy(attributes = field.attributes + ("uninvertible" to "false"))
        val resolved = resolve("uninvertible", declared, version = SolrSchemaVersion.of("1.6"))
        assertEquals("false", resolved.value)
        assertEquals(SolrPropertyOrigin.FIELD, resolved.origin)

        val onType = stringType.copy(attributes = stringType.attributes + ("uninvertible" to "false"))
        val fromType = resolve("uninvertible", type = onType, version = SolrSchemaVersion.of("1.6"))
        assertEquals("false", fromType.value)
        assertEquals(SolrPropertyOrigin.FIELD_TYPE, fromType.origin)
    }

    /**
     * `docValues` needs the field type's class as well as the version — at 1.7 a `solr.StrField`
     * gains it and a `solr.TextField` does not — so the version alone must not resolve it. Asserting
     * one answer here would swap an honest silence for a new wrong statement.
     */
    @Test
    fun `docValues stays undetermined even at a version that would enable it`() {
        val bare = SolrField("x", "string")
        val docValues = resolve("docValues", bare, type = null, version = SolrSchemaVersion.of("1.7"))
        assertNull(docValues.value)
        assertEquals(SolrPropertyOrigin.UNDETERMINED, docValues.origin)
    }

    // --- defaults that follow the field type's class ------------------------------------------

    private fun resolveWithTraits(
        name: String,
        traits: Set<SolrTypeTrait>?,
        version: String = "1.7",
    ) = SolrFieldProperties.resolve(
        SolrFieldProperties.byName(name)!!,
        SolrField("x", "string"),
        null,
        SolrSchemaVersion.of(version),
        traits,
    )

    /**
     * The `omitNorms` case the KDoc has always used as its example of an unanswerable default. It
     * is answerable once the type's class is known: primitive types get it, text types do not.
     */
    @Test
    fun `omitNorms resolves from the field type's traits`() {
        val primitive = resolveWithTraits("omitNorms", setOf(SolrTypeTrait.PRIMITIVE))
        assertEquals("true", primitive.value)
        assertEquals(SolrPropertyOrigin.FIELD_TYPE_DEFAULT, primitive.origin)

        val text = resolveWithTraits("omitNorms", emptySet())
        assertEquals("false", text.value)
        assertEquals(SolrPropertyOrigin.FIELD_TYPE_DEFAULT, text.origin)
    }

    /** `PrimitiveFieldType.init` only sets it above schema version 1.4. */
    @Test
    fun `omitNorms on a primitive type still respects the schema version`() {
        assertEquals("false", resolveWithTraits("omitNorms", setOf(SolrTypeTrait.PRIMITIVE), version = "1.4").value)
        assertEquals("true", resolveWithTraits("omitNorms", setOf(SolrTypeTrait.PRIMITIVE), version = "1.5").value)
    }

    /** A spatial prefix-tree type sets it in `init` regardless of version. */
    @Test
    fun `omitNorms is on for a spatial prefix tree type at any version`() {
        assertEquals("true", resolveWithTraits("omitNorms", setOf(SolrTypeTrait.SPATIAL_PREFIX_TREE), version = "1.0").value)
    }

    /**
     * **The distinction the whole thing rests on.** No traits means the type declares no class the
     * catalog knows — a custom plugin type — and that is not the same as a known type with no
     * traits. The first must stay undetermined; the second is a definite false.
     */
    @Test
    fun `an unknown field type class leaves the default undetermined`() {
        val unknown = resolveWithTraits("omitNorms", null)
        assertNull(unknown.value)
        assertEquals(SolrPropertyOrigin.UNDETERMINED, unknown.origin)
    }

    @Test
    fun `docValues resolves from the traits and the schema version together`() {
        assertEquals("true", resolveWithTraits("docValues", setOf(SolrTypeTrait.DOC_VALUES_BY_DEFAULT)).value)
        assertEquals(
            "the general rule only applies from 1.7",
            "false",
            resolveWithTraits("docValues", setOf(SolrTypeTrait.DOC_VALUES_BY_DEFAULT), version = "1.6").value,
        )
    }

    /**
     * `SortableTextField.init` sets DOC_VALUES outside the version gate in `setArgs`, so it has doc
     * values even on a schema too old for the general rule. Applying the gate to it would report
     * false for a type that has them.
     */
    @Test
    fun `a sortable text type has doc values below the version the general rule needs`() {
        val traits = setOf(SolrTypeTrait.SORTABLE_TEXT, SolrTypeTrait.DOC_VALUES_BY_DEFAULT)
        assertEquals("true", resolveWithTraits("docValues", traits, version = "1.6").value)
    }

    @Test
    fun `a value declared in the file still wins over a type-derived default`() {
        val declared = SolrField("x", "string", attributes = mapOf("omitNorms" to "false"))
        val resolved = SolrFieldProperties.resolve(
            SolrFieldProperties.byName("omitNorms")!!,
            declared,
            null,
            SolrSchemaVersion.of("1.7"),
            setOf(SolrTypeTrait.PRIMITIVE),
        )
        assertEquals("false", resolved.value)
        assertEquals(SolrPropertyOrigin.FIELD, resolved.origin)
    }

    // --- the two scopes ---------------------------------------------------------------------

    /**
     * The six the first pass missed. They come from the Reference Guide's *general* properties
     * table, beside the field properties table the original list was built from — which left them
     * invisible to documentation and completion alike.
     */
    @Test
    fun `the fieldType general properties are modelled`() {
        for (name in listOf(
            "positionIncrementGap", "autoGeneratePhraseQueries", "synonymQueryStyle",
            "enableGraphQueries", "docValuesFormat", "postingsFormat",
        )) {
            val property = SolrFieldProperties.byName(name)
            assertNotNull("$name should be known", property)
            assertEquals("$name is only legal on a fieldType", SolrPropertyScope.TYPE_ONLY, property!!.scope)
        }
    }

    /** A field inherits its type's properties, so most are legal on both; six are not. */
    @Test
    fun `a field accepts fewer properties than a field type`() {
        assertTrue(SolrFieldProperties.FOR_FIELD.size < SolrFieldProperties.FOR_FIELD_TYPE.size)
        assertEquals(SolrFieldProperties.ALL.size, SolrFieldProperties.FOR_FIELD_TYPE.size)
        assertTrue(SolrFieldProperties.FOR_FIELD.none { it.scope == SolrPropertyScope.TYPE_ONLY })
    }

    /** A field's property table must not offer values it cannot carry. */
    @Test
    fun `type-only properties are absent from a field's effective properties`() {
        val resolved = SolrFieldProperties
            .effectiveFor(SolrField("x", "string"), null, SolrSchemaVersion.of("1.7"))
            .map { it.property.name }
        assertTrue("positionIncrementGap" !in resolved)
        assertTrue("indexed" in resolved)
    }

    /** Where the accepted set is closed and not boolean, it is recorded so completion can offer it. */
    @Test
    fun `closed non-boolean value sets are recorded`() {
        assertEquals(
            listOf("as_same_term", "pick_best", "as_distinct_terms"),
            SolrFieldProperties.byName("synonymQueryStyle")!!.closedValues,
        )
        assertTrue(
            "an integer is not a closed set",
            SolrFieldProperties.byName("positionIncrementGap")!!.closedValues.isEmpty(),
        )
        assertTrue(
            "booleans are handled separately",
            SolrFieldProperties.byName("indexed")!!.closedValues.isEmpty(),
        )
    }

    /** The four whose values are terse enough to state beside the declaration. */
    private val inlineProperties = setOf("indexed", "stored", "multiValued", "docValues")

    @Test
    fun `every boolean property legal on a field states what its value means`() {
        val missing = SolrFieldProperties.FOR_FIELD
            .filter { it.valueType == SolrValueType.BOOLEAN && it.meaning == null }
            .map { it.name }
        assertEquals("every boolean needs a meaning", emptyList<String>(), missing)
    }

    /**
     * A non-boolean has two values to state nothing about. `default` takes any value of the field's
     * type, so a true/false consequence would be inventing a distinction Solr does not make.
     */
    @Test
    fun `a property that is not a boolean carries no meaning`() {
        assertNull(SolrFieldProperties.byName("default")!!.meaning)
    }

    @Test
    fun `exactly the four storage-shape properties speak inline`() {
        val inline = SolrFieldProperties.ALL
            .filter { it.meaning?.inlineWhenTrue != null }
            .map { it.name }
            .toSet()
        assertEquals(inlineProperties, inline)
    }

    /**
     * The declarative renderer truncates any single segment past 30 characters, and each phrase
     * arrives as its own segment. A phrase over budget renders as "no doc value…".
     */
    @Test
    fun `no inline phrase exceeds the renderers segment budget`() {
        val overBudget = SolrFieldProperties.ALL
            .mapNotNull { it.meaning }
            .flatMap { listOfNotNull(it.inlineWhenTrue, it.inlineWhenFalse) }
            .filter { it.length > 30 }
        assertEquals(emptyList<String>(), overBudget)
    }

    /**
     * A half-populated pair renders a hint that states one value and says nothing for the other,
     * which reads as a missing fact rather than as a deliberate silence.
     */
    @Test
    fun `an inline phrase for one value implies one for the other`() {
        val asymmetric = SolrFieldProperties.ALL
            .filter { (it.meaning?.inlineWhenTrue == null) != (it.meaning?.inlineWhenFalse == null) }
            .map { it.name }
        assertEquals(emptyList<String>(), asymmetric)
    }

    /** Sentences are prose for a reader; a bare attribute name is a restatement, not an explanation. */
    @Test
    fun `every sentence is a sentence`() {
        val malformed = SolrFieldProperties.ALL
            .mapNotNull { p -> p.meaning?.let { p.name to it } }
            .filter { (_, m) -> !m.whenTrue.endsWith(".") || !m.whenFalse.endsWith(".") }
            .map { it.first }
        assertEquals(emptyList<String>(), malformed)
    }
}
