package org.apache.solr.ide.configset.parsing

import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.psi.PsiDocumentManager
import com.intellij.openapi.vfs.VirtualFile
import org.apache.solr.ide.configset.activation.SolrConfigsetDetector
import org.apache.solr.ide.configset.activation.SolrConfigsetTestCase

class SolrConfigsetReaderTest : SolrConfigsetTestCase() {

    private val reader: SolrConfigsetReader get() = SolrConfigsetReader.getInstance(project)

    private val schema = """
        <schema name="products">
          <fieldType name="string" class="solr.StrField"/>
          <field name="id" type="string" indexed="true"/>
          <field name="name" type="string" indexed="true"/>
          <dynamicField name="*_s" type="string"/>
          <uniqueKey>id</uniqueKey>
          <copyField source="name" dest="text"/>
        </schema>
    """.trimIndent()

    private val config = """
        <config>
          <requestHandler name="/select" class="solr.SearchHandler">
            <lst name="defaults"><str name="qf">name^3</str></lst>
          </requestHandler>
        </config>
    """.trimIndent()

    private fun givenConfigset(): org.apache.solr.ide.configset.activation.SolrConfigset {
        myFixture.addFileToProject("core/conf/solrconfig.xml", config)
        val file = myFixture.addFileToProject("core/conf/managed-schema.xml", schema).virtualFile
        return SolrConfigsetDetector.configsetFor(project, file)!!
    }

    /**
     * Edits the file and commits, which is what the IDE does before running any of the features
     * that read the model. The model is derived from PSI, so an uncommitted document is text no
     * consumer of the model would be looking at either.
     */
    private fun edit(file: VirtualFile, text: String) {
        WriteAction.runAndWait<RuntimeException> {
            FileDocumentManager.getInstance().getDocument(file)!!.setText(text)
            PsiDocumentManager.getInstance(project).commitAllDocuments()
        }
    }

    fun testBothFilesContributeToOneModel() {
        val model = reader.modelFor(givenConfigset())

        assertEquals(setOf("id", "name"), model.fields.keys)
        assertEquals(setOf("*_s"), model.dynamicFields.keys)
        assertEquals(setOf("string"), model.fieldTypes.keys)
        assertEquals("id", model.uniqueKey!!.effective)
        assertEquals(1, model.copyFields.size)
        assertEquals("the qf reference must survive into the merged model", "name", model.fieldReferences.single().fieldName)
    }

    fun testModelIsCachedBetweenLookups() {
        val configset = givenConfigset()

        val first = reader.modelFor(configset)
        val second = reader.modelFor(configset)

        assertSame("an unchanged configset must not be reparsed", first, second)
    }

    /** The half of the criterion that costs performance if it is wrong. */
    fun testModelRebuildsWhenTheSchemaChanges() {
        val configset = givenConfigset()
        val before = reader.modelFor(configset)

        val schemaFile = configset.root.findChild("managed-schema.xml")!!
        edit(schemaFile, schema.replace("""<field name="name" type="string" indexed="true"/>""", ""))

        val after = reader.modelFor(configset)
        assertNotSame(before, after)
        assertEquals(setOf("id"), after.fields.keys)
    }

    /**
     * Editing an unrelated file must not invalidate the model, or every keystroke reparses.
     *
     * The file is created *before* the baseline deliberately. Creating a file is a structural change
     * and does invalidate — see [testASolrconfigAddedLaterIsPickedUp] for why that is wanted — so a
     * test that created and edited in one breath would be asserting the wrong thing about the wrong
     * event.
     */
    fun testModelIsNotRebuiltWhenAnUnrelatedFileChanges() {
        val configset = givenConfigset()
        val unrelated = myFixture.addFileToProject("src/App.java", "class App {}").virtualFile
        val before = reader.modelFor(configset)

        edit(unrelated, "class App { int x; }")

        assertSame(before, reader.modelFor(configset))
    }

    /**
     * The other half of the dependency list. A configset can acquire a `solrconfig.xml` it did not
     * have, and no dependency on the files that existed at build time can notice that — only the
     * VFS structure count can.
     */
    fun testASolrconfigAddedLaterIsPickedUp() {
        val schemaFile = myFixture.addFileToProject("late/conf/managed-schema.xml", schema).virtualFile
        val configset = SolrConfigsetDetector.configsetFor(project, schemaFile)!!
        assertTrue("no handler parameters yet", reader.modelFor(configset).fieldReferences.isEmpty())

        myFixture.addFileToProject("late/conf/solrconfig.xml", config)

        assertEquals(
            "the qf reference must appear once solrconfig.xml exists",
            "name",
            reader.modelFor(configset).fieldReferences.single().fieldName,
        )
    }

    /** Unsaved edits count: the plugin must not disagree with what the user is looking at. */
    fun testUnsavedEditsAreVisibleToTheModel() {
        val configset = givenConfigset()
        reader.modelFor(configset)

        val schemaFile = configset.root.findChild("managed-schema.xml")!!
        edit(schemaFile, schema.replace("<uniqueKey>id</uniqueKey>", """<field name="added" type="string"/><uniqueKey>id</uniqueKey>"""))

        assertTrue(
            "a field added in the editor must be in the model before the file is saved",
            "added" in reader.modelFor(configset).fields,
        )
    }

    fun testTwoConfigsetsGetSeparateModels() {
        myFixture.addFileToProject("products/conf/managed-schema.xml", schema)
        val orders = myFixture.addFileToProject(
            "orders/conf/managed-schema.xml",
            """<schema><field name="order_id" type="string"/></schema>""",
        ).virtualFile
        val products = myFixture.addFileToProject("products/conf/solrconfig.xml", config).virtualFile

        val productsModel = reader.modelFor(SolrConfigsetDetector.configsetFor(project, products)!!)
        val ordersModel = reader.modelFor(SolrConfigsetDetector.configsetFor(project, orders)!!)

        assertEquals(setOf("id", "name"), productsModel.fields.keys)
        assertEquals(setOf("order_id"), ordersModel.fields.keys)
    }

    /** A configset whose files are unreadable or malformed yields an empty model, never an error. */
    fun testAMalformedSchemaYieldsAnEmptyModel() {
        myFixture.addFileToProject("core/conf/solrconfig.xml", config)
        val broken = myFixture.addFileToProject("core/conf/managed-schema.xml", "<schema><field name=").virtualFile
        val model = reader.modelFor(SolrConfigsetDetector.configsetFor(project, broken)!!)
        assertTrue(model.fields.isEmpty())
        assertEquals("the readable file's facts must survive its neighbour being broken", 1, model.fieldReferences.size)
    }
}
