package org.apache.solr.ide.configset.solrconfig

import com.intellij.codeInsight.completion.CompletionType
import com.intellij.codeInsight.documentation.DocumentationManager
import com.intellij.lang.documentation.DocumentationProvider
import org.apache.solr.ide.configset.activation.SolrConfigsetTestCase

/**
 * What a `class` attribute in `solrconfig.xml` offers, says and navigates to.
 *
 * **The surfaces were already general; only the vocabulary was short.** Completion, quick documentation
 * and class navigation all route a `class` attribute through
 * [SolrClassKind.forTag][org.apache.solr.ide.model.vocabulary.SolrClassKind.forTag] and are gated on
 * configset membership rather than on which of the two files the caret is in — so the whole of this
 * behaviour arrived when the catalog learned Solr's `solrconfig.xml` plugin kinds, with no new surface
 * written. That is worth pinning precisely because nothing in the diff looks like a feature: a later
 * change that narrowed any of those three to the schema would take all of it away and break no test
 * that existed before this one.
 *
 * The classes named here are the ones a reader reported as inert — a `<directoryFactory>` and a
 * `<codecFactory>` offering nothing and explaining nothing — plus `<requestHandler>`, which is the most
 * written element in the file.
 */
class SolrConfigClassValueTest : SolrConfigsetTestCase() {

    private val schema = """
        <schema name="t" version="1.7">
          <fieldType name="string" class="solr.StrField"/>
          <field name="id" type="string"/>
        </schema>
    """.trimIndent()

    private var schemaAdded = false

    private fun configure(body: String) {
        if (!schemaAdded) {
            myFixture.addFileToProject("managed-schema.xml", schema)
            schemaAdded = true
        }
        myFixture.configureByText("solrconfig.xml", "<config>\n$body\n</config>")
    }

    private fun completionsFor(body: String): List<String> {
        configure(body)
        myFixture.complete(CompletionType.BASIC)
        return myFixture.lookupElementStrings.orEmpty()
    }

    /** The popup HTML at the caret, or null when nothing documents it. */
    private fun documentationFor(body: String): String? {
        configure(body)
        val target = DocumentationManager.getInstance(project)
            .findTargetElement(myFixture.editor, myFixture.file)
            ?: return null
        val provider = DocumentationManager.getProviderFromElement(target) as DocumentationProvider
        return provider.generateDoc(target, myFixture.file.findElementAt(myFixture.caretOffset))
    }

    // --- completion ------------------------------------------------------------------------------

    /**
     * The element a reader opens the file to read, and the one the short catalog embarrassed itself on
     * most: `<requestHandler>` offered nothing at all.
     */
    fun testRequestHandlerClassesAreOffered() {
        val offered = completionsFor("""<requestHandler name="/select" class="<caret>"/>""")
        assertTrue("expected solr.SearchHandler among $offered", "solr.SearchHandler" in offered)
        assertTrue("expected solr.UpdateRequestHandler among $offered", "solr.UpdateRequestHandler" in offered)
    }

    /** One of the two elements reported as offering no valid suggestions. */
    fun testDirectoryFactoryClassesAreOffered() {
        val offered = completionsFor("""<directoryFactory name="DirectoryFactory" class="<caret>"/>""")
        assertTrue("expected solr.NRTCachingDirectoryFactory among $offered", "solr.NRTCachingDirectoryFactory" in offered)
    }

    /** The other one. */
    fun testCodecFactoryClassesAreOffered() {
        val offered = completionsFor("""<codecFactory class="<caret>"/>""")
        assertTrue("expected solr.SchemaCodecFactory among $offered", "solr.SchemaCodecFactory" in offered)
    }

    /**
     * Each element is offered its own population and nobody else's.
     *
     * This is the assertion that makes the list worth reading. A `class` attribute means twenty-two
     * different things across a configset, and a provider that poured all of them into every position
     * would be teaching the vocabulary wrongly to exactly the reader who is using the list to learn
     * it — while still passing every positive case above.
     */
    fun testEachElementIsOfferedOnlyItsOwnClasses() {
        val handlers = completionsFor("""<requestHandler name="/select" class="<caret>"/>""")
        assertTrue("a codec factory is not a request handler: $handlers", "solr.SchemaCodecFactory" !in handlers)
        assertTrue("a field type is not a request handler: $handlers", "solr.StrField" !in handlers)

        val codecs = completionsFor("""<codecFactory class="<caret>"/>""")
        assertTrue("a request handler is not a codec factory: $codecs", "solr.SearchHandler" !in codecs)
    }

