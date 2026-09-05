package org.apache.solr.ide.server.query

import com.intellij.codeInsight.template.impl.TemplateSettings
import com.intellij.httpClient.actions.AddRequestTemplateProvider
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/**
 * That every offered request resolves to a template the IDE has actually registered.
 *
 * **The test that was missing, and the only kind that could have caught what shipped.** Everything
 * else about these templates could be checked without an IDE — the text, the descriptions, the
 * argument order — and all of it passed while every entry in the menu answered *Cannot find template
 * for this request*. The action does not use the request text at all: it reads the descriptor's
 * first field as a key and asks `TemplateSettings` for it. Nothing that stops short of asking
 * `TemplateSettings` the same question is testing the thing that fails.
 *
 * So this is a fixture test rather than a plain one, for the reason fixture tests exist here: the
 * registration is the subject, and a registration is a fact about a running IDE.
 */
class SolrRequestTemplateResolutionTest : BasePlatformTestCase() {

    private val provided: List<AddRequestTemplateProvider.TemplateDescriptor>
        get() = SolrRequestTemplateProvider().provideTemplates()

    /**
     * Each published descriptor names a live template that exists, in the group it says.
     *
     * The pair is what the lookup takes, so both halves have to be right together: a correct key in
     * the wrong group fails exactly as a missing one does.
     */
    fun testEveryOfferedRequestResolves() {
        val settings = TemplateSettings.getInstance()

        val unresolved = provided.filter { settings.getTemplate(it.template, it.group) == null }

        assertEmpty(
            "these would answer 'Cannot find template for this request': " +
                unresolved.map { "${it.group}/${it.template}" },
            unresolved,
        )
    }

    /**
     * The registered file and the offered list hold the same templates.
     *
     * Guards the direction the other test cannot see: a template registered but never offered is
     * invisible, and both halves come from one file precisely so this stays true.
     */
    fun testTheRegisteredGroupHoldsExactlyWhatIsOffered() {
        val registered = TemplateSettings.getInstance().templates
            .filter { it.groupName == SolrRequestTemplates.GROUP }
            .map { it.key }
            .sorted()

        assertEquals(SolrRequestTemplates.all.map { it.key }.sorted(), registered)
    }

    /**
     * A resolved template carries exactly the text the reader reports, byte for byte.
     *
     * **The join between the two halves, and the reason the reader does no decoding of its own.**
     * `SolrRequestTemplates` parses the same file the IDE parses, so any transformation it applied
     * -- unescaping a `$`, trimming, normalising a newline -- would be a second decoder, and the
     * first template to use that syntax is where the two would part company. Every other test in
     * this package asserts against the reader's text; this is what makes those assertions statements
     * about what the IDE actually inserts.
     */
    fun testAResolvedTemplateCarriesTheTextTheReaderReports() {
        val settings = TemplateSettings.getInstance()

        SolrRequestTemplates.all.forEach { template ->
            val resolved = settings.getTemplate(template.key, SolrRequestTemplates.GROUP)
            assertNotNull(template.key, resolved)
            assertEquals(template.key, template.template, resolved!!.string)
        }
    }
}
