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
}
