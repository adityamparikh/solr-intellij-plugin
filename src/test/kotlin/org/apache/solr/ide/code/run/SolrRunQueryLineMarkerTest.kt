package org.apache.solr.ide.code.run

import org.apache.solr.ide.code.SolrCodeFixtures
import org.apache.solr.ide.configset.activation.SolrConfigsetTestCase

/**
 * Where the gutter icon offering to run a query appears, and where it does not.
 *
 * **The icon is the whole promise, so where it appears is the whole test.** Clicking it opens
 * popups and talks to a server, neither of which a headless fixture can drive; what a test can hold
 * is the claim the icon makes by existing — *there is a Solr query here, and this plugin can run
 * it*. An icon on a string that is not a query is that claim being false.
 *
 * The provider is registered without a language, so every leaf of every file the IDE opens is
 * offered to it. That makes the silences below the substance rather than the trimming.
 */
class SolrRunQueryLineMarkerTest : SolrConfigsetTestCase() {

    private fun markersIn(name: String, text: String): List<String> {
        myFixture.configureByText(name, text)
        return myFixture.findAllGutters().mapNotNull { it.tooltipText }
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

    fun testAQueryCarriesTheIcon() {
        SolrCodeFixtures.givenSolrJ(myFixture)

        val tooltips = markersIn("Search.java", searching("""q.setQuery("category:books");"""))

        assertTrue(tooltips.toString(), tooltips.any { it.contains("category:books") })
    }

    /** Kotlin gets the same icon, from the same provider. */
    fun testKotlinCarriesTheIconToo() {
        SolrCodeFixtures.givenSolrJ(myFixture)

        val tooltips = markersIn(
            "Search.kt",
            """
            import org.apache.solr.client.solrj.SolrQuery
            fun go() {
                val q = SolrQuery("*:*")
                q.setQuery("category:books")
            }
            """.trimIndent(),
        )

        assertTrue(tooltips.toString(), tooltips.any { it.contains("category:books") })
    }

    // --- where it stays quiet -----------------------------------------------------------------------

    /**
     * A filter query carries no icon, though it is a query and is read as one.
     *
     * Running an `fq` alone answers a different question from the one the code asks: a filter
     * narrows a result set and scores nothing, so its matches are not what the program would see.
     */
    fun testAFilterQueryCarriesNoIcon() {
        SolrCodeFixtures.givenSolrJ(myFixture)

        val tooltips = markersIn("Search.java", searching("""q.addFilterQuery("category:books");"""))

        assertFalse(tooltips.toString(), tooltips.any { it.contains("category:books") })
    }

    /** A field list is not a query, and carries nothing. */
    fun testAFieldListCarriesNoIcon() {
        SolrCodeFixtures.givenSolrJ(myFixture)

        val tooltips = markersIn("Search.java", searching("""q.setFields("id", "category");"""))

        assertFalse(tooltips.toString(), tooltips.any { it.contains("category") })
    }

    /**
     * A module with no Solr client carries nothing, however the string is spelled.
     *
     * The provider runs on every leaf of every file in the IDE, so this is the case that decides
     * whether it is quiet in the projects that have never heard of Solr — which is most of them.
     */
    fun testAModuleWithoutASolrClientCarriesNoIcon() {
        SolrCodeFixtures.givenSolrJ(myFixture)
        givenNoSolrOnTheClasspath()

        val tooltips = markersIn("Search.java", searching("""q.setQuery("category:books");"""))

        assertFalse(tooltips.toString(), tooltips.any { it.contains("category:books") })
    }

    /** A query the source does not spell out carries nothing, as the recognizer declines it. */
    fun testAComputedQueryCarriesNoIcon() {
        SolrCodeFixtures.givenSolrJ(myFixture)

        val tooltips = markersIn("Search.java", searching("""q.setQuery(q.toString());"""))

        assertEmpty(tooltips.filter { it.contains("Solr", ignoreCase = true) })
    }
}
