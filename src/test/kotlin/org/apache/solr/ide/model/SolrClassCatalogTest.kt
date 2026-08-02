package org.apache.solr.ide.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    /**
     * The current generator marks an attribute required with a trailing `!` or gives it a literal
     * default after `=`. The two markers are mutually exclusive, and a bare `name:type` carries
     * neither.
     */
    @Test
    fun `a required marker and a literal default parse from the entry`() {
        val attributes = SolrClassCatalog.parse(
            sequenceOf("tokenFilter\torg.example.F\tsolr.F\tminGramSize:int!,generateWordParts:int=1,words:free"),
        ).single().attributes.associateBy { it.name }

        assertTrue("minGramSize is required", attributes.getValue("minGramSize").required)
        assertNull("a required attribute carries no default", attributes.getValue("minGramSize").defaultValue)

        assertEquals("1", attributes.getValue("generateWordParts").defaultValue)
        assertFalse("a default is not a requirement", attributes.getValue("generateWordParts").required)

        assertNull("a bare attribute has no default", attributes.getValue("words").defaultValue)
        assertFalse("a bare attribute is not required", attributes.getValue("words").required)
    }

    /** A string default keeps its text, and the type still parses from the token before the `=`. */
    @Test
    fun `a string default keeps its text`() {
        val attribute = SolrClassCatalog.parse(
            sequenceOf("tokenizer\torg.example.T\tsolr.T\tlanguage:free=English"),
        ).single().attributes.single()
        assertEquals("English", attribute.defaultValue)
        assertEquals(SolrValueType.FREE, attribute.valueType)
    }

    /** An older catalog, with only name and type, reads as neither required nor defaulted. */
    @Test
    fun `an entry without markers has no default and is not required`() {
        val attribute = SolrClassCatalog.parse(
            sequenceOf("tokenFilter\torg.example.F\tsolr.F\tignoreCase:bool"),
        ).single().attributes.single()
        assertNull(attribute.defaultValue)
        assertFalse(attribute.required)
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

    // --- attribute defaults and requiredness ------------------------------------------------------
    //
    // The three behaviours the constructor-bytecode pass has to get right, each named by the factory
    // that proves it: taking a literal default, reading `require*` as required, and declining a
    // computed default rather than guessing it. Named, never counted, for the same reason the types
    // are.

    /**
     * A literal default beside the name is taken. `WordDelimiterGraphFilterFactory` reads
     * `generateWordParts` with `getInt(args, GENERATE_WORD_PARTS, 1)`, and javac inlines the named
     * constant to the literal `1` in the argument slot.
     */
    @Test
    fun `a literal default is recorded`() {
        val generateWordParts = attribute("solr.WordDelimiterGraphFilterFactory", "generateWordParts")
        assertEquals("1", generateWordParts.defaultValue)
        assertFalse("a default is not a requirement", generateWordParts.required)
    }

    /**
     * `requireInt` marks an attribute required and takes no default. `EdgeNGramFilterFactory` reads
     * both grammar sizes that way, so each is required with nothing to fall back to.
     */
    @Test
    fun `a required attribute is marked required and carries no default`() {
        for (name in listOf("minGramSize", "maxGramSize")) {
            val required = attribute("solr.EdgeNGramFilterFactory", name)
            assertTrue("$name is required", required.required)
            assertNull("$name carries no default", required.defaultValue)
        }
    }

    /**
     * A default computed at runtime is recorded as absent rather than guessed.
     * `JapaneseTokenizerFactory` reads `mode` with `get(args, MODE, DEFAULT_MODE.toString())`, whose
     * default is a method result, not a literal — so `mode` carries neither a default nor a
     * requirement, the same reason match analysis carries a `confident` flag.
     */
    @Test
    fun `a computed default is declined rather than guessed`() {
        val mode = attribute("solr.JapaneseTokenizerFactory", "mode")
        assertNull("mode's default is DEFAULT_MODE.toString(), not a literal", mode.defaultValue)
        assertFalse("mode is optional", mode.required)
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

    // --- the documentation column -------------------------------------------------------------

    /**
     * The class this generator's `-sources` pass proves works: `EdgeNGramFilterFactory`'s own
     * class comment is a single short sentence, exactly the shape the summary extraction targets.
     */
    @Test
    fun `a factory with a class comment carries a documentation summary`() {
        val entry = SolrClassCatalog.find("solr.EdgeNGramFilterFactory", latest)
        assertNotNull(entry)
        assertEquals("Creates new instances of EdgeNGramTokenFilter.", entry!!.summary)
    }

    /**
     * `StrField` carries no class-level Javadoc comment at all in Solr's own sources — only a
     * plain block comment above it, the ASF license header, which a Javadoc comment's extra
     * leading star tells apart. A summary is absent here for the same reason a computed default
     * is absent elsewhere: there is nothing to report, so nothing is guessed.
     */
    @Test
    fun `a class with no Javadoc comment carries no summary`() {
        val entry = SolrClassCatalog.find("solr.StrField", latest)
        assertNotNull(entry)
        assertNull(entry!!.summary)
    }

    /** A row with no fifth column at all — an older catalog — reads as no summary, not a blank one. */
    @Test
    fun `a row without a documentation column has no summary`() {
        val entry = SolrClassCatalog.parse(
            sequenceOf("tokenFilter\torg.example.F\tsolr.F\tminGramSize:int"),
        ).single()
        assertNull(entry.summary)
    }

    /** A trailing empty documentation column is no summary, the same rule the attributes follow. */
    @Test
    fun `a blank documentation column has no summary`() {
        val entry = SolrClassCatalog.parse(
            sequenceOf("tokenFilter\torg.example.F\tsolr.F\tminGramSize:int\t"),
        ).single()
        assertNull(entry.summary)
    }

    /** A generated summary reads through, verbatim. */
    @Test
    fun `a documentation column parses into the summary`() {
        val entry = SolrClassCatalog.parse(
            sequenceOf("tokenizer\torg.example.T\tsolr.T\t\tCreates new instances of T."),
        ).single()
        assertEquals("Creates new instances of T.", entry.summary)
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