    /**
     * A cache class is offered on `<filterCache>`, which is not spelled like its kind.
     *
     * Solr reads its named caches by element name rather than through the plugin list, so the
     * generated kind token is `cache` and `filterCache` matches none. A mapping that assumed the token
     * was the tag would be silent here — on an element every configset that tunes its caches writes.
     */
    fun testCacheClassesAreOfferedOnANamedCache() {
        val offered = completionsFor("""<query><filterCache class="<caret>" size="512"/></query>""")
        assertTrue("expected a cache class among $offered", offered.any { it.startsWith("solr.") })
        assertTrue("a request handler is not a cache: $offered", "solr.SearchHandler" !in offered)
    }

    /** The same, for a chain member — written `<processor>`, never `<updateProcessor>`. */
    fun testUpdateProcessorClassesAreOfferedOnAChainMember() {
        val offered = completionsFor(
            """<updateRequestProcessorChain name="x"><processor class="<caret>"/></updateRequestProcessorChain>""",
        )
        assertTrue("expected solr.LogUpdateProcessorFactory among $offered", "solr.LogUpdateProcessorFactory" in offered)
    }

    /**
     * The link label reads as English for every kind, which the generic plural is what decides.
     *
     * `directoryFactory` is one of five kinds ending in `y`, and a bare `s` produced *Directory
     * factorys* — in a label the popup invites the reader to click.
     */
    fun testTheGuideLinkLabelIsSpelledAsEnglish() {
        val doc = documentationFor("""<directoryFactory name="DirectoryFactory" class="solr.NRTCaching<caret>DirectoryFactory"/>""")
        assertNotNull("expected documentation", doc)
        assertTrue("expected a correctly pluralised label: $doc", doc!!.contains("Directory factories"))
        assertFalse("the naive plural must not ship: $doc", doc.contains("factorys"))
    }

    /**
     * An element that carries no `class` is offered nothing, rather than every class in the file.
     *
     * `<lst name="defaults">` has a `name` and no `class`, and the position test is what keeps the
     * twenty-two populations out of the many attributes in `solrconfig.xml` that are not a class.
     */
    fun testAnElementThatNamesNoClassIsOfferedNoClasses() {
        val offered = completionsFor(
            """<requestHandler name="/select"><lst name="<caret>"></lst></requestHandler>""",
        )
        assertTrue("no class belongs in a parameter-list name: $offered", offered.none { it.startsWith("solr.") })
    }

    // --- quick documentation ---------------------------------------------------------------------

    /**
     * Hovering a class says what it is and links the page that describes it.
     *
     * The kind in words is the half a reader cannot get anywhere else: `solr.NRTCachingDirectoryFactory`
     * says nothing about what a directory factory decides, and the class name is all the file shows.
     */
    fun testHoveringADirectoryFactoryClassExplainsIt() {
        val doc = documentationFor("""<directoryFactory name="DirectoryFactory" class="solr.NRTCaching<caret>DirectoryFactory"/>""")
        assertNotNull("expected documentation", doc)
        assertTrue("expected the class name: $doc", doc!!.contains("NRTCachingDirectoryFactory"))
        assertTrue("expected the kind in words: $doc", doc.contains("directory factory"))
    }

    /** The same gesture on the element a reader meets first. */
    fun testHoveringARequestHandlerClassExplainsIt() {
        val doc = documentationFor("""<requestHandler name="/select" class="solr.Search<caret>Handler"/>""")
        assertNotNull("expected documentation", doc)
        assertTrue("expected the kind in words: $doc", doc!!.contains("request handler"))
    }

    /**
     * A class the catalog does not carry documents nothing rather than rendering as a class that does
     * nothing.
     *
     * The same vow the schema's `class` popup takes. A custom plugin is the normal case in this file,
     * and an empty description is a claim where silence is honest.
     */
    fun testHoveringACustomClassSaysNothing() {
        val doc = documentationFor("""<requestHandler name="/x" class="com.example.My<caret>Handler"/>""")
        assertNull("a class outside the catalog must not be described: $doc", doc)
    }
}
