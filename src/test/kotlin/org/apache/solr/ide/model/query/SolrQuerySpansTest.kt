package org.apache.solr.ide.model.query

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What a query is made of, at the resolution a highlighter needs.
 *
 * **Plain JUnit over strings, because that is what this is.** Deciding which characters of
 * `category:books AND price:[1 TO 9]` are a field and which are an operator needs no IDE, and the
 * rules are where every mistake in this feature will live.
 *
 * **Precision over recall throughout, and here that reads as: uncoloured beats mis-coloured.** A
 * highlighter is a claim about what the text means, made without being asked. Colouring a word as an
 * operator when Solr reads it as a term tells a reader their query does something it does not, which
 * is worse than telling them nothing.
 */
class SolrQuerySpansTest {

    private fun spans(query: String) =
        SolrQueryExpressions.spansIn(query).map { "${it.kind}:${query.substring(it.start, it.end)}" }

    private fun ranges(query: String) = SolrQueryExpressions.spansIn(query).map { it.start to it.end }

    // --- fields -------------------------------------------------------------------------------------

    @Test
    fun `a fielded clause names its field`() {
        assertEquals(listOf("FIELD:category"), spans("category:books"))
    }

    @Test
    fun `each clause names its own field`() {
        assertEquals(listOf("FIELD:category", "OPERATOR:AND", "FIELD:price"), spans("category:books AND price:9"))
    }

    @Test
    fun `a span covers exactly the name it reports`() {
        val query = "   category:books"
        val span = SolrQueryExpressions.spansIn(query).first()

        assertEquals("category", query.substring(span.start, span.end))
    }

    // --- operators ----------------------------------------------------------------------------------

    @Test
    fun `the boolean operators are recognized`() {
        assertEquals(listOf("OPERATOR:AND", "OPERATOR:OR", "OPERATOR:NOT"), spans("a AND b OR c NOT d"))
    }

    @Test
    fun `the symbolic forms are recognized`() {
        assertEquals(listOf("OPERATOR:&&", "OPERATOR:||"), spans("a && b || c"))
    }

    /** `TO` joins the ends of a range, which reads as an operator whatever the grammar calls it. */
    @Test
    fun `a range join is an operator`() {
        assertTrue(spans("price:[1 TO 9]").contains("OPERATOR:TO"))
    }

    /**
     * A lowercase `and` is a term, not an operator.
     *
     * Solr reads it as a word to search for. Colouring it would tell a reader their query combines
     * two clauses when it searches for three.
     */
    @Test
    fun `a lowercase and is left alone`() {
        assertEquals(emptyList<String>(), spans("a and b"))
    }

    /**
     * A hyphen inside a name is part of the name.
     *
     * **Verified against Solr rather than reasoned about.** `some-field:books` comes back as
     * *undefined field some-field* — the whole token is the field name. `-` and `+` are operators
     * only where a clause begins, which is why `-id:1` parses as a prohibited clause on `id` and
     * `a+b:c` comes back as *undefined field a+b*.
     */
    @Test
    fun `a hyphen inside a name is part of the name`() {
        assertEquals(listOf("FIELD:some-field"), spans("some-field:books"))
    }

    /** A leading minus prohibits the clause, and is not part of the name after it. */
    @Test
    fun `a leading minus is not part of the name`() {
        assertEquals(listOf("FIELD:category"), spans("-category:books"))
    }

    /** A leading plus requires the clause, and is likewise not part of the name. */
    @Test
    fun `a leading plus is not part of the name`() {
        assertEquals(listOf("FIELD:category"), spans("+category:books"))
    }

    /** A plus inside a token is part of it, as Solr reads `a+b:c` as the field `a+b`. */
    @Test
    fun `a plus inside a name is part of the name`() {
        assertEquals(listOf("FIELD:a+b"), spans("a+b:c"))
    }

    // --- what it will not read ----------------------------------------------------------------------

    /** Nothing inside a phrase is anything: it is a value in its entirety. */
    @Test
    fun `a phrase hides what is inside it`() {
        assertEquals(listOf("FIELD:title"), spans("""title:"books AND magazines""""))
    }

    /** A local-parameter block declares parameters, and its names are not fields. */
    @Test
    fun `a local parameter block names no field`() {
        assertEquals(emptyList<String>(), spans("{!edismax qf=title}"))
    }

    @Test
    fun `the match-all query names nothing`() {
        assertEquals(emptyList<String>(), spans("*:*"))
    }

    @Test
    fun `an empty query has no spans`() {
        assertEquals(emptyList<String>(), spans(""))
    }

    // --- the two readings agree ---------------------------------------------------------------------

    /**
     * Every field the names reader reports is a field span, and the reverse.
     *
     * The two answers come from one scan precisely so they cannot disagree; this is what holds that
     * to more than intent. A name reported by the check but not coloured — or coloured and not
     * checked — is the disagreement a user would see.
     */
    @Test
    fun `the spans and the names agree`() {
        val query = """category:books AND title:"a AND b" NOT price:[1 TO 9]"""

        val fromSpans = SolrQueryExpressions.spansIn(query)
            .filter { it.kind == SolrQuerySpanKind.FIELD }
            .map { query.substring(it.start, it.end) }

        assertEquals(SolrQueryExpressions.fieldNamesIn(query), fromSpans)
    }
}
