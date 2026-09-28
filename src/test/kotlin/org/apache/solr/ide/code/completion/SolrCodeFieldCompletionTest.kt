package org.apache.solr.ide.code.completion

import com.intellij.testFramework.DumbModeTestUtils
import com.intellij.util.ThreeState
import org.apache.solr.ide.code.SolrCodeFixtures
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
        SolrCodeFixtures.givenSolrJ(myFixture)
        SolrCodeFixtures.givenConfigsetDeclaring(myFixture, "id", "category")

        val offered = offeredIn("Search.java", searching("""q.addFilterQuery("<caret>");"""))

        assertContainsElements(offered, "id", "category")
    }

    /**
     * A dynamic pattern is not offered: `*_s` is not a name a query can use.
     *
     * Accepting it inserted `*_s` into the string, which in a query is a wildcard *field* Solr
     * rejects and in a field list is a glob rather than the field the user meant. A pattern stands for
     * names the user has to finish, so the honest offer is the names that exist; the one they are
     * about to create is theirs to type, and the inspection accepts it once typed.
     */
    fun testADynamicPatternIsNotOffered() {
        SolrCodeFixtures.givenSolrJ(myFixture)
        SolrCodeFixtures.givenConfigsetDeclaring(myFixture, "id", "category")

        assertDoesntContain(offeredIn("Search.java", searching("""q.addFilterQuery("<caret>");""")), "*_s")
    }

    /**
     * In a field position the popup holds fields and nothing else.
     *
     * The platform offers words and file paths inside any string, and they were arriving beside the
     * fields — `Search.java` and `org` offered as things a filter query might name.
     */
    fun testAFieldPositionOffersOnlyFields() {
        SolrCodeFixtures.givenSolrJ(myFixture)
        SolrCodeFixtures.givenConfigsetDeclaring(myFixture, "id", "category")

        val offered = offeredIn("Search.java", searching("""q.addFilterQuery("<caret>");"""))

        assertSameElements(offered, "id", "category")
    }

    /** The clause after an operator is a new field position. */
    fun testTheNextClauseOffersFieldsAgain() {
        SolrCodeFixtures.givenSolrJ(myFixture)
        SolrCodeFixtures.givenConfigsetDeclaring(myFixture, "id", "category")

        val offered = offeredIn("Search.java", searching("""q.setQuery("category:books AND <caret>");"""))

        assertContainsElements(offered, "id", "category")
    }

    /** The next name in a field list, after a comma, is offered like the first. */
    fun testTheNextNameInAFieldListIsOffered() {
        SolrCodeFixtures.givenSolrJ(myFixture)
        SolrCodeFixtures.givenConfigsetDeclaring(myFixture, "id", "category", "name")

        val offered = offeredIn("Search.java", searching("""q.setFields("id,<caret>");"""))

        assertContainsElements(offered, "category", "name")
    }

    // --- only what the position can use --------------------------------------------------------------

    /** A query is offered what can be searched, and not a field with neither an index nor doc values. */
    fun testAQueryOffersOnlySearchableFields() {
        SolrCodeFixtures.givenSolrJ(myFixture)
        SolrCodeFixtures.givenConfigsetOfCapabilities(myFixture)

        val offered = offeredIn("Search.java", searching("""q.setQuery("<caret>");"""))

        assertContainsElements(offered, "id", "sku", "title", "tags")
        assertDoesntContain(offered, "payload")
    }

    /** A filter query likewise: a stored-only field matches nothing in it. */
    fun testAFilterQueryOffersOnlySearchableFields() {
        SolrCodeFixtures.givenSolrJ(myFixture)
        SolrCodeFixtures.givenConfigsetOfCapabilities(myFixture)

        val offered = offeredIn("Search.java", searching("""q.addFilterQuery("<caret>");"""))

        assertContainsElements(offered, "id", "title")
        assertDoesntContain(offered, "payload")
    }

    /** A facet is offered what can be faceted: not analysed text without doc values. */
    fun testAFacetOffersOnlyFacetableFields() {
        SolrCodeFixtures.givenSolrJ(myFixture)
        SolrCodeFixtures.givenConfigsetOfCapabilities(myFixture)

        val offered = offeredIn("Search.java", searching("""q.addFacetField("<caret>");"""))

        assertContainsElements(offered, "id", "sku", "tags")
        assertDoesntContain(offered, "title", "payload")
    }

    /** A sort is offered what can be sorted: one value per document, and a structure to read it from. */
    fun testASortOffersOnlySortableFields() {
        SolrCodeFixtures.givenSolrJ(myFixture)
        SolrCodeFixtures.givenConfigsetOfCapabilities(myFixture)

        val offered = offeredIn("Search.java", searching("""q.setSort("<caret>", SolrQuery.ORDER.asc);"""))

        assertContainsElements(offered, "id", "sku")
        assertDoesntContain(offered, "title", "tags", "payload")
    }

    /** A field list asks nothing a schema can refuse, so every declared field is offered. */
    fun testAFieldListOffersEveryDeclaredField() {
        SolrCodeFixtures.givenSolrJ(myFixture)
        SolrCodeFixtures.givenConfigsetOfCapabilities(myFixture)

        val offered = offeredIn("Search.java", searching("""q.setFields("<caret>");"""))

        assertContainsElements(offered, "id", "sku", "title", "tags", "payload")
        assertDoesntContain(offered, "*_s")
    }

    fun testFieldNamesAreOfferedInABeanAnnotation() {
        SolrCodeFixtures.givenSolrJ(myFixture)
        SolrCodeFixtures.givenConfigsetDeclaring(myFixture, "id", "category")

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
        SolrCodeFixtures.givenSolrJ(myFixture)
        SolrCodeFixtures.givenConfigsetDeclaring(myFixture, "id", "category")

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

    /**
     * After a colon the caret is in a value, and no field is offered there.
     *
     * Offering one would complete `category:category`. The sandbox report asked for completion that
     * appears by itself; appearing in the value half of every clause is the failure that would turn
     * that request into a nuisance.
     */
    fun testAValueOffersNoFields() {
        SolrCodeFixtures.givenSolrJ(myFixture)
        SolrCodeFixtures.givenConfigsetDeclaring(myFixture, "id", "category")

        val offered = offeredIn("Search.java", searching("""q.setQuery("category:<caret>");"""))

        assertDoesntContain(offered, "id", "category")
    }

    /** The same in Kotlin, where the string is a template rather than a literal token. */
    fun testAValueOffersNoFieldsInKotlin() {
        SolrCodeFixtures.givenSolrJ(myFixture)
        SolrCodeFixtures.givenConfigsetDeclaring(myFixture, "id", "category")

        val offered = offeredIn(
            "Search.kt",
            """
            import org.apache.solr.client.solrj.SolrQuery
            fun go() {
                val q = SolrQuery("*:*")
                q.setQuery("category:bo<caret>")
            }
            """.trimIndent(),
        )

        assertDoesntContain(offered, "id", "category")
    }

    /** A sort's direction is the second argument, and is not a field. */
    fun testASortDirectionIsNotAField() {
        SolrCodeFixtures.givenSolrJ(myFixture)
        SolrCodeFixtures.givenConfigsetDeclaring(myFixture, "id", "category")

        val offered = offeredIn("Search.java", searching("""q.setSort("id", <caret>);"""))

        assertDoesntContain(offered, "id", "category")
    }

    /** A method that does not name fields is offered nothing, even on SolrJ's own class. */
    fun testAMethodThatNamesNoFieldsOffersNothing() {
        SolrCodeFixtures.givenSolrJ(myFixture)
        SolrCodeFixtures.givenConfigsetDeclaring(myFixture, "id", "category")

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
        SolrCodeFixtures.givenSolrJ(myFixture)
        SolrCodeFixtures.givenConfigsetDeclaring(myFixture, "id", "category")
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

    /**
     * While indexing, the auto-popup rule says nothing rather than throwing on a keystroke.
     *
     * Deciding that a call is SolrJ's resolves its receiver, which reads an index. The rule is asked
     * on every letter typed into every string, so an unguarded read here would fail typing itself.
     */
    fun testTheAutoPopupRuleIsSilentWhileIndexing() {
        SolrCodeFixtures.givenSolrJ(myFixture)
        SolrCodeFixtures.givenConfigsetDeclaring(myFixture, "id", "category")
        myFixture.configureByText("Search.java", searching("""q.addFilterQuery("c<caret>");"""))
        val offset = myFixture.caretOffset
        val element = myFixture.file.findElementAt(offset - 1)!!
        val rule = SolrCodeFieldCompletionConfidence()

        assertEquals(ThreeState.NO, rule.shouldSkipAutopopup(myFixture.editor, element, myFixture.file, offset))
        DumbModeTestUtils.runInDumbModeSynchronously(project) {
            assertEquals(ThreeState.UNSURE, rule.shouldSkipAutopopup(myFixture.editor, element, myFixture.file, offset))
        }
    }

    /** A module with no Solr client is offered nothing at all. */
    fun testAModuleWithoutASolrClientOffersNothing() {
        SolrCodeFixtures.givenSolrJ(myFixture)
        SolrCodeFixtures.givenConfigsetDeclaring(myFixture, "id", "category")
        givenNoSolrOnTheClasspath()

        val offered = offeredIn("Search.java", searching("""q.addFilterQuery("<caret>");"""))

        assertDoesntContain(offered, "id", "category")
    }
}
