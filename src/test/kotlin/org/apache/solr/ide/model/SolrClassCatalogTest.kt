package org.apache.solr.ide.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The generated catalog, as the plugin reads it.
 *
 * These assert against the shipped resource rather than a fixture, so they fail when the generator
 * regresses. That is the point: a generator's failure mode is a plausible short list, not an error,
 * and only a named expectation catches it.
 */
class SolrClassCatalogTest {

    private val latest = SolrVersionSelection.DEFAULT
    private val solr9 = SolrVersionSelection.fromLuceneMatchVersion("9.12.0")

    @Test
    fun `every supported line ships a catalog`() {
        for (line in SolrClassCatalog.SUPPORTED_LINES) {
            val entries = SolrClassCatalog.entriesFor(
                SolrVersionSelection.fromLuceneMatchVersion("$line.0.0"),
            )
            assertTrue("line $line ships no catalog", entries.size > 100)
        }
    }

    @Test
    fun `every kind is populated`() {
        for (kind in SolrClassKind.entries) {
            assertTrue("$kind is empty", SolrClassCatalog.of(kind, latest).isNotEmpty())
        }
    }

    /**
     * The entry an earlier generator missed.
     *
     * `StandardTokenizerFactory` lives in `lucene-core`, not `lucene-analysis-common`, so a scan
     * limited to the two obvious artifacts produced a list that looked right and omitted the most
     * used tokenizer there is. Naming it here is what turns that from a silent gap into a failure.
     */
    @Test
    fun `the standard tokenizer is present`() {
        val tokenizers = SolrClassCatalog.of(SolrClassKind.TOKENIZER, latest).map { it.shortName }
        assertTrue("expected solr.StandardTokenizerFactory in $tokenizers", "solr.StandardTokenizerFactory" in tokenizers)
    }

    /** The classes a schema names most, one from each population. */
    @Test
    fun `the everyday classes are present`() {
        for (name in listOf("solr.StrField", "solr.TextField", "solr.LowerCaseFilterFactory")) {
            assertNotNull("expected $name", SolrClassCatalog.find(name, latest))
        }
    }

    /** A configset may write either spelling, and they name the same class. */
    @Test
    fun `a class resolves by either spelling`() {
        val short = SolrClassCatalog.find("solr.StrField", latest)
        val qualified = SolrClassCatalog.find("org.apache.solr.schema.StrField", latest)
        assertEquals(short, qualified)
    }

    @Test
    fun `an unknown class resolves to nothing`() {
        assertNull(SolrClassCatalog.find("solr.NoSuchField", latest))
    }

    /** Every kind is filed under the tag it belongs to; a field type is not a tokenizer. */
    @Test
    fun `kinds do not overlap`() {
        val fieldTypes = SolrClassCatalog.of(SolrClassKind.FIELD_TYPE, latest).map { it.shortName }.toSet()
        val tokenizers = SolrClassCatalog.of(SolrClassKind.TOKENIZER, latest).map { it.shortName }.toSet()
        assertTrue("a class cannot be both", (fieldTypes intersect tokenizers).isEmpty())
    }

    /**
     * The lines genuinely differ, which is the whole reason this is generated per line rather than
     * once. Solr 10 removed four field types that 9.10 has.
     */
    @Test
    fun `the lines are not the same catalog`() {
        val nine = SolrClassCatalog.of(SolrClassKind.FIELD_TYPE, solr9).map { it.shortName }.toSet()
        val ten = SolrClassCatalog.of(SolrClassKind.FIELD_TYPE, latest).map { it.shortName }.toSet()
        assertTrue("expected solr.EnumField in 9.10", "solr.EnumField" in nine)
        assertTrue("expected solr.EnumField gone from 10", "solr.EnumField" !in ten)
    }

    /**
     * A version this plugin does not support falls back to the newest line rather than to nothing.
     * Someone reading a Solr 7 configset is better served by the current vocabulary than by silence.
     */
    @Test
    fun `an unsupported line falls back to the newest`() {
        val ancient = SolrClassCatalog.entriesFor(SolrVersionSelection.fromLuceneMatchVersion("7.0.0"))
        assertEquals(SolrClassCatalog.entriesFor(latest), ancient)
    }

    // --- the parser, against input the generator would never produce ------------------------------

