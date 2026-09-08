package org.apache.solr.ide.code.highlighting

import com.intellij.openapi.editor.DefaultLanguageHighlighterColors
import org.apache.solr.ide.code.SolrCodeFixtures
import org.apache.solr.ide.configset.activation.SolrConfigsetTestCase

/**
 * Colour inside a Solr query written in Java or Kotlin.
 *
 * **What is asserted is which characters get coloured, not what colour they get.** The keys come
 * from the platform's palette, so the actual colour is the reader's theme; what this plugin decides
 * is where the boundaries fall — and a boundary off by one is a highlight that covers half a word.
 *
 * An annotator runs on every element of every file the editor shows, so the silences here are the
 * substance. Colour is a claim about what text means, made without being asked.
 */
class SolrQueryHighlightTest : SolrConfigsetTestCase() {

    /** The text of every range this plugin coloured, by the key it used. */
    private fun colouredIn(name: String, text: String): List<String> {
        myFixture.configureByText(name, text)
        myFixture.doHighlighting()
        val document = myFixture.editor.document.charsSequence
        // The document's markup model, not the editor's: annotations produced by the daemon land in
        // the former, and reading the latter finds nothing however correct the annotator is.
        return com.intellij.openapi.editor.impl.DocumentMarkupModel
            .forDocument(myFixture.editor.document, project, false)
            .allHighlighters
            .filter { it.textAttributesKey == DefaultLanguageHighlighterColors.KEYWORD ||
                it.textAttributesKey == DefaultLanguageHighlighterColors.OPERATION_SIGN }
            .sortedBy { it.startOffset }
            .map { document.subSequence(it.startOffset, it.endOffset).toString() }
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

    // --- what it colours ----------------------------------------------------------------------------

    fun testTheFieldAndTheOperatorAreColoured() {
        SolrCodeFixtures.givenSolrJ(myFixture)

        val coloured = colouredIn("Search.java", searching("""q.setQuery("category:books AND price:9");"""))

        assertEquals(listOf("category", "AND", "price"), coloured)
    }

    /** A filter query is a query, and is coloured as one. */
    fun testAFilterQueryIsColouredToo() {
        SolrCodeFixtures.givenSolrJ(myFixture)

        val coloured = colouredIn("Search.java", searching("""q.addFilterQuery("category:books");"""))

        assertEquals(listOf("category"), coloured)
    }

    /** Kotlin is coloured by the same annotator, through the same scan. */
    fun testKotlinIsColouredTheSameWay() {
        SolrCodeFixtures.givenSolrJ(myFixture)

        val coloured = colouredIn(
            "Search.kt",
            """
            import org.apache.solr.client.solrj.SolrQuery
            fun go() {
                val q = SolrQuery("*:*")
                q.setQuery("category:books AND price:9")
            }
            """.trimIndent(),
        )

        assertEquals(listOf("category", "AND", "price"), coloured)
    }

    // --- what it leaves alone -----------------------------------------------------------------------

    /**
     * A field list is not a query, and nothing in it is coloured.
     *
     * `fl` holds names, not clauses. Colouring them as fields would be true of the names and false
     * of the structure — there are no clauses and no operators there to see.
     */
    fun testAFieldListIsNotColoured() {
        SolrCodeFixtures.givenSolrJ(myFixture)

        assertEmpty(colouredIn("Search.java", searching("""q.setFields("id,category");""")))
    }

    /** An ordinary string that is no argument of a Solr call is left alone. */
    fun testAnUnrelatedStringIsNotColoured() {
        SolrCodeFixtures.givenSolrJ(myFixture)

        assertEmpty(colouredIn("Search.java", searching("""int n = 1; q.setRows(n);""")))
    }

    /**
     * A module with no Solr client is not coloured, however the string is spelled.
     *
     * The annotator is offered every element of every file in the IDE, so this is the case that
     * decides whether it is quiet in the projects that have never heard of Solr.
     */
    fun testAModuleWithoutASolrClientIsNotColoured() {
        SolrCodeFixtures.givenSolrJ(myFixture)
        givenNoSolrOnTheClasspath()

        assertEmpty(colouredIn("Search.java", searching("""q.setQuery("category:books AND price:9");""")))
    }
}
