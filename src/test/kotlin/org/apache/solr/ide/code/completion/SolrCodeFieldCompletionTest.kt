package org.apache.solr.ide.code.completion

import org.apache.solr.ide.configset.activation.SolrConfigsetTestCase

/**
 * Offering field names where Java or Kotlin code is naming one.
 *
 * **Registered against no language, so every Java and Kotlin file in every project reaches this.**
 * That makes the declining cases the substance of the test: offering Solr field names inside an
 * unrelated method call is the same failure as an inspection firing on a correct file, and it is
 * more visible, because a completion popup interrupts someone mid-keystroke.
 */
class SolrCodeFieldCompletionTest : SolrConfigsetTestCase() {

    private fun givenAConfigsetDeclaring(vararg fields: String) {
        val lines = buildList {
            add("""<?xml version="1.0" encoding="UTF-8"?>""")
            add("""<schema name="test" version="1.6">""")
            add("""  <fieldType name="string" class="solr.StrField"/>""")
            fields.forEach { add("""  <field name="$it" type="string" indexed="true" stored="true"/>""") }
            add("""  <dynamicField name="*_s" type="string" indexed="true" stored="true"/>""")
            add("  <uniqueKey>id</uniqueKey>")
            add("</schema>")
        }
        myFixture.addFileToProject("solr/conf/managed-schema.xml", lines.joinToString("\n"))
    }

    private fun givenSolrJ() {
        myFixture.addFileToProject(
            "org/apache/solr/client/solrj/SolrQuery.java",
            """
            package org.apache.solr.client.solrj;
            public class SolrQuery {
                public SolrQuery(String q) {}
                public SolrQuery addFilterQuery(String... fq) { return this; }
                public SolrQuery setRows(Integer rows) { return this; }
            }
            """.trimIndent(),
        )
        myFixture.addFileToProject(
            "org/apache/solr/client/solrj/beans/Field.java",
            """
            package org.apache.solr.client.solrj.beans;
            public @interface Field { String value() default "#default"; }
            """.trimIndent(),
        )
    }

    private fun offeredIn(name: String, text: String): List<String> {
        myFixture.configureByText(name, text)
        myFixture.completeBasic()
        return myFixture.lookupElementStrings.orEmpty()
    }

    private fun searching(body: String) = """
        import org.apache.solr.client.solrj.SolrQuery;
        class Search {
            void go() {
                SolrQuery q = new SolrQuery("*:*");
                $body
            }
        }
    """.trimIndent()

    // --- where it offers ----------------------------------------------------------------------------

    fun testFieldNamesAreOfferedInAQueryCall() {
        givenSolrJ()
        givenAConfigsetDeclaring("id", "category")

        val offered = offeredIn("Search.java", searching("""q.addFilterQuery("<caret>");"""))

        assertContainsElements(offered, "id", "category")
    }

    /** A dynamic pattern is offered too, because naming one is how a user names the field it makes. */
    fun testADynamicPatternIsOffered() {
        givenSolrJ()
        givenAConfigsetDeclaring("id")

        assertContainsElements(offeredIn("Search.java", searching("""q.addFilterQuery("<caret>");""")), "*_s")
    }

    fun testFieldNamesAreOfferedInABeanAnnotation() {
        givenSolrJ()
        givenAConfigsetDeclaring("id", "category")

        val offered = offeredIn(
            "Product.java",
            """
            import org.apache.solr.client.solrj.beans.Field;
            class Product {
                @Field("<caret>") int price;
            }
            """.trimIndent(),
        )

        assertContainsElements(offered, "id", "category")
    }

    /** Kotlin reaches the same contributor, through the same position check. */
    fun testKotlinIsOfferedTheSameNames() {
        givenSolrJ()
        givenAConfigsetDeclaring("id", "category")

        val offered = offeredIn(
            "Search.kt",
            """
            import org.apache.solr.client.solrj.SolrQuery
            fun go() {
                val q = SolrQuery("*:*")
                q.addFilterQuery("<caret>")
            }
            """.trimIndent(),
        )

        assertContainsElements(offered, "id", "category")
    }

    // --- where it stays quiet -----------------------------------------------------------------------

    /** A method that does not name fields is offered nothing, even on SolrJ's own class. */
    fun testAMethodThatNamesNoFieldsOffersNothing() {
        givenSolrJ()
        givenAConfigsetDeclaring("id", "category")

        val offered = offeredIn("Search.java", searching("""q.setRows(1); String s = "<caret>";"""))

        assertDoesntContain(offered, "id", "category")
    }

    /**
     * Somebody else's `@Field` is offered nothing.
     *
     * The name is among the most reused on the JVM, so matching it loosely would pop a Solr field
     * list open inside every JPA entity in the project.
     */
    fun testAnUnrelatedFieldAnnotationOffersNothing() {
        givenSolrJ()
        givenAConfigsetDeclaring("id", "category")
        myFixture.addFileToProject(
            "jakarta/persistence/Field.java",
            """
            package jakarta.persistence;
            public @interface Field { String value() default ""; }
            """.trimIndent(),
        )

        val offered = offeredIn(
            "Entity.java",
            """
            import jakarta.persistence.Field;
            class Entity {
                @Field("<caret>") int price;
            }
            """.trimIndent(),
        )

        assertDoesntContain(offered, "id", "category")
    }

    /** A module with no Solr client is offered nothing at all. */
    fun testAModuleWithoutASolrClientOffersNothing() {
        givenSolrJ()
        givenAConfigsetDeclaring("id", "category")
        givenNoSolrOnTheClasspath()

        val offered = offeredIn("Search.java", searching("""q.addFilterQuery("<caret>");"""))

        assertDoesntContain(offered, "id", "category")
    }
}
