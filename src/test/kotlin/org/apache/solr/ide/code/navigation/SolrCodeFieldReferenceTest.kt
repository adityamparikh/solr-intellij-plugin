package org.apache.solr.ide.code.navigation

import com.intellij.psi.PsiFile
import com.intellij.psi.xml.XmlAttributeValue
import org.apache.solr.ide.code.SolrCodeFixtures
import org.apache.solr.ide.configset.activation.SolrConfigsetTestCase

/**
 * Ctrl-clicking a Solr field name in Java or Kotlin, and landing on the schema that declares it.
 *
 * **The boundary nothing else in the IDE crosses.** A field name in code and its declaration in a
 * configset are related by convention and by nothing the tooling can see: one is a string in a
 * `.java` file, the other an attribute in an XML file the Java file has never heard of.
 *
 * The silences below are the same silences the inspection keeps, and deliberately so — they are read
 * from the same recognizer. A reference that resolved where the check declines, or the reverse,
 * would be two answers about one name.
 */
class SolrCodeFieldReferenceTest : SolrConfigsetTestCase() {

    private fun searching(body: String) = """
        import org.apache.solr.client.solrj.SolrQuery;
        class Search {
            void go() {
                SolrQuery q = new SolrQuery("*:*");
                $body
            }
        }
    """.trimIndent()

    /** The declaration the caret resolves to, or null where it resolves to nothing. */
    private fun declarationAt(file: PsiFile, name: String): String? {
        val offset = file.text.indexOf(name)
        val reference = file.findReferenceAt(offset)
        return (reference?.resolve() as? XmlAttributeValue)?.value
    }

    private fun configured(text: String, vararg fields: String): PsiFile {
        SolrCodeFixtures.givenSolrJ(myFixture)
        SolrCodeFixtures.givenConfigsetDeclaring(myFixture, *fields)
        return myFixture.addFileToProject("src/Search.java", text)
    }

    // --- where it resolves --------------------------------------------------------------------------

    fun testAFieldNameInAFilterQueryResolvesToItsDeclaration() {
        val file = configured(searching("""q.addFilterQuery("category:books");"""), "id", "category")

        assertEquals("category", declarationAt(file, "category"))
    }

    fun testAFieldNameInAFieldListResolvesToo() {
        val file = configured(searching("""q.setFields("id", "category");"""), "id", "category")

        assertEquals("category", declarationAt(file, "category"))
    }

    /** A bean binding resolves from the annotation, as it is read there. */
    fun testABeanFieldResolves() {
        SolrCodeFixtures.givenSolrJ(myFixture)
        SolrCodeFixtures.givenConfigsetDeclaring(myFixture, "id", "price")
        val file = myFixture.addFileToProject(
            "src/Product.java",
            """
            import org.apache.solr.client.solrj.beans.Field;
            class Product {
                @Field("price") int price;
            }
            """.trimIndent(),
        )

        assertEquals("price", declarationAt(file, "price"))
    }

    /** Kotlin resolves through the same contributor, from the same reading of the file. */
    fun testKotlinResolvesTheSameWay() {
        SolrCodeFixtures.givenSolrJ(myFixture)
        SolrCodeFixtures.givenConfigsetDeclaring(myFixture, "id", "category")
        val file = myFixture.addFileToProject(
            "src/Search.kt",
            """
            import org.apache.solr.client.solrj.SolrQuery
            fun go() {
                val q = SolrQuery("*:*")
                q.addFilterQuery("category:books")
            }
            """.trimIndent(),
        )

        assertEquals("category", declarationAt(file, "category"))
    }

    // --- where it stays quiet -----------------------------------------------------------------------

    /**
     * A name no configset declares resolves to nothing rather than to something near it.
     *
     * The inspection reports this name; the reference declining is the other half of one answer.
     */
    fun testAnUndeclaredNameResolvesToNothing() {
        val file = configured(searching("""q.addFilterQuery("categry:books");"""), "id", "category")

        assertNull(declarationAt(file, "categry"))
    }

    /** With no Solr client on the module nothing is read, so nothing resolves. */
    fun testAModuleWithoutASolrClientResolvesNothing() {
        val file = configured(searching("""q.addFilterQuery("category:books");"""), "id", "category")
        givenNoSolrOnTheClasspath()

        assertNull(declarationAt(file, "category"))
    }

    /**
     * An ordinary string that names no field resolves to nothing.
     *
     * The contributor is offered every PSI element in the project, so this is the case that decides
     * whether it is quiet everywhere it should be.
     */
    fun testAnUnrelatedStringResolvesNothing() {
        val file = configured(searching("""String s = "category";"""), "id", "category")

        assertNull(declarationAt(file, "\"category\""))
    }
}
