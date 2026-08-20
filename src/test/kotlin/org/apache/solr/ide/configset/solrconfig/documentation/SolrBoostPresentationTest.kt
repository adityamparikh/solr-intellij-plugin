package org.apache.solr.ide.configset.solrconfig.documentation

import org.apache.solr.ide.configset.solrconfig.parsing.SolrConfigParser.SolrBoostOccurrence
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The boost popup's HTML, exercised as the pure function it is.
 *
 * No fixture: the presentation is a function of an occurrence and a resolved field, which is the
 * same separation the parameter and parser-name popups already have from their provider.
 */
class SolrBoostPresentationTest {

    private fun boost(parameter: String, boost: String, field: String? = "name") =
        SolrBoostOccurrence(parameterName = parameter, boost = boost, fieldName = field)

    @Test
    fun `a qf boost says it scales the term match, and names the field`() {
        val html = SolrConfigPresentation.boostDocumentation(
            boost("qf", "3"),
            SolrConfigPresentation.BoostedField("name", searchable = true),
        )

        assertTrue(html, "^3" in html)
        assertTrue(html, "term match" in html)
        assertTrue(html, "name" in html)
    }

    @Test
    fun `each phrase parameter says which phrase it scales`() {
        val pf = SolrConfigPresentation.boostDocumentation(boost("pf", "2"), null)
        val pf2 = SolrConfigPresentation.boostDocumentation(boost("pf2", "2"), null)
        val pf3 = SolrConfigPresentation.boostDocumentation(boost("pf3", "2"), null)

        assertTrue(pf, "phrase match" in pf)
        assertTrue(pf2, "bigram" in pf2)
        assertTrue(pf3, "trigram" in pf3)
    }

    @Test
    fun `bf is additive and boost is multiplicative, and they say so differently`() {
        val bf = SolrConfigPresentation.boostDocumentation(boost("bf", "2", field = null), null)
        val multiplied = SolrConfigPresentation.boostDocumentation(boost("boost", "2", field = null), null)

        assertTrue(bf, "added" in bf)
        assertTrue(multiplied, "multiplied" in multiplied)
        // The distinction is the point: if these ever render the same sentence, one is wrong.
        assertNotEquals(bf, multiplied)
    }

    @Test
    fun `a boost of one is described as changing nothing`() {
        val html = SolrConfigPresentation.boostDocumentation(boost("qf", "1"), null)

        assertTrue(html, "changes nothing" in html)
    }

    @Test
    fun `a boost of one written as a decimal is the same default`() {
        // `^1.0` is the same number, and a reader who wrote it deserves the same answer.
        val html = SolrConfigPresentation.boostDocumentation(boost("qf", "1.0"), null)

        assertTrue(html, "changes nothing" in html)
    }

    @Test
    fun `an ordinary boost is not described as changing nothing`() {
        val html = SolrConfigPresentation.boostDocumentation(boost("qf", "3"), null)

        assertFalse(html, "changes nothing" in html)
    }

    @Test
    fun `a boost that is not a number says Solr rejects the query`() {
        val html = SolrConfigPresentation.boostDocumentation(boost("qf", "abc"), null)

        assertTrue(html, "rejects" in html)
    }

    @Test
    fun `a marker with nothing after it says a number belongs there`() {
        val html = SolrConfigPresentation.boostDocumentation(boost("qf", ""), null)

        assertTrue(html, "expects a number" in html)
    }

    @Test
    fun `a field Solr cannot search says there is nothing to scale`() {
        val html = SolrConfigPresentation.boostDocumentation(
            boost("qf", "3"),
            SolrConfigPresentation.BoostedField("name", searchable = false),
        )

        assertTrue(html, "nothing for the boost to scale" in html)
    }

    @Test
    fun `an undetermined field is named without a searchability claim`() {
        // The same silence the match hint keeps for a property that resolves to UNDETERMINED: the
        // field is still named, and nothing is asserted about it.
        val html = SolrConfigPresentation.boostDocumentation(
            boost("qf", "3"),
            SolrConfigPresentation.BoostedField("name", searchable = null),
        )

        assertTrue(html, "name" in html)
        assertFalse(html, "searchable" in html)
    }

    @Test
    fun `no resolved field means no field line at all`() {
        val html = SolrConfigPresentation.boostDocumentation(boost("bf", "2", field = null), null)

        assertFalse(html, "Boosts" in html)
    }

    @Test
    fun `a field name carrying markup is escaped`() {
        // The name comes out of the file being edited, so it is not trusted to be free of `<`.
        val html = SolrConfigPresentation.boostDocumentation(
            boost("qf", "3"),
            SolrConfigPresentation.BoostedField("<b>name", searchable = true),
        )

        assertFalse(html, "<b>name" in html)
    }
}
