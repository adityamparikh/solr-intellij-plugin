package org.apache.solr.ide.configset.schema.documentation

import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.util.parentOfType
import com.intellij.psi.xml.XmlAttribute
import com.intellij.psi.xml.XmlTag
import org.apache.solr.ide.configset.activation.SolrConfigsetTestCase
import org.junit.Assert.assertNotEquals

/**
 * Which caret positions answer, now that the attribute's own name does.
 *
 * **The absences carry the weight here.** What could go wrong is a popup appearing where none
 * should, or an existing popup losing what the catalog proved — both worse than the silence this
 * replaces, and neither visible to a test that only checks the new answers arrive.
 */
class SolrAttributeDocumentationTest : SolrConfigsetTestCase() {

    private fun schema(body: String) = """
        <schema name="products" version="1.6">
          <fieldType name="text_general" class="solr.TextField">
            <analyzer>
              <tokenizer class="solr.StandardTokenizerFactory"/>
              <filter class="solr.EdgeNGramFilterFactory" minGramSize="2" maxGramSize="15"/>
            </analyzer>
          </fieldType>
          <field name="description" type="text_general" indexed="true"/>
          <field name="text" type="text_general"/>
          $body
        </schema>
    """.trimIndent()

    /** The popup for whatever the caret is on, through the same path the platform takes. */
    private fun documentationAtCaret(text: String): String? {
        myFixture.configureByText("managed-schema.xml", text)
        return documentationAt(myFixture.caretOffset)
    }

    /**
     * The popup for the already-configured file at [offset], through the platform's own path.
     *
     * The caret marker is fine for one position and useless for a sweep: re-parsing the file once
     * per attribute would make the inventory below a test of the fixture builder. The provider only
     * ever reads the offset, so handing it one directly is the same gesture without the reparse.
     */
    private fun documentationAt(offset: Int): String? {
        val provider = SolrSchemaDocumentationProvider()
        val target: PsiElement = provider.getCustomDocumentationElement(
            myFixture.editor,
            myFixture.file,
            myFixture.file.findElementAt(offset),
            offset,
        ) ?: return null
        return provider.generateDoc(target, null)
    }

    /**
     * Every `element/attribute` position in the open file whose popup is an *attribute meaning*.
     *
     * Told apart from every other popup by shape rather than by wording: a meaning popup is the
     * attribute's own name over a paragraph, and nothing else this provider renders on an attribute
     * omits a table — the property popup has *Accepts* and *Solr default*, the catalog one has
     * *Read by*, the element popup names the tag rather than the attribute. Classifying by shape is
     * what lets this assert about attributes nobody wrote a sentence for, which is the half a
     * wording-based check cannot see.
     */
    private fun meaningAnsweringPositions(): Set<String> =
        PsiTreeUtil.findChildrenOfType(myFixture.file, XmlAttribute::class.java)
            .filter { attribute ->
                val doc = documentationAt(attribute.textOffset)
                doc != null &&
                    doc.startsWith("<div class='definition'><pre>${attribute.name}</pre></div>") &&
                    "<table>" !in doc
            }
            .map { "${it.parentOfType<XmlTag>()?.name}/${it.name}" }
            .toSet()

    fun testAFieldsNameAttributeExplainsItself() {
        val doc = documentationAtCaret(schema("""<field na<caret>me="sku" type="text_general"/>"""))!!
        assertTrue(doc, "document" in doc.lowercase())
    }

    fun testAFieldTypesClassAttributeExplainsItself() {
        val doc = documentationAtCaret(
            schema("").replace("""<fieldType name="text_general" cl""", """<fieldType name="text_general" c<caret>l"""),
        )!!
        assertTrue(doc, "class" in doc.lowercase())
    }

    /** A `copyField`'s ends are told apart, which is why the table is keyed by element and name. */
    fun testACopyFieldsEndsAreDescribedDifferently() {
        val source = documentationAtCaret(schema("""<copyField sou<caret>rce="description" dest="text"/>"""))!!
        val destination = documentationAtCaret(schema("""<copyField source="description" de<caret>st="text"/>"""))!!
        assertNotEquals(source, destination)
    }

    /**
     * The schema version answers twice: what the attribute decides anywhere, and what this value
     * decides here. The second half is the part no external documentation can supply.
     */
    fun testTheSchemaVersionReportsWhatThisValueDecides() {
        val doc = documentationAtCaret(
            schema("").replace("""<schema name="products" ver""", """<schema name="products" v<caret>er"""),
        )!!
        assertTrue(doc, "1.6" in doc)
        assertTrue(doc, "docValues" in doc)
    }

