package org.apache.solr.ide.configset.schema.annotator

import com.intellij.openapi.editor.colors.CodeInsightColors
import org.apache.solr.ide.configset.activation.SolrConfigsetTestCase

/**
 * Dimming an attribute whose value the field would have had anyway.
 *
 * **Every file here is correct, which inverts what the tests have to prove.** An inspection is
 * dangerous when it fires on a clean file; this is dangerous when it dims an attribute whose removal
 * would change the field, because the reader is being invited to delete it. So the negative cases —
 * a value that decides something, a default the catalog cannot determine — carry the weight, and
 * the whole feature must stay out of the Problems view.
 */
class SolrRestatedDefaultAnnotatorTest : SolrConfigsetTestCase() {

    private fun schema(body: String) = """
        <schema name="t" version="1.7">
          <fieldType name="string" class="solr.StrField"/>
          <fieldType name="text" class="solr.TextField"/>
          $body
        </schema>
    """.trimIndent()

    /**
     * The attributes rendered as removable, as the file spells them.
     *
     * Keyed on the text attributes rather than on severity: an XML file is full of information-level
     * highlights that belong to syntax colouring, and a filter that caught those would pass whatever
     * this annotator did.
     */
    private fun dimmed(body: String): List<String> {
        myFixture.configureByText("managed-schema.xml", schema(body))
        return myFixture.doHighlighting()
            .filter { it.forcedTextAttributesKey == CodeInsightColors.NOT_USED_ELEMENT_ATTRIBUTES }
            .map { myFixture.file.text.substring(it.startOffset, it.endOffset) }
    }

