package org.apache.solr.ide.configset.navigation

import com.intellij.codeInsight.TargetElementUtil
import com.intellij.psi.PsiElement
import com.intellij.psi.search.LocalSearchScope
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.util.parentOfType
import com.intellij.psi.xml.XmlAttributeValue
import com.intellij.psi.xml.XmlTag
import org.apache.solr.ide.configset.activation.SolrConfigsetTestCase
import org.apache.solr.ide.configset.reference.SolrSchemaPsi

/**
 * Find Usages from a declaration, asserted at the position where the behaviour is decided.
 *
 * **This suite was written to assert the opposite, and the inversion is the proof.** Before
 * declarations became targets, three of these tests asserted that a `field`, `dynamicField` and
 * `fieldType` declaration each yielded *no* target — which was the *Cannot search for usages from
 * this location* the user saw. They now assert a target, unchanged in every other respect, while the
 * reference and reverse-search assertions written alongside them still hold exactly as they did.
 * A suite that went from asserting an absence to asserting a presence is better evidence than new
 * tests grading their own homework.
 *
 * [TargetElementUtil.findTargetElement] is the call the Find Usages action itself makes, so asserting
 * against it rather than against the action keeps these at the level the behaviour is decided.
 *
 * **The negative cases carry the weight.** Most of what could go wrong here is a target appearing
 * somewhere it should not — `name` is the commonest attribute in XML — so the absences below are
 * load-bearing, and each names the guard it exercises. One of them is narrower than it looks:
 * a caret on an attribute *name* does yield a target, the platform's own, and what must not appear
 * is a Solr declaration target. That was measured rather than assumed, and the design record was
 * corrected to match.
 */
class SolrDeclarationTargetTest : SolrConfigsetTestCase() {

    /**
     * One schema, with the caret placed by substituting whichever attribute the test is about.
     * Each test therefore states its own caret position literally, which is the part that carries
     * the meaning.
     */
    private fun schema(
        fieldType: String = """name="text_general"""",
        nameFieldType: String = """type="text_general"""",
        description: String = """name="description"""",
        dynamic: String = """name="*_t"""",
        copyFieldDestination: String = "description",
    ) = """
        <schema name="products">
          <fieldType $fieldType class="solr.TextField"/>
          <field name="name" $nameFieldType/>
          <field $description type="text_general"/>
          <dynamicField $dynamic type="text_general"/>
          <copyField source="name" dest="$copyFieldDestination"/>
        </schema>
    """.trimIndent()

    private fun handler(body: String) =
        """<config><requestHandler name="/select"><lst name="defaults">$body</lst></requestHandler></config>"""

    private fun targetIn(schemaText: String): PsiElement? =
        targetInFile("managed-schema.xml", schemaText)

    private fun targetInFile(fileName: String, text: String): PsiElement? {
        myFixture.configureByText(fileName, text)
        return TargetElementUtil.findTargetElement(
            myFixture.editor,
            TargetElementUtil.getInstance().allAccepted,
        )
    }

    private fun describe(element: PsiElement?) =
        if (element == null) "null" else "${element.javaClass.simpleName}(${element.text})"

