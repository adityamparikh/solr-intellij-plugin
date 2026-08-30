package org.apache.solr.ide.server.indexing

import org.apache.solr.ide.configset.activation.SolrConfigsetTestCase
import org.apache.solr.ide.model.SolrConfigsetFacts
import org.apache.solr.ide.model.schema.SolrDynamicField
import org.apache.solr.ide.model.schema.SolrField
import org.apache.solr.ide.model.schema.SolrFieldType

/**
 * The dialog that stands between a keystroke and a live collection.
 *
 * Built headlessly, as the connection form is: a `DialogWrapper` constructs and validates without a
 * screen, so what it refuses is ordinary test material.
 */
class SolrIndexDocumentDialogTest : SolrConfigsetTestCase() {

    private val schema = SolrConfigsetFacts(
        fields = listOf(
            SolrField(name = "id", type = "string", required = true),
            SolrField(name = "title", type = "text_general"),
        ),
        dynamicFields = listOf(SolrDynamicField("*_s", SolrField(name = "*_s", type = "string"))),
        fieldTypes = listOf(SolrFieldType("string", "solr.StrField"), SolrFieldType("text_general", "solr.TextField")),
        uniqueKey = "id",
    )

    private fun <T> withDialog(body: (SolrIndexDocumentDialog) -> T): T {
        val dialog = SolrIndexDocumentDialog(project, "books", "local", schema)
        return try {
            body(dialog)
        } finally {
            dialog.close(0)
        }
    }

    // --- what it opens with -------------------------------------------------------------------------

    /** The generated document is what the schema requires, and it is already valid. */
    fun testItOpensOnADocumentTheSchemaAccepts() {
        withDialog { dialog ->
            assertTrue(dialog.document, dialog.document.contains("\"id\""))
            assertEmpty("a generated document must not open with a complaint", dialog.problems())
            assertEmpty(dialog.performValidateAll())
        }
    }

    /** The default is `commitWithin`, chosen rather than left to whatever sorts first. */
    fun testTheDefaultCommitModeIsCommitWithin() {
        withDialog { assertEquals(SolrCommitMode.WITHIN, it.commitMode) }
    }

    /** Both the collection and the server are named where the user will read them. */
    fun testTheTitleNamesTheCollectionAndTheServer() {
        withDialog { dialog ->
            assertTrue(dialog.title, dialog.title.contains("books"))
            assertTrue(dialog.title, dialog.title.contains("local"))
        }
    }

    // --- what it refuses ------------------------------------------------------------------------------

    /**
     * A field the schema cannot place stops the send.
     *
     * Solr would accept it and add it to the deployed schema, so this is the only place it can be
     * stopped.
     */
    fun testAFieldTheSchemaCannotPlaceIsRefused() {
        withDialog { dialog ->
            dialog.setDocument("""{"id": "1", "titel": "typo"}""")

            val problem = dialog.problems().single { it.field == "titel" }
            assertEquals(SolrDocumentSeverity.ERROR, problem.severity)
            assertFalse("the send must be blocked", dialog.performValidateAll().isEmpty())
        }
    }

    /** A document with no unique key stops the send, though Solr would index it under a UUID. */
    fun testADocumentWithNoUniqueKeyIsRefused() {
        withDialog { dialog ->
            dialog.setDocument("""{"title": "no id"}""")

            assertFalse(dialog.performValidateAll().isEmpty())
        }
    }

    /** A dynamic instance is accepted, which is the case a naive check gets wrong. */
    fun testADynamicInstanceIsAccepted() {
        withDialog { dialog ->
            dialog.setDocument("""{"id": "1", "author_s": "Frank Herbert"}""")

            assertEmpty(dialog.problems())
            assertEmpty(dialog.performValidateAll())
        }
    }

    /**
     * A warning does not block the send.
     *
     * Supplying `_version_` is legal and almost never meant. Blocking it would be the plugin
     * refusing something Solr allows; saying nothing would let it through unremarked.
     */
    fun testAWarningIsShownWithoutBlockingTheSend() {
        withDialog { dialog ->
            dialog.setDocument("""{"id": "1", "_version_": 12345}""")

            assertEquals(SolrDocumentSeverity.WARNING, dialog.problems().single().severity)
            assertEmpty("a warning must not block", dialog.performValidateAll())
        }
    }

    /**
     * Half-typed JSON still validates what it can.
     *
     * A validator that went silent on unbalanced braces would go silent exactly while someone was
     * typing the field name it exists to check.
     */
    fun testAnUnfinishedDocumentIsStillChecked() {
        withDialog { dialog ->
            dialog.setDocument("""{"id": "1", "titel": """)

            assertTrue(dialog.problems().toString(), dialog.problems().any { it.field == "titel" })
        }
    }
}