    fun testAnAttributeRepeatingSolrsDefaultIsDimmed() {
        val dimmed = dimmed("""<field name="sku" type="string" indexed="true"/>""")
        assertEquals(listOf("""indexed="true""""), dimmed)
    }

    fun testAnAttributeThatDecidesSomethingIsNotDimmed() {
        assertEquals(emptyList<String>(), dimmed("""<field name="sku" type="string" indexed="false"/>"""))
    }

    /** The whole attribute, because removing the name and leaving the value is not a thing. */
    fun testTheDimCoversTheWholeAttribute() {
        val dimmed = dimmed("""<field name="sku" type="string" stored="true"/>""")
        assertEquals(listOf("""stored="true""""), dimmed)
    }

    /**
     * A value the field inherits from its type rather than from Solr, which is removable for a
     * different reason and reads identically to the reader.
     */
    fun testAnAttributeRepeatingItsFieldTypeIsDimmed() {
        val dimmed = dimmed(
            """
            <fieldType name="tuned" class="solr.StrField" omitNorms="false"/>
            <field name="sku" type="tuned" omitNorms="false"/>
            """.trimIndent(),
        )
        assertEquals(listOf("""omitNorms="false""""), dimmed)
    }

    /** `name` and `type` are not properties at all, and nothing may offer to remove them. */
    fun testStructuralAttributesAreNeverDimmed() {
        assertEquals(emptyList<String>(), dimmed("""<field name="sku" type="string"/>"""))
    }

    /**
     * A dynamic field resolves exactly as a concrete one does, because it names a type the same way.
     *
     * The pattern it declares is what makes a dynamic field different, and none of the properties
     * here are about the pattern — `indexed` means the same thing for the fields it will match as it
     * does for a field written out.
     */
    fun testADynamicFieldAttributeRepeatingSolrsDefaultIsDimmed() {
        val dimmed = dimmed("""<dynamicField name="*_s" type="string" indexed="true"/>""")
        assertEquals(listOf("""indexed="true""""), dimmed)
    }

    /**
     * The same resolution, but through the type rather than from Solr's own default.
     *
     * Every other dynamic-field case here repeats a value Solr defaults to, which a resolution that
     * never consulted `type` at all would still answer correctly. This one can only come out right
     * by reading the `<fieldType>`: `omitNorms="false"` is not Solr's default for a string field, so
     * the attribute is removable solely because the type already said so.
     */
    fun testADynamicFieldAttributeRepeatingItsTypesValueIsDimmed() {
        val dimmed = dimmed(
            """
            <fieldType name="tuned" class="solr.StrField" omitNorms="false"/>
            <dynamicField name="*_t" type="tuned" omitNorms="false"/>
            """.trimIndent(),
        )
        assertEquals(listOf("""omitNorms="false""""), dimmed)
    }

    fun testADynamicFieldAttributeThatDecidesSomethingIsNotDimmed() {
        assertEquals(
            emptyList<String>(),
            dimmed("""<dynamicField name="*_s" type="string" indexed="false"/>"""),
        )
    }

    /** The pattern is the declaration, and nothing may offer to remove it. */
    fun testADynamicFieldsPatternIsNeverDimmed() {
        assertEquals(emptyList<String>(), dimmed("""<dynamicField name="*_s" type="string"/>"""))
    }

    // --- field types -----------------------------------------------------------------------------
    //
    // A `<fieldType>` resolves with nothing above it, so its attributes answer to Solr's own
    // defaults and to its class's traits directly. The same question, one layer shorter.

    fun testAFieldTypeAttributeRepeatingSolrsDefaultIsDimmed() {
        val dimmed = dimmed("""<fieldType name="tuned" class="solr.StrField" indexed="true"/>""")
        assertEquals(listOf("""indexed="true""""), dimmed)
    }

    fun testAFieldTypeAttributeThatDecidesSomethingIsNotDimmed() {
        assertEquals(
            emptyList<String>(),
            dimmed("""<fieldType name="tuned" class="solr.StrField" indexed="false"/>"""),
        )
    }

    /** Solr accepts both spellings and real configsets use both. */
    fun testTheLowercaseFieldTypeSpellingIsReadToo() {
        val dimmed = dimmed("""<fieldtype name="tuned" class="solr.StrField" indexed="true"/>""")
        assertEquals(listOf("""indexed="true""""), dimmed)
    }

    /**
     * A type naming a class the catalog does not carry keeps every type-dependent attribute.
     *
     * The custom plugin case, which is ordinary rather than exotic. `omitNorms` depends on the
     * class's ancestry, and with no catalog entry there is nothing to compare against — dimming here
     * would invite a deletion that changes the index on the strength of a guess.
     */
    fun testAFieldTypeWhoseClassIsUnknownIsNotDimmed() {
        assertEquals(
            emptyList<String>(),
            dimmed("""<fieldType name="custom" class="com.example.MyType" omitNorms="true"/>"""),
        )
    }

    /**
     * A property legal only on a type is not judged on a field.
     *
     * `enableGraphQueries` defaults to true, so comparing it against that default on a `<field>`
     * would dim it — for the wrong reason. Solr ignores it there entirely, which is a different
     * thing to tell the reader and not this feature's to tell.
     */
    fun testATypeOnlyPropertyIsNotDimmedOnAField() {
        assertEquals(
            emptyList<String>(),
            dimmed("""<field name="sku" type="string" enableGraphQueries="true"/>"""),
        )
    }

    /** The same property on the element it belongs to does dim. */
    fun testATypeOnlyPropertyIsDimmedOnAFieldType() {
        val dimmed = dimmed("""<fieldType name="tuned" class="solr.TextField" enableGraphQueries="true"/>""")
        assertEquals(listOf("""enableGraphQueries="true""""), dimmed)
    }

    // --- analysis factories ----------------------------------------------------------------------
    //
    // A `<filter>`, `<tokenizer>` or `<charFilter>` inherits nothing — no chain-wide default, no
    // outer element to fall through to — so the only thing that can make one of its attributes
    // removable is the literal default the catalog read out of that factory's own constructor.
    // Which makes the negative cases the whole of the work: the catalog records a default only
    // where the bytecode proved one, and every other answer has to be silence.

    /** A chain in the only place Solr allows one, so the components sit where the parser reads them. */
    private fun analyzed(components: String) = """
        <fieldType name="chain" class="solr.TextField">
          <analyzer>
            $components
          </analyzer>
        </fieldType>
    """.trimIndent()

    /**
     * The default read out of the factory, and the attribute beside it that has none.
     *
     * Both are asserted in one fixture on purpose. `ignoreCase` and `words` are written identically
     * and are two different questions: the first has a default the bytecode proved (`false`), the
     * second is read with no fallback at all, so a comparison that treated "absent" as "matches"
     * would dim a stop-word file that the filter genuinely needs.
     */
    fun testAFactoryAttributeRepeatingItsRecordedDefaultIsDimmed() {
        val dimmed = dimmed(
            analyzed("""<filter class="solr.StopFilterFactory" ignoreCase="false" words="stopwords.txt"/>"""),
        )
        assertEquals(listOf("""ignoreCase="false""""), dimmed)
    }

    fun testAFactoryAttributeThatDecidesSomethingIsNotDimmed() {
        assertEquals(
            emptyList<String>(),
            dimmed(analyzed("""<filter class="solr.StopFilterFactory" ignoreCase="true"/>""")),
        )
    }

    /**
     * A tokenizer, and a default that is neither true nor false.
     *
     * `maxTokenLength="255"` is the whole reason this reads the catalog's literal rather than a
     * boolean table: the recorded default is a number, and so is every one on the word-delimiter
     * filters beside it.
     */
    fun testATokenizerAttributeRepeatingItsRecordedDefaultIsDimmed() {
        val dimmed = dimmed(
            analyzed("""<tokenizer class="solr.StandardTokenizerFactory" maxTokenLength="255"/>"""),
        )
        assertEquals(listOf("""maxTokenLength="255""""), dimmed)
    }

    /** The third element, which reaches the same catalog through the same tag mapping. */
    fun testACharFilterAttributeRepeatingItsRecordedDefaultIsDimmed() {
        val dimmed = dimmed(
            analyzed("""<charFilter class="solr.JapaneseIterationMarkCharFilterFactory" normalizeKana="true"/>"""),
        )
        assertEquals(listOf("""normalizeKana="true""""), dimmed)
    }

    /**
     * A required attribute never dims, because a required attribute has no default to restate.
     *
     * Solr marks these by reading them with `requireInt` rather than `getInt`, which takes no
     * fallback — so the catalog carries the requirement and no value, and there is nothing to
     * compare `minGramSize="1"` against however ordinary that number looks.
     */
    fun testARequiredFactoryAttributeIsNeverDimmed() {
        assertEquals(
            emptyList<String>(),
            dimmed(analyzed("""<filter class="solr.NGramFilterFactory" minGramSize="1" maxGramSize="2"/>""")),
        )
    }

    /**
     * A factory the catalog does not carry keeps every attribute, which is the custom-plugin case.
     *
     * Ordinary rather than exotic — a site's own filter is written exactly like a stock one — and
     * the one where a wrong dim costs most, because nothing about the file tells the reader that
     * the offer to delete was made without knowing what the class does.
     */
    fun testAFactoryClassTheCatalogDoesNotKnowIsNotDimmed() {
        assertEquals(
            emptyList<String>(),
            dimmed(analyzed("""<filter class="com.example.MyFilter" ignoreCase="false"/>""")),
        )
    }

    /**
     * The class is matched by kind as well as by name, as completion and the hover already match it.
     *
     * `maxTokenLength` is a tokenizer's attribute with a recorded default of `255`. Written on a
     * `<filter>` it names no filter Solr has, so the value restates nothing — a catalog lookup that
     * ignored the kind would find the tokenizer's entry and offer to delete it.
     */
    fun testATokenizersAttributeOnAFilterIsNotDimmed() {
        assertEquals(
            emptyList<String>(),
            dimmed(analyzed("""<filter class="solr.StandardTokenizerFactory" maxTokenLength="255"/>""")),
        )
    }

    /** An attribute the factory does not read at all, which the catalog answers for by omission. */
    fun testAnAttributeTheFactoryDoesNotReadIsNotDimmed() {
        assertEquals(
            emptyList<String>(),
            dimmed(analyzed("""<filter class="solr.StopFilterFactory" maxTokenLength="255"/>""")),
        )
    }

    /** `class` is what makes the lookup possible, and nothing may offer to remove it. */
    fun testAFactorysClassAttributeIsNeverDimmed() {
        assertEquals(
            emptyList<String>(),
            dimmed(analyzed("""<filter class="solr.LowerCaseFilterFactory"/>""")),
        )
    }

    /**
     * Nothing this adds reaches the Problems view, which is the criterion that keeps it honest.
     *
     * A restated default is *correct*, and the standing rule is that inspections do not fire on
     * correct files. Every category is checked, weak warnings included, because an information-level
     * annotation that had acquired a message would show up as one.
     */
    fun testNothingIsReportedOnACorrectFile() {
        myFixture.configureByText(
            "managed-schema.xml",
            schema("""<field name="sku" type="string" indexed="true" stored="false"/>"""),
        )
        myFixture.checkHighlighting(true, false, true)
    }

    /** The same guarantee for the factory half, which dims inside a chain a real configset would ship. */
    fun testNothingIsReportedOnACorrectAnalyzerChain() {
        myFixture.configureByText(
            "managed-schema.xml",
            schema(
                analyzed(
                    """
                    <tokenizer class="solr.StandardTokenizerFactory" maxTokenLength="255"/>
                        <filter class="solr.LowerCaseFilterFactory"/>
                    """.trimIndent(),
                ),
            ),
        )
        myFixture.checkHighlighting(true, false, true)
    }
}
