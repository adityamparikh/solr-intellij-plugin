package org.apache.solr.ide.server.indexing

import org.apache.solr.ide.configset.activation.SolrConfigsetTestCase
import org.apache.solr.ide.model.SolrConfigsetFacts
import org.apache.solr.ide.model.schema.SolrDynamicField
import org.apache.solr.ide.model.schema.SolrField

/**
 * Field names offered while writing a document for a collection.
 *
 * **Registered against JSON, so every JSON file in every project reaches this** — the same exposure
 * the query-body completion carries. The clean cases come first for the same reason: offering Solr
 * field names in somebody's `package.json` is the failure worth the most care.
 */
class SolrDocumentFieldCompletionTest : SolrConfigsetTestCase() {

    private val schema = SolrConfigsetFacts(
        fields = listOf(
            SolrField(name = "id", type = "string", required = true),
            SolrField(name = "title", type = "text_general"),
            SolrField(name = "_version_", type = "plong"),
        ),
        dynamicFields = listOf(SolrDynamicField("*_s", SolrField(name = "*_s", type = "string"))),
        uniqueKey = "id",
    )

    /** Completion over a file marked as a document editor's, as the dialog marks it. */
    private fun offeredIn(text: String, marked: Boolean = true): List<String> {
        val file = myFixture.configureByText("solr-document.json", text)
        if (marked) file.putUserData(SOLR_DOCUMENT_SCHEMA, schema)
        myFixture.completeBasic()
        return myFixture.lookupElementStrings.orEmpty()
    }

    // --- where nothing may be offered ---------------------------------------------------------------

    /**
     * An unmarked JSON file gets nothing, whatever it contains.
     *
     * The file here is even named like ours and shaped like a document. What decides is the schema
     * the dialog attaches, which nothing outside that dialog can attach.
     */
    fun testAnUnmarkedJsonFileIsUntouched() {
        val offered = offeredIn("""{"<caret>"}""", marked = false)

        assertFalse(offered.toString(), offered.contains("title"))
    }

    /**
     * A value is not a field name.
     *
     * Offering there would suggest field names as data, which is a different and wrong suggestion —
     * and would fire on every string a user types into a document.
     */
    fun testAValueIsNotAFieldName() {
        val offered = offeredIn("""{"id": "<caret>"}""")

        assertFalse(offered.toString(), offered.contains("title"))
    }

    // --- where field names belong ---------------------------------------------------------------------

    fun testFieldNamesAreOfferedAsKeys() {
        val offered = offeredIn("""{"<caret>"}""")

        assertContainsElements(offered, "id", "title")
    }

    fun testFieldNamesAreOfferedBesideAnExistingKey() {
        val offered = offeredIn("""{"id": "1", "<caret>"}""")

        assertContainsElements(offered, "title")
    }

    /**
     * A dynamic pattern is offered, because it is the only clue the schema gives that a name like
     * `author_s` is legal at all.
     */
    fun testDynamicPatternsAreOffered() {
        val offered = offeredIn("""{"<caret>"}""")

        assertContainsElements(offered, "*_s")
    }

    /**
     * Solr's own fields are not offered.
     *
     * Supplying `_version_` asserts a concurrency check almost nobody means — the validation warns
     * about it, and completion should not be the thing that suggested it.
     */
    fun testInternalFieldsAreNotOffered() {
        val offered = offeredIn("""{"<caret>"}""")

        assertFalse(offered.toString(), offered.contains("_version_"))
    }

    // --- what it completes for ------------------------------------------------------------------------

    /**
     * The fields come from the collection the document is for, not from the project.
     *
     * A project configset naming other fields must not leak in: the document is about to be sent to
     * one named collection, and a field it cannot accept would be added to its schema rather than
     * refused.
     */
    fun testTheProjectsConfigsetsDoNotLeakIn() {
        myFixture.addFileToProject(
            "elsewhere/conf/managed-schema.xml",
            """<?xml version="1.0" encoding="UTF-8"?>
               <schema name="elsewhere" version="1.6">
                 <field name="only_in_the_project" type="string" indexed="true"/>
                 <fieldType name="string" class="solr.StrField"/>
               </schema>""".trimIndent(),
        )
        myFixture.addFileToProject("elsewhere/conf/solrconfig.xml", "<config/>")

        val offered = offeredIn("""{"<caret>"}""")

        assertContainsElements(offered, "id")
        assertFalse(offered.toString(), offered.contains("only_in_the_project"))
    }

    /**
     * A caret with no quotes typed yet is still a place a field name goes.
     *
     * This is the case that looks like it needs a separate rule and does not: the platform's dummy
     * identifier makes it a property before the contributor ever sees it.
     */
    fun testFieldNamesAreOfferedWithNoQuotesTypedYet() {
        val offered = offeredIn("""{<caret>}""")

        assertContainsElements(offered, "id", "title")
    }

    /**
     * Completion runs over a copy of the file, and the schema is found through the original.
     *
     * The dialog marks the file it creates; the platform hands the contributor a copy of it. A
     * lookup that only asked the copy would find nothing, and the feature would be silently absent
     * exactly where it is used.
     */
    fun testTheSchemaIsFoundThroughTheOriginalFile() {
        val file = myFixture.configureByText("solr-document.json", """{"<caret>"}""")
        file.originalFile.putUserData(SOLR_DOCUMENT_SCHEMA, schema)

        myFixture.completeBasic()

        assertContainsElements(myFixture.lookupElementStrings.orEmpty(), "id")
    }
}
