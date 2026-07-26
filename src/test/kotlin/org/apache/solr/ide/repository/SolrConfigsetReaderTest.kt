package org.apache.solr.ide.repository

import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import org.apache.solr.ide.configset.SolrConfigsetDetector
import org.apache.solr.ide.configset.SolrConfigsetTestCase

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

    private fun givenConfigset(): org.apache.solr.ide.configset.SolrConfigset {
        myFixture.addFileToProject("core/conf/solrconfig.xml", config)
        val file = myFixture.addFileToProject("core/conf/managed-schema.xml", schema).virtualFile
        return SolrConfigsetDetector.configsetFor(project, file)!!
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
        reader.dropCache()

        val first = reader.modelFor(configset)
        val second = reader.modelFor(configset)

        assertSame("an unchanged configset must not be reparsed", first, second)
        assertEquals(1, reader.cacheSize)
    }

    /** The half of the criterion that costs performance if it is wrong. */
    fun testModelRebuildsWhenTheSchemaChanges() {
        val configset = givenConfigset()
        val before = reader.modelFor(configset)

        val schemaFile = configset.root.findChild("managed-schema.xml")!!
        WriteAction.runAndWait<RuntimeException> {
            val document = FileDocumentManager.getInstance().getDocument(schemaFile)!!
            document.setText(schema.replace("""<field name="name" type="string" indexed="true"/>""", ""))
        }

        val after = reader.modelFor(configset)
        assertNotSame(before, after)
        assertEquals(setOf("id"), after.fields.keys)
    }

    /** Editing an unrelated file must not invalidate the model, or every keystroke reparses. */
    fun testModelIsNotRebuiltWhenAnUnrelatedFileChanges() {
        val configset = givenConfigset()
        val before = reader.modelFor(configset)

        val unrelated = myFixture.addFileToProject("src/App.java", "class App {}").virtualFile
        WriteAction.runAndWait<RuntimeException> {
            FileDocumentManager.getInstance().getDocument(unrelated)!!.setText("class App { int x; }")
        }

        assertSame(before, reader.modelFor(configset))
    }

    /** Unsaved edits count: the plugin must not disagree with what the user is looking at. */
    fun testUnsavedEditsAreVisibleToTheModel() {
        val configset = givenConfigset()
        reader.modelFor(configset)

        val schemaFile = configset.root.findChild("managed-schema.xml")!!
        WriteAction.runAndWait<RuntimeException> {
            val document = FileDocumentManager.getInstance().getDocument(schemaFile)!!
            document.setText(schema.replace("<uniqueKey>id</uniqueKey>", """<field name="added" type="string"/><uniqueKey>id</uniqueKey>"""))
        }

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
