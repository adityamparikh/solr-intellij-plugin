package org.apache.solr.ide.server.query

import org.apache.solr.ide.configset.activation.SolrConfigsetTestCase

/**
 * Where a query's field names come from.
 *
 * The project's configsets, not a server. What this pins is the consequences of that choice: several
 * configsets give several answers and each says which it came from, and a project with none gives
 * nothing rather than something invented.
 */
class SolrProjectFieldsTest : SolrConfigsetTestCase() {

    // Built by joining rather than with an interpolated `trimIndent`: interpolated lines carry no
    // indent of their own, so `trimIndent` computes a common indent from them and leaves the XML
    // declaration indented — which is malformed, parses to nothing, and would have made every
    // assertion below fail for a reason that has nothing to do with the code under test.
    private fun schemaNaming(vararg fields: String) = buildString {
        appendLine("""<?xml version="1.0" encoding="UTF-8"?>""")
        appendLine("""<schema name="books" version="1.6">""")
        fields.forEach { appendLine("""  <field name="$it" type="string" indexed="true" stored="true"/>""") }
        appendLine("""  <dynamicField name="*_i" type="pint" indexed="true" stored="true"/>""")
        appendLine("""  <fieldType name="string" class="solr.StrField"/>""")
        appendLine("""  <fieldType name="pint" class="solr.IntPointField"/>""")
        appendLine("</schema>")
    }

    private fun givenConfigset(name: String, vararg fields: String) {
        myFixture.addFileToProject("$name/conf/managed-schema.xml", schemaNaming(*fields))
        myFixture.addFileToProject(
            "$name/conf/solrconfig.xml",
            "<config><luceneMatchVersion>10.0.0</luceneMatchVersion></config>",
        )
    }

    private fun fields() = SolrProjectFields.getInstance(project).all()

    fun testAProjectWithNoConfigsetOffersNothing() {
        assertEmpty(fields())
    }

    fun testTheDeclaredFieldsAreOffered() {
        givenConfigset("books", "id", "title", "author_s")

        assertContainsElements(fields().map { it.name }, "id", "title", "author_s")
    }

    fun testEachFieldCarriesItsType() {
        givenConfigset("books", "id")

        assertEquals("string", fields().single { it.name == "id" }.type)
    }

    /** Which configset an offer came from, so a project with several can be told apart. */
    fun testEachFieldNamesItsConfigset() {
        givenConfigset("books", "id")

        assertEquals("books", fields().single { it.name == "id" }.configset)
    }

    /**
     * A dynamic pattern is offered and marked as one.
     *
     * It is what a user types when they mean the field it will create, so hiding it would leave the
     * most common way of naming a dynamic field unavailable — but it is not a field, and saying so
     * is what stops it being read as one.
     */
    fun testDynamicPatternsAreOfferedAndMarked() {
        givenConfigset("books", "id")

        val pattern = fields().single { it.name == "*_i" }
        assertTrue(pattern.dynamic)
        assertFalse(fields().single { it.name == "id" }.dynamic)
    }

    fun testEveryConfigsetContributes() {
        givenConfigset("books", "id", "author_s")
        givenConfigset("films", "id", "director_s")

        val names = fields().map { it.name }
        assertContainsElements(names, "author_s", "director_s")
    }

    /**
     * A name declared in two configsets is offered once.
     *
     * `id` is in every configset ever written, and offering it once per configset would put four
     * identical entries in the popup with nothing to choose between them.
     */
    fun testANameSharedByTwoConfigsetsIsOfferedOnce() {
        givenConfigset("books", "id")
        givenConfigset("films", "id")

        assertEquals(1, fields().count { it.name == "id" })
    }
}
