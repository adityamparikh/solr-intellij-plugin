package org.apache.solr.ide.model.query

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Field names read out of a query expression, which is a different grammar from a field list.
 *
 * `fl` and `qf` hold names; `q` and `fq` hold *queries*, where a name appears only as the part
 * before a colon. [SolrQueryFields] answers about the first kind and says `false` to the second, so
 * nothing in the plugin could read `categry:books` until this existed — which is the demo's planted
 * defect and the reason the Java recognizer needs it.
 *
 * **Precision over recall throughout.** Query syntax is full of things that resemble a field
 * reference, and a false positive is a warning on somebody's working code. Every case below that
 * expects nothing is expecting nothing deliberately.
 */
class SolrQueryExpressionsTest {

    @Test
    fun `a fielded clause names its field`() {
        assertEquals(listOf("categry"), SolrQueryExpressions.fieldNamesIn("categry:books"))
    }

    @Test
    fun `several clauses name several fields`() {
        assertEquals(
            listOf("name", "category"),
            SolrQueryExpressions.fieldNamesIn("name:widget AND category:tools"),
        )
    }

    /** `*:*` is the match-all query, and `*` is not a field anyone declared. */
    @Test
    fun `the match-all query names no field`() {
        assertEquals(emptyList<String>(), SolrQueryExpressions.fieldNamesIn("*:*"))
    }

    /** A bare term has no field at all; it searches whatever `df` names. */
    @Test
    fun `a bare term names no field`() {
        assertEquals(emptyList<String>(), SolrQueryExpressions.fieldNamesIn("widget"))
    }

    /** A range is still a fielded clause. */
    @Test
    fun `a range clause names its field`() {
        assertEquals(listOf("price"), SolrQueryExpressions.fieldNamesIn("price:[1 TO 10]"))
    }

    /** A quoted phrase is a value, and a colon inside it is not a field boundary. */
    @Test
    fun `a colon inside a quoted phrase is not a field`() {
        assertEquals(listOf("name"), SolrQueryExpressions.fieldNamesIn("""name:"time: a history""""))
    }

    /**
     * Local parameters are the case most likely to produce a wrong warning.
     *
     * `{!edismax qf=name}` is a parser declaration, not a fielded clause, and `!edismax` is not a
     * field. The names inside it are parameters, which is a different grammar again — reading them
     * here would report `qf` as a field.
     */
    @Test
    fun `a local parameter block names no field`() {
        assertEquals(emptyList<String>(), SolrQueryExpressions.fieldNamesIn("{!edismax qf=name}widget"))
    }

    /** A field reached through a local parameter block is still read once the block ends. */
    @Test
    fun `a clause after a local parameter block is read`() {
        assertEquals(listOf("category"), SolrQueryExpressions.fieldNamesIn("{!lucene}category:tools"))
    }

    /** A boost is not part of the name. */
    @Test
    fun `a boosted clause names the field without its boost`() {
        assertEquals(listOf("name"), SolrQueryExpressions.fieldNamesIn("name:widget^3"))
    }

    /** A function query names no field this can be sure of, so it names none. */
    @Test
    fun `a function query names no field`() {
        assertEquals(emptyList<String>(), SolrQueryExpressions.fieldNamesIn("{!func}max(price,0)"))
    }

    /** A parameter reference is resolved by Solr at request time and is not a name here. */
    @Test
    fun `a parameter reference names no field`() {
        assertEquals(emptyList<String>(), SolrQueryExpressions.fieldNamesIn("\$myfield:widget"))
    }

    @Test
    fun `an empty query names no field`() {
        assertEquals(emptyList<String>(), SolrQueryExpressions.fieldNamesIn(""))
    }
}
