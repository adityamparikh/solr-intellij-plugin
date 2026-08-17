package org.apache.solr.ide.model.vocabulary

import org.apache.solr.ide.model.SolrVersionSelection
import org.apache.solr.ide.model.SolrVersionSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which line answers, across all three catalogs, and what the selection says about how it was decided.
 *
 * **Three objects implement the same rule and none of them shares the code.** [SolrClassCatalog],
 * [SolrParameterCatalog] and [SolrElementCatalog] each carry their own `lineFor`, and two of them their
 * own list of supported lines — so "the declared line, else the newest" is a rule stated three times
 * and enforced nowhere. A copy that drifted would not fail: it would answer, from the wrong line,
 * about a Solr the reader is not running. That is the same failure the Reference Guide segment already
 * shipped once, and the reason this asks all three the same questions rather than one of them.
 *
 * **The fallback is `SUPPORTED_LINES.first()`, and only the list's order makes that the newest.** A
 * list written oldest-first would send every unsupported configset to the *oldest* vocabulary with
 * every existing test still green, because a test comparing the fallback against
 * [SolrVersionSelection.DEFAULT] compares two answers that took the same wrong turn. So the assertions
 * below name a class, a parameter and an element that separate the lines, rather than comparing one
 * catalog read to another.
 *
 * The witnesses are named, never counted, for the reason `SolrClassCatalogTest` gives: a generator
 * fails by producing a plausible list, and a count moves with the Solr line.
 */
class SolrCatalogSelectionTest {

    /**
     * A class present on Solr 10 and on no 9 release — the dense-vector field 10 added.
     *
     * Its counterpart is [onlyInNine], one of the four field types Solr 10 removed.
     */
    private val onlyInTen = "solr.BinaryQuantizedDenseVectorField"

    private val onlyInNine = "solr.EnumField"

    /** Solr 10 removed the content-stream parameters; nothing was added that 9 lacks. */
    private val parameterOnlyInNine = "stream.body"

    /** The cache Solr 10 added, and the only element that separates the two vocabularies. */
    private val elementOnlyInTen = "featureVectorCache"

    /** Where Solr reads it: a cache, off the query node rather than off the document. */
    private val parentOfElementOnlyInTen = "query"

    /**
     * A configset's own declaration is the arm that answers when there is no server, and the
     * selection records that it was.
     */
    @Test
    fun `a declared line answers from that line, and says the configset decided it`() {
        val nine = SolrVersionSelection.fromLuceneMatchVersion("9.12.0")
        assertEquals(SolrVersionSource.CONFIGSET, nine.source)
        assertAnsweredFromNine(nine)
    }

    /**
     * Nothing declared reaches the newest line, and the selection says so rather than claiming a
     * configset asked for it.
     */
    @Test
    fun `an undeclared line answers from the newest, and says nothing declared one`() {
        assertEquals(SolrVersionSource.DEFAULT, SolrVersionSelection.DEFAULT.source)
        assertAnsweredFromTen(SolrVersionSelection.DEFAULT)
    }

    /**
     * **The newest, not merely the first.** A Solr 7 configset is one this plugin does not support, and
     * the rule is that it gets the current vocabulary rather than silence — but "current" is what
     * `SUPPORTED_LINES.first()` means only while the list stays newest-first, in all three of the
     * places it is written. Reversing any one of them fails here and nowhere else.
     */
    @Test
    fun `an unsupported line falls back to the newest line rather than to the oldest`() {
        val ancient = SolrVersionSelection.fromLuceneMatchVersion("7.0.0")
        assertEquals(SolrVersionSource.DEFAULT, ancient.source)
        assertAnsweredFromTen(ancient)
    }

    /**
     * A line from beyond this build's knowledge falls forward, not back.
     *
     * Solr 11 will exist before this plugin is rebuilt for it, and the useful answer then is the
     * newest vocabulary this build does have — the same fallback an unsupported *old* line takes,
     * which is worth asserting separately because it arrives at `lineFor` by a different route: the
     * guide segment is `latest` rather than a major this build declines.
     */
    @Test
    fun `a line newer than this build falls back to the newest line it ships`() {
        assertAnsweredFromTen(SolrVersionSelection.fromLuceneMatchVersion("99.0.0"))
    }

    /**
     * **The server arm's consuming half is already built, and only its producing half waits.**
     *
     * The catalogs decide from the selection's line and never look at its source, so a selection a
     * connected server produced is honoured today exactly as a configset's is. That is what bounds
     * what the server reader still owes the selection order: constructing the selection, not teaching
     * anything to read it. A catalog that consulted [SolrVersionSource] would make the server arm a
     * change here as well, and this is what says it is not.
     */
    @Test
    fun `a selection a server produced is honoured by every catalog`() {
        val fromServer = SolrVersionSelection("9_10", SolrVersionSource.SERVER)
        assertEquals(SolrVersionSource.SERVER, fromServer.source)
        assertAnsweredFromNine(fromServer)
    }

    /**
     * The two lists that name supported lines agree, and the parameter catalog reads the class
     * catalog's rather than keeping a third.
     *
     * [SolrElementCatalog] keeps its list private and is held to the same order behaviourally, by the
     * fallback assertions above.
     */
    @Test
    fun `the declared lines are newest first`() {
        assertEquals(
            "SUPPORTED_LINES is the fallback's definition of newest, so it must be sorted",
            SolrClassCatalog.SUPPORTED_LINES.sortedDescending(),
            SolrClassCatalog.SUPPORTED_LINES,
        )
        assertEquals(SolrClassCatalog.SUPPORTED_LINES, SolrParameterCatalog.SUPPORTED_LINES)
    }

    private fun assertAnsweredFromNine(version: SolrVersionSelection) {
        assertNotNull("$version should reach Solr 9's classes", SolrClassCatalog.find(onlyInNine, version))
        assertNull("$version reached Solr 10's classes", SolrClassCatalog.find(onlyInTen, version))
        assertNotNull(
            "$version should reach Solr 9's parameters",
            SolrParameterCatalog.parameter(parameterOnlyInNine, version),
        )
        assertNull(
            "$version reached Solr 10's element vocabulary",
            SolrElementCatalog.element(elementOnlyInTen, parentOfElementOnlyInTen, version),
        )
    }

    private fun assertAnsweredFromTen(version: SolrVersionSelection) {
        assertNotNull("$version should reach Solr 10's classes", SolrClassCatalog.find(onlyInTen, version))
        assertNull("$version reached Solr 9's classes", SolrClassCatalog.find(onlyInNine, version))
        // Solr 10 added no parameter that 9 lacks, so the only witness available is 9's absence. It
        // is a real one while the parameter catalog is proven non-empty here, which it is.
        assertTrue("$version reached no parameters at all", SolrParameterCatalog.parametersFor(version).size > 100)
        assertNull(
            "$version reached Solr 9's parameters",
            SolrParameterCatalog.parameter(parameterOnlyInNine, version),
        )
        assertNotNull(
            "$version should reach Solr 10's element vocabulary",
            SolrElementCatalog.element(elementOnlyInTen, parentOfElementOnlyInTen, version),
        )
    }
}
