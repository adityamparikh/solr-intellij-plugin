package org.apache.solr.ide.code.solrj

import com.intellij.psi.PsiFile
import org.apache.solr.ide.code.SolrRecognizers
import org.apache.solr.ide.configset.activation.SolrConfigsetTestCase

/**
 * Reading field names out of the document a client builds before sending it.
 *
 * **The third place a field name is written, and the one furthest from a query.** Querying a field
 * that does not exist returns nothing; *indexing* into one that does not exist is worse, because a
 * collection running the default update chain will add it to the deployed schema rather than
 * refusing the document. Solr answers `status: 0`, the document is stored under a field nobody
 * declared, and the configset in the repository and the schema on the server have silently diverged.
 *
 * `SolrInputDocument` carries a method named `addField` and so does `SolrQuery`, which is why the
 * receiver is checked rather than the method name: the two mean different things and are read
 * differently — a query's `addField` names something to return, a document's names something to
 * store.
 */
class SolrJDocumentFieldTest : SolrConfigsetTestCase() {

    private fun givenSolrJ() {
        myFixture.addFileToProject(
            "org/apache/solr/common/SolrInputDocument.java",
            """
            package org.apache.solr.common;
            public class SolrInputDocument {
                public void addField(String name, Object value) {}
                public void setField(String name, Object value) {}
                public Object getFieldValue(String name) { return null; }
            }
            """.trimIndent(),
        )
    }

    private fun javaFile(body: String): PsiFile =
        myFixture.addFileToProject(
            "src/Indexer.java",
            """
            import org.apache.solr.common.SolrInputDocument;
            class Indexer {
                void go() {
                    SolrInputDocument doc = new SolrInputDocument();
                    $body
                }
            }
            """.trimIndent(),
        )

    private fun usages(file: PsiFile) = SolrRecognizers.fieldUsagesIn(file)

    fun testAddFieldNamesItsFirstArgument() {
        givenSolrJ()

        assertEquals(listOf("prce"), usages(javaFile("""doc.addField("prce", 9.99);""")).map { it.fieldName })
    }

    fun testSetFieldNamesItsFirstArgumentToo() {
        givenSolrJ()

        assertEquals(listOf("categry"), usages(javaFile("""doc.setField("categry", "books");""")).map { it.fieldName })
    }

    /**
     * The value is not read, only the name.
     *
     * A document's second argument is data. Reading it would report every string an application
     * indexes as a field name, which is most of the strings it has.
     */
    fun testTheValueIsNotReadAsAField() {
        givenSolrJ()

        val found = usages(javaFile("""doc.addField("id", "category");"""))

        assertEquals(listOf("id"), found.map { it.fieldName })
    }

    /** A method on the document that names no field is not read. */
    fun testAnUnmappedDocumentMethodIsNotRead() {
        givenSolrJ()

        assertEmpty(usages(javaFile("""doc.getFieldValue("id");""")))
    }

    /** The same call in Kotlin reads the same, through the same recognizer. */
    fun testKotlinReadsTheSameName() {
        givenSolrJ()

        val found = usages(
            myFixture.addFileToProject(
                "src/Indexer.kt",
                """
                import org.apache.solr.common.SolrInputDocument
                fun go() {
                    val doc = SolrInputDocument()
                    doc.addField("prce", 9.99)
                }
                """.trimIndent(),
            ),
        )

        assertEquals(listOf("prce"), found.map { it.fieldName })
    }

    /** A name the source does not spell out is not read, as everywhere else in this recognizer. */
    fun testAComputedNameIsNotRead() {
        givenSolrJ()

        assertEmpty(usages(javaFile("""doc.addField(name(), 1);""")))
    }
}