    /**
     * A malformed row must cost its own line, not the file. This parser is the one place a
     * half-written or truncated catalog would reach the editor.
     */
    @Test
    fun `a malformed row is dropped and the rest survive`() {
        val entries = SolrClassCatalog.parse(
            sequenceOf(
                "# a comment",
                "",
                "   ",
                "fieldType\torg.apache.solr.schema.StrField\tsolr.StrField\t",
                "truncated\trow",
                "notAKind\torg.example.Thing\tsolr.Thing\t",
                "tokenizer\torg.example.T\tsolr.T\talpha,beta",
            ),
        )
        assertEquals(2, entries.size)
        assertEquals(SolrClassKind.FIELD_TYPE, entries[0].kind)
        assertTrue("a trailing empty column is no attributes", entries[0].attributes.isEmpty())
        assertEquals(listOf("alpha", "beta"), entries[1].attributes.map { it.name })
        // An untyped row is what a catalog generated before types existed looks like. It has to read
        // as "any value is legal" rather than throwing, so a stale resource degrades to no value
        // checking instead of taking the editor down.
        assertEquals(
            listOf(SolrValueType.FREE, SolrValueType.FREE),
            entries[1].attributes.map { it.valueType },
        )
    }

    /** A row with no attribute column at all is still an entry; older catalogs had three columns. */
    @Test
    fun `a row without an attribute column parses`() {
        val entries = SolrClassCatalog.parse(sequenceOf("charFilter\torg.example.C\tsolr.C"))
        assertEquals(1, entries.size)
        assertTrue(entries.single().attributes.isEmpty())
    }

    /** Empty items between separators are not attribute names. */
    @Test
    fun `blank attributes are discarded`() {
        val entries = SolrClassCatalog.parse(sequenceOf("tokenFilter\torg.example.F\tsolr.F\t,alpha,,beta,"))
        assertEquals(listOf("alpha", "beta"), entries.single().attributes.map { it.name })
    }

    /** A row written by the current generator carries a type on every attribute. */
    @Test
    fun `a typed row parses its types`() {
        val entries = SolrClassCatalog.parse(
            sequenceOf("tokenFilter\torg.example.F\tsolr.F\tminGramSize:int,ignoreCase:bool,words:free"),
        )
        assertEquals(
            listOf(SolrValueType.INTEGER, SolrValueType.BOOLEAN, SolrValueType.FREE),
            entries.single().attributes.map { it.valueType },
        )
    }

    /** A token this version does not know reads as free rather than failing the row. */
    @Test
    fun `an unrecognized type token does not lose the attribute`() {
        val entries = SolrClassCatalog.parse(
            sequenceOf("tokenFilter\torg.example.F\tsolr.F\tsomething:futureType"),
        )
        val attribute = entries.single().attributes.single()
        assertEquals("something", attribute.name)
        assertEquals(SolrValueType.FREE, attribute.valueType)
    }

    /** Every entry carries both spellings, and the short one is the `solr.` form. */
    @Test
    fun `entries are well formed`() {
        for (entry in SolrClassCatalog.entriesFor(latest)) {
            assertTrue("${entry.className} is not qualified", '.' in entry.className)
            assertTrue("${entry.shortName} is not a solr. name", entry.shortName.startsWith("solr."))
            assertEquals(entry.className.substringAfterLast('.'), entry.shortName.removePrefix("solr."))
        }
    }

    // --- attribute value types --------------------------------------------------------------------
    //
    // Named, never counted. A bytecode pass does not fail; it produces a plausible short list, and a
    // count both passes for the wrong reason and moves with the Solr line.

    private fun attribute(shortName: String, attribute: String): SolrClassAttribute {
        val entry = SolrClassCatalog.find(shortName, latest)
        assertNotNull("$shortName is missing from the catalog", entry)
        val found = entry!!.attribute(attribute)
        assertNotNull("$shortName does not expose $attribute", found)
        return found!!
    }

    @Test
    fun `an integer attribute is typed from its reader's return type`() {
        assertEquals(SolrValueType.INTEGER, attribute("solr.EdgeNGramFilterFactory", "minGramSize").valueType)
        assertEquals(SolrValueType.INTEGER, attribute("solr.EdgeNGramFilterFactory", "maxGramSize").valueType)
    }

