package org.apache.solr.ide.server.indexing

import org.apache.solr.ide.model.SolrConfigsetFacts
import org.apache.solr.ide.model.schema.SolrDynamicField
import org.apache.solr.ide.model.schema.SolrField
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What is said about a document before Solr is asked.
 *
 * **Every rule here exists because Solr answers `status: 0`.** Both of the cases that matter were
 * run against Solr 10.0.0 on the `_default` configset: an unknown field is *added to the deployed
 * schema* rather than refused, and a document with no unique key is indexed under a generated UUID.
 * Neither reports an error, so neither can be caught by reading the response — checking before
 * sending is the only place either exists to be found.
 */
class SolrDocumentValidationTest {

    private val schema = SolrConfigsetFacts(
        fields = listOf(
            SolrField(name = "id", type = "string", required = true),
            SolrField(name = "title", type = "text_general"),
        ),
        dynamicFields = listOf(SolrDynamicField("*_s", SolrField(name = "*_s", type = "string"))),
        uniqueKey = "id",
    )

    private fun problems(vararg fields: String) =
        SolrDocumentValidation.problemsIn(fields.toList(), schema)

    // --- the document that indexes and cannot be found ---------------------------------------------

    /**
     * A document with no unique key is an error, though Solr accepts it.
     *
     * Solr generates a UUID rather than refusing, so the index gains a document nobody can look up
     * by the id they think it has. "It indexed successfully" and "it is there" come apart.
     */
    @Test
    fun `a document with no unique key is reported`() {
        val problem = problems("title").single { it.field == "id" }

        assertEquals(SolrDocumentSeverity.ERROR, problem.severity)
        assertTrue(problem.message, problem.message.contains("generated identifier"))
    }

    @Test
    fun `a document carrying the unique key is not reported for it`() {
        assertTrue(problems("id", "title").none { it.field == "id" })
    }

    // --- the typo that changes the deployed schema -------------------------------------------------

    /**
     * A field the schema has no place for is an error, though Solr accepts it.
     *
     * The default update chain adds it to the schema. One typo produced a field, a `_str` companion
     * and a copy-field directive — all three of which the drift view then reports as declarations
     * only the server has.
     */
    @Test
    fun `a field the schema cannot place is reported`() {
        val problem = problems("id", "titel").single { it.field == "titel" }

        assertEquals(SolrDocumentSeverity.ERROR, problem.severity)
        assertTrue(problem.message, problem.message.contains("add it to the schema"))
    }

    @Test
    fun `a declared field is not reported`() {
        assertTrue(problems("id", "title").none { it.field == "title" })
    }

    /**
     * A field a dynamic pattern matches is fine, and this is the case a naive check gets wrong.
     *
     * `author_s` is declared nowhere and is entirely legitimate — refusing it would reject the most
     * ordinary thing a Solr schema does.
     */
    @Test
    fun `a field a dynamic pattern matches is not reported`() {
        assertTrue(problems("id", "author_s").none { it.field == "author_s" })
    }

    // --- Solr's own fields --------------------------------------------------------------------------

    /** Supplying `_version_` is legal and almost never meant, so it warns rather than blocks. */
    @Test
    fun `supplying an internal field warns rather than errors`() {
        val problem = problems("id", "_version_").single { it.field == "_version_" }

        assertEquals(SolrDocumentSeverity.WARNING, problem.severity)
    }

    /** And it is not also reported as unknown — one fact, one message. */
    @Test
    fun `an internal field is reported once`() {
        assertEquals(1, problems("id", "_version_").count { it.field == "_version_" })
    }

    // --- the clean case -----------------------------------------------------------------------------

    /**
     * A correct document says nothing at all.
     *
     * The half that decides whether anyone reads the rest: a validator that always finds something
     * is one users learn to dismiss.
     */
    @Test
    fun `a correct document produces no problems`() {
        assertTrue(problems("id", "title", "author_s").toString(), problems("id", "title", "author_s").isEmpty())
    }

    /** A schema with no unique key asks for none. */
    @Test
    fun `a schema declaring no unique key does not demand one`() {
        val keyless = SolrConfigsetFacts(fields = listOf(SolrField(name = "title", type = "string")))

        assertTrue(SolrDocumentValidation.problemsIn(listOf("title"), keyless).isEmpty())
    }

    /** Errors come before warnings, because the first thing read should be the thing that stops you. */
    @Test
    fun `errors are listed before warnings`() {
        val ordered = SolrDocumentValidation.problemsIn(listOf("_version_", "titel"), schema)

        assertEquals(SolrDocumentSeverity.ERROR, ordered.first().severity)
        assertEquals(SolrDocumentSeverity.WARNING, ordered.last().severity)
    }
}