    fun testAFieldTypeDeclarationYieldsATarget() {
        val target = targetIn(schema(fieldType = """name="text_gen<caret>eral""""))
        assertTrue(
            "expected a Solr declaration target, got ${describe(target)}",
            isSolrDeclarationTarget(target),
        )
    }

    fun testAFieldDeclarationYieldsATarget() {
        val target = targetIn(schema(description = """name="descri<caret>ption""""))
        assertTrue(
            "expected a Solr declaration target, got ${describe(target)}",
            isSolrDeclarationTarget(target),
        )
    }

    fun testADynamicFieldDeclarationYieldsATarget() {
        val target = targetIn(schema(dynamic = """name="*<caret>_t""""))
        assertTrue(
            "expected a Solr declaration target, got ${describe(target)}",
            isSolrDeclarationTarget(target),
        )
    }

    /**
     * **The one thing that cannot be established by reading the platform, so it is established
     * first.** Every reference here resolves to a raw [XmlAttributeValue], while Find Usages from a
     * declaration will hand `isReferenceTo` the `PomTargetPsiElement` the platform wrapped that
     * value in. `PsiReferenceBase.isReferenceTo` compares the two through `areElementsEquivalent`,
     * so the whole step rests on the platform equating a POM target with the element it delegates
     * to. If it does not, the search comes back empty and the feature is a worse failure than the
     * refusal it replaces — which is why this fixture exists before the searcher does.
     */
    fun testFindUsagesFromADeclarationReachesTheCrossFileReference() {
        myFixture.addFileToProject("solrconfig.xml", handler("""<str name="qf">name^3 description</str>"""))
        val target = targetIn(schema(description = """name="descri<caret>ption""""))
        assertNotNull("no target on the declaration to search from", target)

        val hits = ReferencesSearch.search(target!!).findAll()
        assertTrue(
            "expected the qf parameter among the usages, got ${hits.map { it.element.containingFile.name }}",
            hits.any { it.element.containingFile.name == "solrconfig.xml" },
        )
    }

    /** The contrast that makes the absence above a target problem rather than a graph problem. */
    fun testAFieldTypeReferenceYieldsATarget() {
        assertNotNull(
            "a reference must resolve to a target",
            targetIn(schema(nameFieldType = """type="text_gen<caret>eral"""")),
        )
    }

    /** The same contrast across the file boundary. */
    fun testAHandlerParameterReferenceYieldsATarget() {
        myFixture.addFileToProject("managed-schema.xml", schema())
        myFixture.configureByText("solrconfig.xml", handler("""<str name="qf">name^3 descri<caret>ption</str>"""))
        assertNotNull(
            "a cross-file reference must resolve to a target",
            TargetElementUtil.findTargetElement(myFixture.editor, TargetElementUtil.getInstance().allAccepted),
        )
    }

    /**
     * The reverse traversal already works, which is why this step is about targets and not about
     * search. Overlaps `SolrConfigFieldReferenceTest` on purpose: there it is the reference's own
     * behaviour, here it is half of the diagnosis.
     */
    fun testSearchingADeclarationReachesTheCrossFileReference() {
        val schemaFile = myFixture.addFileToProject("managed-schema.xml", schema())
        myFixture.configureByText("solrconfig.xml", handler("""<str name="qf">name^3 description</str>"""))
        val hits = ReferencesSearch.search(SolrSchemaPsi.findField(schemaFile, "description")!!).findAll()
        assertTrue(
            "expected the qf parameter among the usages, got ${hits.map { it.element.containingFile.name }}",
            hits.any { it.element.containingFile.name == "solrconfig.xml" },
        )
    }

    /**
     * **The case the default search cannot reach, and the reason the step carries a
     * `referencesSearch` executor.** `ReferencesSearch` picks candidates out of the word index
     * before asking any reference to confirm itself, and `body_t` shares no word with `*_t`, so the
     * reference that genuinely resolves to this pattern was never offered for confirmation. Left
     * alone, Find Usages on a dynamic field reports an empty list — which reads as *nothing uses
     * this pattern*, the silently-incomplete answer this plugin's posture exists to prevent.
     */
    fun testSearchingADynamicFieldReachesTheNameItsPatternSupplies() {
        val schemaFile = myFixture.addFileToProject("managed-schema.xml", schema())
        myFixture.configureByText("solrconfig.xml", handler("""<str name="qf">name^3 body<caret>_t</str>"""))

        // The premise, asserted first: without it the assertion below would also hold if the
        // reference had never been contributed, or had stopped resolving through the pattern.
        val resolved = myFixture.getReferenceAtCaretPosition()?.resolve()
        val declaringTag = (resolved as? XmlAttributeValue)?.parentOfType<XmlTag>()
        assertEquals("body_t must resolve through the pattern", "dynamicField", declaringTag?.name)
        assertEquals("*_t", declaringTag?.getAttributeValue("name"))

        val hits = ReferencesSearch.search(SolrSchemaPsi.findField(schemaFile, "*_t")!!).findAll()
        val inHandler = hits.filter { it.element.containingFile.name == "solrconfig.xml" }
        assertEquals(
            "expected exactly the qf occurrence, got ${inHandler.map { it.element.text }}",
            1,
            inHandler.size,
        )
    }

    /**
     * `name` is the commonest attribute in XML, so the guard that matters most is the one that
     * declines. Without it every `name=` in every `pom.xml` in the project would offer a Solr target.
     */
    fun testANameAttributeOutsideAConfigsetYieldsNoTarget() {
        val target = targetInFile(
            "beans.xml",
            """<schema name="products"><field name="descri<caret>ption" type="text_general"/></schema>""",
        )
        assertNull("expected no Solr target outside a configset, got ${describe(target)}", target)
    }

    /**
     * A `name` the plugin does not model as a declaration stays inert.
     *
     * Making a request handler's name a target would produce an empty usage list, which teaches the
     * reader that the search is broken rather than that nothing references it.
     */
    fun testARequestHandlerNameYieldsNoTarget() {
        myFixture.addFileToProject("managed-schema.xml", schema())
        val target = targetInFile(
            "solrconfig.xml",
            """<config><requestHandler name="/sel<caret>ect"><lst name="defaults"/></requestHandler></config>""",
        )
        assertNull("expected no target on a request handler name, got ${describe(target)}", target)
    }

    /**
     * The caret has to be in the value; the attribute's own name declares no field.
     *
     * **This assertion is narrower than the obvious one, and measurement is why.** The obvious
     * version — that the position yields nothing at all — is false, and was false before this step
     * existed: the platform answers an attribute name with the enclosing tag, through the descriptor
     * machinery [org.apache.solr.ide.configset.descriptor.SolrSchemaElementDescriptorProvider]
     * feeds. That is the platform's answer to *what attribute is this*, and it is not this step's to
     * remove. What must not appear here is a Solr *declaration* target, which would say the caret is
     * on the field `description` when it is on the word `name`.
     */
    fun testACaretOnTheAttributeNameYieldsNoSolrTarget() {
        val target = targetIn(schema(description = """na<caret>me="description""""))
        assertFalse(
            "expected no Solr declaration target on the attribute name, got ${describe(target)}",
            isSolrDeclarationTarget(target),
        )
    }

    /**
     * Only a schema declares fields.
     *
     * A `<field>` written into `solrconfig.xml` declares nothing Solr would read, so a target there
     * would be a name whose usages are necessarily somebody else's.
     */
    fun testAFieldTagOutsideTheSchemaYieldsNoTarget() {
        myFixture.addFileToProject("managed-schema.xml", schema())
        val target = targetInFile(
            "solrconfig.xml",
            """<config><field name="descri<caret>ption" type="text_general"/></config>""",
        )
        assertNull("expected no target outside the schema, got ${describe(target)}", target)
    }

    /**
     * **Solr resolves a field name per configset, and so does this.** A project holding two cores
     * that both declare `*_t` has two unrelated declarations; reporting one core's references under
     * the other's pattern would be wrong rather than merely noisy.
     */
    fun testASameNamedDynamicFieldInASecondConfigsetIsNotReported() {
        val products = myFixture.addFileToProject("products/conf/managed-schema.xml", schema())
        myFixture.addFileToProject("products/conf/solrconfig.xml", handler("""<str name="qf">body_t</str>"""))
        myFixture.addFileToProject("orders/conf/managed-schema.xml", schema())
        myFixture.addFileToProject("orders/conf/solrconfig.xml", handler("""<str name="qf">title_t</str>"""))

        val hits = ReferencesSearch.search(SolrSchemaPsi.findField(products, "*_t")!!).findAll()
        assertTrue(
            "expected only the products configset, got ${hits.map { it.element.containingFile.virtualFile.path }}",
            hits.all { "/products/" in it.element.containingFile.virtualFile.path },
        )
        assertTrue("expected the products qf among them", hits.any { it.element.text.contains("body_t") })
    }

    /**
     * The executor answers the search it was asked, not the one it would rather answer.
     *
     * Bounding the walk to the configset is what makes the dynamic case possible at all, but the
     * configset is the *upper* bound: a caller narrowing the search to one file has said which
     * results it wants, and reporting the other file's anyway would put usages in a Find Usages
     * window that explicitly excluded them.
     */
    fun testADynamicFieldSearchHonoursANarrowedScope() {
        val schemaFile = myFixture.addFileToProject("managed-schema.xml", schema(copyFieldDestination = "*_t"))
        myFixture.addFileToProject("solrconfig.xml", handler("""<str name="qf">body_t</str>"""))

        val hits = ReferencesSearch.search(
            SolrSchemaPsi.findField(schemaFile, "*_t")!!,
            LocalSearchScope(schemaFile),
            false,
        ).findAll()
        assertTrue(
            "expected nothing outside the searched file, got ${hits.map { it.element.containingFile.name }}",
            hits.none { it.element.containingFile.name == "solrconfig.xml" },
        )
    }

    /**
     * **A scope can be narrower than a file, and the walk has to respect that too.**
     *
     * The file-level check this started with passed the whole-file fixture above while still
     * reporting everything in that file — a `LocalSearchScope` built from one tag excludes its
     * siblings, and the walk put them back. Asking the scope about the reference's own element
     * rather than about its file is what makes the bound the caller's rather than approximately
     * the caller's.
     */
    fun testADynamicFieldSearchHonoursAScopeNarrowerThanAFile() {
        val schemaFile = myFixture.addFileToProject("managed-schema.xml", schema())
        val config = myFixture.addFileToProject(
            "solrconfig.xml",
            """<config><requestHandler name="/select"><lst name="defaults">""" +
                """<str name="qf">body_t</str><str name="pf">title_t</str>""" +
                """</lst></requestHandler></config>""",
        )
        val queryFields = PsiTreeUtil.findChildrenOfType(config, XmlTag::class.java)
            .single { it.name == "str" && it.getAttributeValue("name") == "qf" }

        val hits = ReferencesSearch.search(
            SolrSchemaPsi.findField(schemaFile, "*_t")!!,
            LocalSearchScope(queryFields),
            false,
        ).findAll()

        assertEquals(
            "expected only the scoped tag's occurrence, got ${hits.map { it.element.text }}",
            listOf("body_t"),
            hits.map { it.rangeInElement.substring(it.element.text) },
        )
    }

    /** A declaration is not a usage of itself, however the search reached it. */
    fun testADeclarationIsNotAUsageOfItself() {
        val schemaFile = myFixture.addFileToProject("managed-schema.xml", schema())
        myFixture.addFileToProject("solrconfig.xml", handler("""<str name="qf">body_t</str>"""))

        val declaration = SolrSchemaPsi.findField(schemaFile, "*_t")!!
        val hits = ReferencesSearch.search(declaration).findAll()
        assertTrue(
            "the declaration reported itself: ${hits.map { it.element.text }}",
            hits.none { it.element == declaration },
        )
    }

    /**
     * The usage is reported at the name itself, not at the whole parameter value.
     *
     * `name^3 body_t` holds two field names and a boost; highlighting the lot would tell the reader
     * the pattern is used by something it is not, in a value where `name` is a *different* field.
     */
    fun testADynamicFieldUsageIsReportedAtTheNameAlone() {
        val schemaFile = myFixture.addFileToProject("managed-schema.xml", schema())
        myFixture.configureByText("solrconfig.xml", handler("""<str name="qf">name^3 body_t</str>"""))

        val hit = ReferencesSearch.search(SolrSchemaPsi.findField(schemaFile, "*_t")!!).findAll()
            .single { it.element.containingFile.name == "solrconfig.xml" }
        val absolute = hit.rangeInElement.shiftRight(hit.element.textRange.startOffset)
        assertEquals("body_t", hit.element.containingFile.text.substring(absolute.startOffset, absolute.endOffset))
    }

    /**
     * **The executor adds to the default result rather than replacing it.** A `copyField dest="*_t"`
     * spells the pattern literally, so it is a usage by the plain reading as well as by resolution.
     * Losing it while gaining `body_t` would trade one silent omission for another — and reporting
     * it twice would tell the reader there are two of them.
     */
    fun testSearchingADynamicFieldStillReportsTheReferenceSpellingItLiterally() {
        val schemaFile = myFixture.addFileToProject(
            "managed-schema.xml",
            schema(copyFieldDestination = "*_t"),
        )
        myFixture.configureByText("solrconfig.xml", handler("""<str name="qf">name^3 body_t</str>"""))

        val hits = ReferencesSearch.search(SolrSchemaPsi.findField(schemaFile, "*_t")!!).findAll()
        val literal = hits.filter { it.element.text.contains("*_t") }
        assertEquals(
            "expected the copyField dest reported exactly once, got ${hits.map { it.element.text }}",
            1,
            literal.size,
        )
    }
}
