package org.apache.solr.ide.configset.schema.intention

import org.apache.solr.ide.configset.activation.SolrConfigsetTestCase
import org.apache.solr.ide.configset.reading.SolrConfigsetReader
import org.apache.solr.ide.model.schema.SolrFieldProperties
import org.apache.solr.ide.model.vocabulary.SolrClassCatalog

/**
 * Removing an attribute the field would have had anyway.
 *
 * **The assertion that matters is not the resulting text.** Anyone can delete an attribute; what
 * makes the offer safe is that the field means the same afterwards, so the criterion these tests are
 * built around compares the resolved properties before and after rather than the characters. A
 * deletion that changed one of them would still produce plausible-looking XML.
 */
class SolrRemoveRestatedAttributeIntentionTest : SolrConfigsetTestCase() {

    /**
     * Deliberately not "Remove attribute", which the platform's own XML intention already answers
     * to — a suite written against that name passes with this intention absent entirely.
     */
    private val hint = "Remove restated default"

    private fun schema(body: String) = """
        <schema name="t" version="1.7">
          <fieldType name="string" class="solr.StrField"/>
          $body
        </schema>
    """.trimIndent()

    /** Every property's effective value for the named field, as the model resolves it now. */
    private fun effectiveProperties(fieldName: String): Map<String, String?> {
        val model = SolrConfigsetReader.getInstance(project).modelFor(myFixture.file)!!
        val field = model.fields.getValue(fieldName).effective
        val fieldType = model.typeOf(field)
        return SolrFieldProperties
            .effectiveFor(field, fieldType, model.schemaVersion, model.traitsOf(fieldType))
            .associate { it.property.name to it.value }
    }

    fun testTheRestatedAttributeIsRemoved() {
        myFixture.configureByText(
            "managed-schema.xml",
            schema("""<field name="sku" type="string" ind<caret>exed="true" stored="false"/>"""),
        )
        myFixture.launchAction(myFixture.filterAvailableIntentions(hint).single())
        assertFalse("""the attribute should be gone: ${myFixture.file.text}""", "indexed" in myFixture.file.text)
        assertTrue("its neighbour must survive", """stored="false"""" in myFixture.file.text)
    }

    /** The criterion the whole feature rests on. */
    fun testTheFieldMeansTheSameAfterwards() {
        myFixture.configureByText(
            "managed-schema.xml",
            schema("""<field name="sku" type="string" ind<caret>exed="true"/>"""),
        )
        val before = effectiveProperties("sku")
        myFixture.launchAction(myFixture.filterAvailableIntentions(hint).single())
        assertEquals(before, effectiveProperties("sku"))
    }

    /**
     * The offer reaches a `<fieldType>` because both surfaces read one predicate.
     *
     * **This passed the moment the annotator's field-type half landed, and asserting it anyway is
     * the point.** Nothing was written to make the intention understand field types; it understands
     * them because it does not decide for itself what counts as restated. A later change that gave
     * either surface its own copy of that judgement would break this and nothing else.
     */
    fun testTheOfferReachesAFieldTypesOwnAttribute() {
        myFixture.configureByText(
            "managed-schema.xml",
            """
            <schema name="t" version="1.7">
              <fieldType name="tuned" class="solr.StrField" ind<caret>exed="true"/>
              <field name="sku" type="tuned"/>
            </schema>
            """.trimIndent(),
        )
        // What the type exists to decide, captured through a field that uses it: removing an
        // attribute from a type is safe exactly when the fields reading it still resolve the same.
        val before = effectiveProperties("sku")
        myFixture.launchAction(myFixture.filterAvailableIntentions(hint).single())
        assertFalse("""the attribute should be gone: ${myFixture.file.text}""", "indexed" in myFixture.file.text)
        assertEquals(before, effectiveProperties("sku"))
    }

    /**
     * Every setting the factory will actually run with, resolved through the catalog's defaults.
     *
     * The analysis-chain counterpart to [effectiveProperties], and the same argument for it: what
     * makes the offer safe is not that the text still parses but that the filter is configured
     * identically, so the comparison has to fold each written value together with the default it
     * would fall back to. Reading the written attributes alone would compare two maps that differ
     * by construction after a deletion, and prove nothing at all.
     */
    private fun effectiveFactorySettings(typeName: String): Map<String, String?> {
        val model = SolrConfigsetReader.getInstance(project).modelFor(myFixture.file)!!
        val component = model.fieldTypes.getValue(typeName).effective.indexAnalyzer!!.components.single()
        val entry = SolrClassCatalog.find(component.className, model.solrVersion)!!
        return entry.attributes.associate { it.name to (component.attributes[it.name] ?: it.defaultValue) }
    }

    /**
     * The offer reaches an analysis factory, and the filter runs the same afterwards.
     *
     * The criterion the factory half rests on, stated the way the field half states it: the file
     * text is allowed to change and the resolved configuration is not.
     */
    fun testTheFilterMeansTheSameAfterwards() {
        myFixture.configureByText(
            "managed-schema.xml",
            schema(
                """
                <fieldType name="chain" class="solr.TextField">
                  <analyzer>
                    <filter class="solr.StopFilterFactory" ignore<caret>Case="false"/>
                  </analyzer>
                </fieldType>
                """.trimIndent(),
            ),
        )
        val before = effectiveFactorySettings("chain")
        myFixture.launchAction(myFixture.filterAvailableIntentions(hint).single())
        assertFalse("""the attribute should be gone: ${myFixture.file.text}""", "ignoreCase" in myFixture.file.text)
        assertEquals(before, effectiveFactorySettings("chain"))
    }

    /**
     * A factory attribute the catalog records no default for is never offered.
     *
     * `words` is read with no fallback, so deleting it would take the stop-word list away — the
     * failure this half is written to avoid, and the one that looks identical in the file to the
     * case above.
     */
    fun testNothingIsOfferedOnAFactoryAttributeWithNoRecordedDefault() {
        myFixture.configureByText(
            "managed-schema.xml",
            schema(
                """
                <fieldType name="chain" class="solr.TextField">
                  <analyzer>
                    <filter class="solr.StopFilterFactory" wo<caret>rds="stopwords.txt"/>
                  </analyzer>
                </fieldType>
                """.trimIndent(),
            ),
        )
        assertEmpty(myFixture.filterAvailableIntentions(hint))
    }

    fun testNothingIsOfferedOnAnAttributeThatDecidesSomething() {
        myFixture.configureByText(
            "managed-schema.xml",
            schema("""<field name="sku" type="string" ind<caret>exed="false"/>"""),
        )
        assertEmpty(myFixture.filterAvailableIntentions(hint))
    }

    fun testNothingIsOfferedOnAStructuralAttribute() {
        myFixture.configureByText(
            "managed-schema.xml",
            schema("""<field name="s<caret>ku" type="string"/>"""),
        )
        assertEmpty(myFixture.filterAvailableIntentions(hint))
    }

    fun testNothingIsOfferedOutsideAConfigset() {
        myFixture.configureByText(
            "beans.xml",
            """<beans><bean ind<caret>exed="true"/></beans>""",
        )
        assertEmpty(myFixture.filterAvailableIntentions(hint))
    }
}