    @Test
    fun `a boolean attribute is typed from its reader's return type`() {
        assertEquals(
            SolrValueType.BOOLEAN,
            attribute("solr.EdgeNGramFilterFactory", "preserveOriginal").valueType,
        )
        assertEquals(SolrValueType.BOOLEAN, attribute("solr.StopFilterFactory", "ignoreCase").valueType)
    }

    /**
     * A resource filename is free-form, and typing it would be a confident wrong answer.
     *
     * `words` is read with a plain `get`, so the descriptor carries no type. This is the case that
     * proves `free` is a positive statement rather than a fallback for everything unrecognized.
     */
    @Test
    fun `a filename attribute stays free`() {
        assertEquals(SolrValueType.FREE, attribute("solr.StopFilterFactory", "words").valueType)
        assertEquals(SolrValueType.FREE, attribute("solr.JapaneseTokenizerFactory", "userDictionary").valueType)
    }

    /**
     * Solr reads these as `0`/`1` flags with `getInt`, so `generateWordParts="true"` really does
     * fail in Solr. The catalog records what Solr does, not what the name suggests — which is only
     * knowable from the return descriptor.
     */
    @Test
    fun `word delimiter flags are integers because Solr reads them as integers`() {
        assertEquals(
            SolrValueType.INTEGER,
            attribute("solr.WordDelimiterGraphFilterFactory", "generateWordParts").valueType,
        )
        assertEquals(
            SolrValueType.INTEGER,
            attribute("solr.WordDelimiterGraphFilterFactory", "catenateAll").valueType,
        )
    }

    /**
     * The regression that the name-matched reader list caused, and the reason it was replaced.
     *
     * `ConcatenateGraphFilterFactory` reads `tokenSeparator` with `getCharacter`, which the old
     * seventeen-name list did not contain. An unmatched reader neither harvested its own attribute
     * nor cleared the pending literal, so the *next* read harvested `tokenSeparator` instead of its
     * own name and `preservePositionIncrements` vanished from the catalog entirely. Matching on the
     * descriptor cannot miss a reader this way.
     */
    @Test
    fun `an attribute is not lost when its neighbour uses an unusual reader`() {
        assertEquals(
            SolrValueType.FREE,
            attribute("solr.ConcatenateGraphFilterFactory", "tokenSeparator").valueType,
        )
        assertEquals(
            SolrValueType.BOOLEAN,
            attribute("solr.ConcatenateGraphFilterFactory", "preservePositionIncrements").valueType,
        )
        assertEquals(
            SolrValueType.INTEGER,
            attribute("solr.ConcatenateGraphFilterFactory", "maxGraphExpansions").valueType,
        )
    }

    /** Every analysis class inherits this from the root of the hierarchy. */
    @Test
    fun `an inherited attribute is collected from the superclass chain`() {
        assertEquals(
            SolrValueType.FREE,
            attribute("solr.EdgeNGramFilterFactory", "luceneMatchVersion").valueType,
        )
    }

    /**
     * `class` and `name` identify the component; they are not parameters it accepts.
     *
     * The generator strips them because the base class consumes them with `args.remove`, which reads
     * identically to a real attribute. Anything checking attribute names has to allow them
     * structurally, and this records that the catalog will not do it for you.
     */
    @Test
    fun `the catalog never lists class or name as an attribute`() {
        for (entry in SolrClassCatalog.entriesFor(latest)) {
            for (attribute in entry.attributes) {
                assertTrue(
                    "${entry.shortName} lists ${attribute.name} as an attribute",
                    attribute.name != "class" && attribute.name != "name",
                )
            }
        }
    }

    // --- field type attributes --------------------------------------------------------------------

    /**
     * A field type's own attributes, which the `endsWith("Factory")` guard used to exclude entirely.
     *
     * These are `free` and stay that way. A field type calls `args.get("defaultCurrency")` on the
     * plain map, whose signature is erased to `(Object)Object`, so no type is recoverable. Knowing
     * the attribute is *legal* is the whole gain; judging its value was never on offer.
     */
    @Test
    fun `a field type exposes its own attributes`() {
        assertEquals(SolrValueType.FREE, attribute("solr.CurrencyFieldType", "defaultCurrency").valueType)
        assertEquals(SolrValueType.FREE, attribute("solr.DenseVectorField", "vectorDimension").valueType)
        assertEquals(SolrValueType.FREE, attribute("solr.DenseVectorField", "similarityFunction").valueType)
        assertEquals(SolrValueType.FREE, attribute("solr.PointType", "subFieldSuffix").valueType)
        assertEquals(SolrValueType.FREE, attribute("solr.SpatialRecursivePrefixTreeFieldType", "distErrPct").valueType)
    }