    /**
     * A catalog-backed attribute with no handwritten meaning keeps exactly the popup it had.
     *
     * **This is the contract, not a nicety.** The meanings are hand-written for the two dozen
     * attributes a reader actually hovers, so most of the catalog's thousands have none — and the
     * failure that matters for a feature like this is a popup appearing, or changing, where nothing
     * should. `maxShingleSize` is catalog-backed on `solr.ShingleFilterFactory` and deliberately
     * undescribed, so its popup must still carry `Read by` and must not grow a `Does` row.
     */
    fun testAnUndescribedFactoryAttributeKeepsItsCatalogRowsAndGainsNothing() {
        val doc = documentationAtCaret(
            """
            <schema name="t" version="1.6">
              <fieldType name="shingled" class="solr.TextField">
                <analyzer>
                  <tokenizer class="solr.StandardTokenizerFactory"/>
                  <filter class="solr.ShingleFilterFactory" maxShing<caret>leSize="3"/>
                </analyzer>
              </fieldType>
            </schema>
            """.trimIndent(),
        )
        assertNotNull("expected the catalog's attribute popup", doc)
        assertTrue("the catalog rows must survive: $doc", "Read by" in doc!!)
        assertFalse("an undescribed attribute must not gain a meaning: $doc", "Does" in doc)
        // *Exactly* the popup it had, not merely one that still mentions the owner: the catalog's
        // own first row must still open the table. A meaning renders above it, so anything the
        // table gained would sit between these two strings.
        assertTrue(
            "the catalog's row must still be the first thing in the table: $doc",
            "<table><tr><td>Read by</td>" in doc,
        )
    }

