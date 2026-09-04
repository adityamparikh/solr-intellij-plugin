package org.apache.solr.ide.server.query

import com.intellij.httpClient.actions.AddRequestTemplateProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * That the templates actually reach the HTTP Client's menu.
 *
 * **The assertion worth having is what the first argument means**, and this file previously had it
 * backwards. `TemplateDescriptor` takes three strings — template, group, description — and the
 * first is a live template *key*, which the action resolves with
 * `TemplateSettings.getTemplate(key, group)`. Passing the request text there compiles perfectly,
 * publishes perfectly, and every entry answers *Cannot find template for this request* when it is
 * clicked. A test asserting the descriptor carried the request text agreed with the code and
 * disagreed with the platform, which is the one party that was never in the room.
 */
class SolrRequestTemplateProviderTest {

    private val provided: List<AddRequestTemplateProvider.TemplateDescriptor> =
        SolrRequestTemplateProvider().provideTemplates()

    @Test
    fun `every template is published`() {
        assertEquals(SolrRequestTemplates.all.size, provided.size)
    }

    /** The label is the short description, not the request body. */
    @Test
    fun `the description is what the menu shows`() {
        assertEquals(
            SolrRequestTemplates.all.map { it.description },
            provided.map { it.description },
        )
    }

    /** The descriptor carries the live template key, which is what the action looks up. */
    @Test
    fun `the template is the live template key`() {
        assertEquals(
            SolrRequestTemplates.all.map { it.key },
            provided.map { it.template },
        )
    }

    /**
     * No descriptor carries a request body where a key belongs.
     *
     * The check that catches the original defect by its shape rather than by its value: a live
     * template key is one word, and the request text this used to pass has newlines and spaces in
     * it.
     */
    @Test
    fun `no template field carries a request`() {
        assertTrue(provided.none { it.template.contains('\n') || it.template.contains(' ') })
    }

    @Test
    fun `every template is filed under the solr group`() {
        assertTrue(provided.all { it.group == SolrRequestTemplates.GROUP })
    }

    /**
     * A description is one line.
     *
     * The check that catches the swap even if the two assertions above were themselves written the
     * wrong way round: a request body has newlines and a menu label does not.
     */
    @Test
    fun `no description carries a newline`() {
        assertTrue(provided.none { it.description.contains('\n') })
    }
}
