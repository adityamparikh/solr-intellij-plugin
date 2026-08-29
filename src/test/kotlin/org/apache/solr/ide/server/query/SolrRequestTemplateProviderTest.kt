package org.apache.solr.ide.server.query

import com.intellij.httpClient.actions.AddRequestTemplateProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * That the templates actually reach the HTTP Client's menu.
 *
 * **The assertion worth having is the argument order.** `TemplateDescriptor` takes three strings —
 * template, group, description — and passing them in the wrong order compiles perfectly, publishes
 * perfectly, and puts a whole `.http` request where the menu label belongs. Nothing but looking
 * would catch it, and nobody looks at a menu they did not change.
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

    /** The template is the `.http` text, not the label. */
    @Test
    fun `the template is the request text`() {
        assertEquals(
            SolrRequestTemplates.all.map { it.template },
            provided.map { it.template },
        )
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