    /**
     * Read in `EnumFieldType$EnumMapping`, not in the field type itself.
     *
     * A class may hand the argument map to a nested helper, and before the enclosing class collected
     * its nested classes the catalog said `EnumFieldType` accepted nothing at all.
     */
    @Test
    fun `an attribute read by a nested helper reaches its enclosing class`() {
        assertNotNull(SolrClassCatalog.find("solr.EnumFieldType", latest)?.attribute("enumsConfig"))
        assertNotNull(SolrClassCatalog.find("solr.EnumFieldType", latest)?.attribute("enumName"))
    }

    /**
     * Read in `setup(ResourceLoader, Map)`, not in `init`.
     *
     * Naming the methods a field type may read in is the same losing game as naming the readers, so
     * every method of a schema class is visited.
     */
    @Test
    fun `an attribute read outside init is still found`() {
        assertNotNull(SolrClassCatalog.find("solr.CollationField", latest)?.attribute("language"))
        assertNotNull(SolrClassCatalog.find("solr.CollationField", latest)?.attribute("strength"))
    }

    /** Every field type answers with something, so none of them looks accidentally attribute-free. */
    @Test
    fun `every field type carries attributes`() {
        val bare = SolrClassCatalog.of(SolrClassKind.FIELD_TYPE, latest).filter { it.attributes.isEmpty() }
        assertTrue("field types with no attributes: ${bare.map { it.shortName }}", bare.isEmpty())
    }

    /**
     * The limit of what bytecode can reach, recorded so it is a known boundary rather than a bug.
     *
     * `currencyConfig` is a real attribute of `<fieldType class="solr.CurrencyFieldType">` — Solr's
     * own `sample_techproducts_configs` writes it — but it is read by `FileExchangeRateProvider`,
     * a collaborator named at runtime through the `providerClass` attribute. It is neither a
     * superclass nor a nested class, so no walk from the field type reaches it.
     *
     * **This is why an unknown-attribute warning cannot be raised on a `<fieldType>`.** A field type
     * delegates to classes chosen by its own configuration, so its attribute list is open by
     * construction, and treating it as complete would underline a configset Solr ships.
     */
    @Test
    fun `a delegated attribute is out of reach, which bounds what may be flagged`() {
        val currency = SolrClassCatalog.find("solr.CurrencyFieldType", latest)
        assertNotNull(currency)
        assertNotNull("providerClass is what makes the vocabulary open", currency!!.attribute("providerClass"))
        assertNull(
            "if this ever resolves, the field type vocabulary may have become closed",
            currency.attribute("currencyConfig"),
        )
    }

    /** A generated catalog cannot produce `enum`: a closed member set is not in a return descriptor. */
    @Test
    fun `no generated attribute is an enum`() {
        for (entry in SolrClassCatalog.entriesFor(latest)) {
            for (attribute in entry.attributes) {
                assertTrue(
                    "${entry.shortName}.${attribute.name} is an enum",
                    attribute.valueType != SolrValueType.ENUM,
                )
            }
        }
    }

    @Test
    fun `every schema element carrying a class maps to its kind`() {
        assertEquals(SolrClassKind.FIELD_TYPE, SolrClassKind.forTag("fieldType"))
        assertEquals(SolrClassKind.FIELD_TYPE, SolrClassKind.forTag("fieldtype"))
        assertEquals(SolrClassKind.TOKENIZER, SolrClassKind.forTag("tokenizer"))
        assertEquals(SolrClassKind.TOKEN_FILTER, SolrClassKind.forTag("filter"))
        assertEquals(SolrClassKind.CHAR_FILTER, SolrClassKind.forTag("charFilter"))
    }

    @Test
    fun `an element that carries no class maps to nothing`() {
        assertNull(SolrClassKind.forTag("copyField"))
        assertNull(SolrClassKind.forTag("analyzer"))
        assertNull(SolrClassKind.forTag("field"))
    }
}
