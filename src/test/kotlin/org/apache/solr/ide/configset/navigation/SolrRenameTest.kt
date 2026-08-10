package org.apache.solr.ide.configset.navigation

import org.apache.solr.ide.configset.activation.SolrConfigsetTestCase
import org.apache.solr.ide.configset.solrconfig.inspection.SolrUnknownFieldReferenceInspection
import java.io.File

/**
 * Renaming a declaration, and what has to follow it.
 *
 * A configset is held together by string references, so a rename that misses one produces a file
 * Solr rejects at startup — the failure mode this whole reference graph exists to prevent. These
 * assert the follow-through rather than the gesture: that every reference the plugin claims to
 * resolve is also a reference it can rewrite.
 */
class SolrRenameTest : SolrConfigsetTestCase() {

    /**
     * Demo step 34, on the configset the demo is actually driven on.
     *
     * The synthetic fixtures below each isolate one rule; this one proves the gesture works on the
     * committed file, for the reason
     * [org.apache.solr.ide.configset.reading.DemoConfigsetTest] gives — a refactoring that works on
     * every crafted case and not on the demo fixture fails in front of an audience. It also pins the
     * `qf` that makes the step worth performing: delete `category` from it and this fails a build
     * rather than the demo.
     */
    fun testDemoStep34RenamesCategoryAcrossBothFiles() {
        val demo = File(System.getProperty("user.dir"), "demo/solr/conf")
        assertTrue("demo configset not found at $demo", demo.isDirectory)
        val config = myFixture.addFileToProject(
            "demo/solr/conf/solrconfig.xml",
            File(demo, "solrconfig.xml").readText(),
        )
        val schema = myFixture.addFileToProject(
            "demo/solr/conf/managed-schema.xml",
            File(demo, "managed-schema.xml").readText(),
        )

        val declaration = schema.text.indexOf("""<field name="category""")
        assertTrue("the demo schema no longer declares `category`", declaration >= 0)
        myFixture.openFileInEditor(schema.virtualFile)
        myFixture.editor.caretModel.moveToOffset(declaration + """<field name="""".length)

        myFixture.renameElementAtCaret("product_category")

        assertTrue(
            "the qf line must name the new field: ${config.text.lines().first { "qf" in it }}",
            """<str name="qf">name^3 description product_category</str>""" in config.text,
        )
        assertFalse("no reference may still name the old field", """>category<""" in config.text)
    }

    fun testRenamingAFieldTypeUpdatesEveryFieldUsingIt() {
        myFixture.configureByText(
            "managed-schema.xml",
            """
            <schema name="products">
              <fieldType name="text_gen<caret>eral" class="solr.TextField"/>
              <field name="name" type="text_general"/>
              <field name="description" type="text_general"/>
            </schema>
            """.trimIndent(),
        )

        myFixture.renameElementAtCaret("text_searchable")

        myFixture.checkResult(
            """
            <schema name="products">
              <fieldType name="text_searchable" class="solr.TextField"/>
              <field name="name" type="text_searchable"/>
              <field name="description" type="text_searchable"/>
            </schema>
            """.trimIndent(),
        )
    }

    fun testRenamingAFieldUpdatesItsCopyFieldEnds() {
        myFixture.configureByText(
            "managed-schema.xml",
            """
            <schema name="products">
              <fieldType name="text_general" class="solr.TextField"/>
              <field name="descri<caret>ption" type="text_general"/>
              <field name="text" type="text_general"/>
              <copyField source="description" dest="text"/>
            </schema>
            """.trimIndent(),
        )

        myFixture.renameElementAtCaret("summary")

        myFixture.checkResult(
            """
            <schema name="products">
              <fieldType name="text_general" class="solr.TextField"/>
              <field name="summary" type="text_general"/>
              <field name="text" type="text_general"/>
              <copyField source="summary" dest="text"/>
            </schema>
            """.trimIndent(),
        )
    }

    /**
     * **Renaming a dynamic pattern rewrites the references that spell it, and only those.**
     *
     * [SolrDynamicFieldSearcher] reports a name the pattern *supplies* — `body_t` — as a usage of
     * `*_t`, which is correct and is the point of it. Being a usage and being rewritable to the new
     * name are different relations, though, and `PsiReferenceBase` assumes they are the same: left
     * alone it substitutes the declaration's new name into the reference's range, turning
     * `qf">name^3 body_t` into `qf">name^3 *_txt`. A pattern is not a field name, and Solr rejects
     * that — so the rewrite is confined to references that spell the declaration literally.
     *
     * What this deliberately does not do is carry `body_t` across to `body_txt`. The consequence is
     * that `body_t` matches nothing afterwards, which
     * [testTheNameLeftBehindIsReportedRatherThanLeftSilent] shows is reported rather than silent.
     */
    fun testRenamingADynamicFieldRewritesOnlyTheReferencesSpellingIt() {
        val config = myFixture.addFileToProject(
            "solrconfig.xml",
            """<config><requestHandler name="/select"><lst name="defaults">""" +
                """<str name="qf">name^3 body_t</str></lst></requestHandler></config>""",
        )
        myFixture.configureByText(
            "managed-schema.xml",
            """
            <schema name="products">
              <fieldType name="text_general" class="solr.TextField"/>
              <field name="name" type="text_general"/>
              <dynamicField name="*<caret>_t" type="text_general"/>
              <copyField source="name" dest="*_t"/>
            </schema>
            """.trimIndent(),
        )

        myFixture.renameElementAtCaret("*_txt")

        myFixture.checkResult(
            """
            <schema name="products">
              <fieldType name="text_general" class="solr.TextField"/>
              <field name="name" type="text_general"/>
              <dynamicField name="*_txt" type="text_general"/>
              <copyField source="name" dest="*_txt"/>
            </schema>
            """.trimIndent(),
        )
        assertEquals(
            "the name the pattern supplied must be left as it was written",
            """<config><requestHandler name="/select"><lst name="defaults">""" +
                """<str name="qf">name^3 body_t</str></lst></requestHandler></config>""",
            config.text,
        )
    }

    /**
     * **The consequence of leaving `body_t` alone is reported, not silent — and that is what makes
     * leaving it alone defensible.**
     *
     * Renaming the pattern out from under a name it used to supply does break the configset: nothing
     * declares `body_t` afterwards. Rename does not carry the name across, so the only honest
     * question is whether the reader finds out. They do, from the position itself, in Solr's
     * vocabulary — the same inspection that would have reported the name had it never resolved.
     */
    fun testTheNameLeftBehindIsReportedRatherThanLeftSilent() {
        val config = myFixture.addFileToProject(
            "solrconfig.xml",
            """<config><requestHandler name="/select"><lst name="defaults">""" +
                """<str name="qf">body_t</str></lst></requestHandler></config>""",
        )
        myFixture.configureByText(
            "managed-schema.xml",
            """
            <schema name="products">
              <fieldType name="text_general" class="solr.TextField"/>
              <dynamicField name="*<caret>_t" type="text_general"/>
            </schema>
            """.trimIndent(),
        )

        myFixture.renameElementAtCaret("*_txt")

        myFixture.enableInspections(SolrUnknownFieldReferenceInspection())
        myFixture.openFileInEditor(config.virtualFile)
        val complaints = myFixture.doHighlighting().mapNotNull { it.description }
        assertTrue(
            "expected the orphaned name to be reported, got $complaints",
            complaints.any { "body_t" in it },
        )
    }

    /**
     * **The one that matters most, and the one a hand-edit gets wrong.** The handler parameter lives
     * in the other file, and nothing in either file connects the two for a reader with a text editor.
     * A rename that updates the schema and leaves `qf` naming a field that no longer exists produces
     * a configset Solr rejects.
     */
    fun testRenamingAFieldUpdatesTheHandlerParameterInSolrconfig() {
        val config = myFixture.addFileToProject(
            "solrconfig.xml",
            """<config><requestHandler name="/select"><lst name="defaults">""" +
                """<str name="qf">name^3 description</str></lst></requestHandler></config>""",
        )
        myFixture.configureByText(
            "managed-schema.xml",
            """
            <schema name="products">
              <fieldType name="text_general" class="solr.TextField"/>
              <field name="name" type="text_general"/>
              <field name="descri<caret>ption" type="text_general"/>
            </schema>
            """.trimIndent(),
        )

        myFixture.renameElementAtCaret("summary")

        assertEquals(
            """<config><requestHandler name="/select"><lst name="defaults">""" +
                """<str name="qf">name^3 summary</str></lst></requestHandler></config>""",
            config.text,
        )
    }
}