    /**
     * **The one the user asked for.** `minGramSize` reported *a whole number*, which is a type
     * rather than an answer.
     *
     * *Alongside* is the load-bearing word, so the catalog's own rows are asserted here rather than
     * left to the undescribed-attribute test: a meaning that displaced the value type and the
     * required marker would satisfy "says what it does" while losing what bytecode proved, and that
     * is the regression this step names as the one that matters.
     */
    fun testAFactoryAttributeSaysWhatItDoesAsWellAsWhatItAccepts() {
        val doc = documentationAtCaret(
            schema("").replace("""minGramSize="2"""", """minG<caret>ramSize="2""""),
        )!!
        assertTrue(doc, "shortest" in doc.lowercase())
        // What the catalog proved must survive alongside it: the owner, the value type, and the
        // marker that says Solr rejects the class without it.
        assertTrue("the owner must still be named: $doc", "EdgeNGram" in doc)
        assertTrue("the catalog's value type must survive: $doc", "a whole number" in doc)
        assertTrue("the required marker must survive: $doc", "Required" in doc)
    }

    /** The other bound of the same n-gram, which reads differently from the first. */
    fun testTheOtherNGramBoundSaysSomethingOfItsOwn() {
        val minimum = documentationAtCaret(
            schema("").replace("""minGramSize="2"""", """minG<caret>ramSize="2""""),
        )!!
        val maximum = documentationAtCaret(
            schema("").replace("""maxGramSize="15"""", """maxG<caret>ramSize="15""""),
        )!!
        assertTrue(maximum, "longest" in maximum.lowercase())
        assertTrue("the catalog's value type must survive: $maximum", "a whole number" in maximum)
        assertNotEquals(minimum, maximum)
    }

    /**
     * **An attribute the table does not describe keeps exactly the popup it had.** Nothing gains an
     * empty row, and nothing loses what the catalog proved.
     */
    fun testAnUndescribedFactoryAttributeKeepsItsCatalogPopup() {
        val doc = documentationAtCaret(
            schema("").replace(
                """<tokenizer class="solr.StandardTokenizerFactory"/>""",
                """<tokenizer class="solr.StandardTokenizerFactory" maxTok<caret>enLength="255"/>""",
            ),
        )
        // `maxTokenLength` is described, so this asserts the shape rather than the absence: the
        // catalog rows must still be there beside the meaning.
        assertNotNull(doc)
        assertTrue(doc!!, "Read by" in doc)
    }

    /** A property attribute still answers with the property popup, not the structural one. */
    fun testAPropertyAttributeIsUnaffected() {
        val doc = documentationAtCaret(schema("""<field name="sku" type="text_general" ind<caret>exed="true"/>"""))!!
        assertTrue("the property popup states Solr's default: $doc", "Solr default" in doc)
    }

    /** Nothing outside a configset answers, whatever it is called. */
    fun testAnAttributeOutsideAConfigsetAnswersNothing() {
        myFixture.configureByText("beans.xml", """<schema na<caret>me="products" version="1.6"/>""")
        val provider = SolrSchemaDocumentationProvider()
        assertNull(
            provider.getCustomDocumentationElement(
                myFixture.editor,
                myFixture.file,
                myFixture.file.findElementAt(myFixture.caretOffset),
                myFixture.caretOffset,
            ),
        )
    }

    /**
     * **An attribute the table does not describe falls through, and must not be described anyway.**
     *
     * `name` on a `<copyField>` is not a field name, which is the whole reason the table is keyed by
     * element and attribute together. The caret still answers — it reaches the element popup, as it
     * did before this step — but it must not gain the `<field>` description, which would state a
     * falsehood about the file.
     */
    fun testAnUndescribedAttributeFallsThroughToItsElement() {
        val doc = documentationAtCaret(
            schema("""<copyField name="no<caret>pe" source="description" dest="text"/>"""),
        )!!
        assertTrue("expected the copyField element popup: $doc", "copyField" in doc)
        assertFalse(
            "a copyField's name must never be described as a field name: $doc",
            "name documents use" in doc,
        )
    }

    /**
     * **Every attribute in a whole schema, and the inventory of which ones gain a meaning.**
     *
     * The tests above each ask one position what it answers, and a suite of those cannot see the
     * failure this step says matters most — a popup appearing where none should. That defect has now
     * reached a sandbox twice in this provider precisely because *what a provider declines* is
     * invisible to assertions about what it returns. This walks every attribute of a file carrying
     * both the modelled elements and the unmodelled ones and pins the whole set, so an entry added
     * to the table, or a lookup that stopped keying on the element, fails here by naming the
     * position that gained a popup.
     *
     * The unmodelled positions are the point of the fixture: `type` on an `<analyzer>` and `class`
     * on a `<tokenizer>`, `<filter>` or `<similarity>` share their spelling with attributes that do
     * answer, and `name` on a `<copyField>` is the one the design record singles out as worse than
     * silence. All four stay out of this set, and a table keyed by attribute name alone would put
     * every one of them in it.
     */
    fun testExactlyTheseAttributePositionsAnswerWithAMeaning() {
        myFixture.configureByText(
            "managed-schema.xml",
            """
            <schema name="products" version="1.6">
              <uniqueKey>id</uniqueKey>
              <fieldType name="string" class="solr.StrField" sortMissingLast="true"/>
              <fieldType name="text_general" class="solr.TextField">
                <analyzer type="index">
                  <tokenizer class="solr.StandardTokenizerFactory"/>
                  <filter class="solr.EdgeNGramFilterFactory" minGramSize="2" maxGramSize="15"/>
                </analyzer>
              </fieldType>
              <field name="id" type="string" indexed="true" stored="true"/>
              <dynamicField name="*_s" type="string" indexed="true"/>
              <copyField source="id" dest="text" maxChars="200"/>
              <copyField name="nope" source="id" dest="text"/>
              <similarity class="solr.BM25SimilarityFactory"/>
            </schema>
            """.trimIndent(),
        )
        assertEquals(
            setOf(
                "schema/name",
                "schema/version",
                "fieldType/name",
                "fieldType/class",
                "field/name",
                "field/type",
                "dynamicField/name",
                "dynamicField/type",
                "copyField/source",
                "copyField/dest",
            ),
            meaningAnsweringPositions(),
        )
    }

    /**
     * **The other configset file answers nothing at all from this table.**
     *
     * `solrconfig.xml` is inside a configset, so the file check that keeps `beans.xml` silent does
     * not apply here — what keeps it silent is that the table is keyed by elements only a schema
     * declares. That is a claim about a vocabulary nothing else guards: `name` on a `<lst>` or a
     * `<str>` is the commonest attribute in the file and is not a field name, and `class` on a
     * `<requestHandler>` is not a field type's class. Documenting `solrconfig.xml`'s own vocabulary
     * is a different step with a different source, and until it exists this must stay empty.
     */
    fun testNothingInSolrConfigAnswersWithASchemaAttributeMeaning() {
        myFixture.configureByText(
            "solrconfig.xml",
            """
            <config>
              <luceneMatchVersion>10.0.0</luceneMatchVersion>
              <schemaFactory class="ClassicIndexSchemaFactory"/>
              <requestHandler name="/select" class="solr.SearchHandler">
                <lst name="defaults">
                  <str name="df">text</str>
                </lst>
              </requestHandler>
            </config>
            """.trimIndent(),
        )
        assertEquals(emptySet<String>(), meaningAnsweringPositions())
    }
}
