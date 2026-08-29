package org.apache.solr.ide.server.query

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the offered requests say.
 *
 * The property worth the most here is portability, and it is easy to lose by accident: a template
 * that hardcodes `localhost:8983` works perfectly for whoever wrote it and is broken for everyone
 * who clones the repository. Nothing in the IDE would report that — the request runs, against the
 * wrong server or none.
 */
class SolrRequestTemplatesTest {

    private val templates = SolrRequestTemplates.all

    // --- the property that makes a committed query shareable --------------------------------------

    /**
     * No template names a host.
     *
     * The whole argument for saved queries being `.http` files: the HTTP Client's environments are
     * what let a colleague clone the repository, open the same queries and select their own server.
     * A hardcoded host opts every user out of that mechanism silently.
     */
    @Test
    fun `no template hardcodes a host`() {
        val offenders = templates.filter { template ->
            HOST_MARKERS.any { template.template.contains(it, ignoreCase = true) }
        }

        assertTrue(
            "these templates name a host instead of an environment variable: ${offenders.map { it.description }}",
            offenders.isEmpty(),
        )
    }

    @Test
    fun `every template addresses the solr url variable`() {
        val missing = templates.filterNot { it.template.contains("{{${SolrRequestTemplates.URL_VARIABLE}}}") }

        assertTrue("templates not using the url variable: ${missing.map { it.description }}", missing.isEmpty())
    }

    @Test
    fun `every template addresses a collection by variable`() {
        val missing = templates.filterNot { it.template.contains("{{${SolrRequestTemplates.COLLECTION_VARIABLE}}}") }

        assertTrue("templates not using the collection variable: ${missing.map { it.description }}", missing.isEmpty())
    }

    // --- what makes them valid requests -----------------------------------------------------------

    /** Each template is a request the HTTP Client can parse: a method, then a URL. */
    @Test
    fun `every template states a method and a url`() {
        for (template in templates) {
            val request = template.template.lines().first { it.startsWith("GET") || it.startsWith("POST") }

            assertTrue(template.description, request.substringAfter(" ").startsWith("{{"))
        }
    }

    /** Each carries a `###` separator, which is what makes it a request rather than loose text. */
    @Test
    fun `every template opens with a request separator`() {
        for (template in templates) {
            assertTrue(template.description, template.template.startsWith("###"))
        }
    }

    @Test
    fun `every template asks for json`() {
        for (template in templates) {
            assertTrue(template.description, template.template.contains("Accept: application/json"))
        }
    }

    @Test
    fun `no template has a blank description`() {
        assertTrue(templates.all { it.description.isNotBlank() })
    }

    @Test
    fun `descriptions are distinct`() {
        assertEquals(templates.size, templates.map { it.description }.toSet().size)
    }

    // --- that they reach the right Solr endpoints -------------------------------------------------

    @Test
    fun `querying goes through the select handler`() {
        assertTrue(templates.first().template.contains("/select?q="))
    }

    /**
     * The scoring explanation asks for `debugQuery=true`.
     *
     * The parameter matters: `debug=timing` and `debug=query` each return part of the answer, and
     * only this one carries the per-document explanation the console is meant to render.
     */
    @Test
    fun `the scoring template asks for the explanation`() {
        val explaining = templates.single { it.description.contains("scored") }

        assertTrue(explaining.template, explaining.template.contains("debugQuery=true"))
    }

    @Test
    fun `the schema template reads the schema api`() {
        val schema = templates.single { it.description.contains("schema") }

        assertTrue(schema.template, schema.template.contains("/schema"))
        assertFalse("the schema api is not the luke handler", schema.template.contains("luke"))
    }

    /**
     * The index template goes to Luke, not to the Schema API.
     *
     * They answer different questions, and this is the one that reports fields a dynamic pattern
     * created at index time — which the schema cannot name.
     */
    @Test
    fun `the index template reads the luke handler`() {
        val luke = templates.single { it.description.contains("index actually holds") }

        assertTrue(luke.template, luke.template.contains("/admin/luke"))
    }

    private companion object {
        /** Spellings that would mean a template had been written against one developer's machine. */
        val HOST_MARKERS = listOf("localhost", "127.0.0.1", "http://", "https://", ":8983")
    }
}
