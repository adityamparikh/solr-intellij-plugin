package org.apache.solr.ide.code.highlighting

import com.intellij.ide.highlighter.JavaHighlightingColors
import com.intellij.openapi.editor.DefaultLanguageHighlighterColors
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.editor.colors.EditorColorsScheme
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.editor.colors.impl.EditorColorsSchemeImpl
import com.intellij.openapi.util.JDOMUtil
import org.apache.solr.ide.code.SolrCodeFixtures
import java.awt.Color
import org.apache.solr.ide.configset.activation.SolrConfigsetTestCase

/**
 * Colour inside a Solr query written in Java or Kotlin.
 *
 * **Mostly what is asserted is which characters get coloured**, since a boundary off by one is a
 * highlight that covers half a word. One test goes further and asks whether the colour can be *seen*
 * inside a string in the bundled light and dark schemes, because a key can be correct and invisible.
 *
 * An annotator runs on every element of every file the editor shows, so the silences here are the
 * substance. Colour is a claim about what text means, made without being asked.
 */
class SolrQueryHighlightTest : SolrConfigsetTestCase() {

    /** The text of every range this plugin coloured, by the key it used. */
    private fun colouredIn(name: String, text: String): List<String> {
        myFixture.configureByText(name, text)
        myFixture.doHighlighting()
        val document = myFixture.editor.document.charsSequence
        // The document's markup model, not the editor's: annotations produced by the daemon land in
        // the former, and reading the latter finds nothing however correct the annotator is.
        return com.intellij.openapi.editor.impl.DocumentMarkupModel
            .forDocument(myFixture.editor.document, project, false)
            .allHighlighters
            .filter { it.textAttributesKey == SolrQueryColors.FIELD || it.textAttributesKey == SolrQueryColors.OPERATOR }
            .sortedBy { it.startOffset }
            .map { document.subSequence(it.startOffset, it.endOffset).toString() }
    }

    private fun searching(body: String) = """
        import org.apache.solr.client.solrj.SolrQuery;
        class Search {
            void go() {
                SolrQuery q = new SolrQuery("*:*");
                $body
            }
        }
    """.trimIndent()

    // --- what it colours ----------------------------------------------------------------------------

    fun testTheFieldAndTheOperatorAreColoured() {
        SolrCodeFixtures.givenSolrJ(myFixture)

        val coloured = colouredIn("Search.java", searching("""q.setQuery("category:books AND price:9");"""))

        assertEquals(listOf("category", "AND", "price"), coloured)
    }

    /** A filter query is a query, and is coloured as one. */
    fun testAFilterQueryIsColouredToo() {
        SolrCodeFixtures.givenSolrJ(myFixture)

        val coloured = colouredIn("Search.java", searching("""q.addFilterQuery("category:books");"""))

        assertEquals(listOf("category"), coloured)
    }

    /** Kotlin is coloured by the same annotator, through the same scan. */
    fun testKotlinIsColouredTheSameWay() {
        SolrCodeFixtures.givenSolrJ(myFixture)

        val coloured = colouredIn(
            "Search.kt",
            """
            import org.apache.solr.client.solrj.SolrQuery
            fun go() {
                val q = SolrQuery("*:*")
                q.setQuery("category:books AND price:9")
            }
            """.trimIndent(),
        )

        assertEquals(listOf("category", "AND", "price"), coloured)
    }

    // --- that the colour can be seen ---------------------------------------------------------------

    /** The key the annotator painted over each of [parts], read back from the highlighting pass. */
    private fun keysPainting(parts: List<String>): Map<String, TextAttributesKey> {
        val document = myFixture.editor.document.charsSequence
        return myFixture.doHighlighting()
            .filter { it.forcedTextAttributesKey != null }
            .associate { document.subSequence(it.startOffset, it.endOffset).toString() to it.forcedTextAttributesKey!! }
            .filterKeys { it in parts }
    }

    /**
     * What a reader actually sees: the key's own foreground, or the string's where the key has none.
     *
     * An annotation is layered over the lexer's colour, so a key that sets no foreground does not
     * repaint anything — the text stays the colour of the string around it. That is the failure the
     * tester reported: a key that is a colour in the abstract and invisible inside a literal.
     */
    private fun visibleForeground(scheme: EditorColorsScheme, key: TextAttributesKey, string: Color?): Color? =
        scheme.getAttributes(key)?.foregroundColor ?: string

    /**
     * The schemes a reader of this plugin is likely to be looking at.
     *
     * The classic pair is registered in a test IDE; the New UI pair and *Islands Dark* — the editor
     * scheme of the theme a fresh 2026.2 install opens in, and the one the sandbox report came from —
     * are not, so they are read from the platform's own bundled definitions over their declared
     * parents. A resource that moves on a platform upgrade fails here by name rather than quietly
     * narrowing what is checked.
     */
    private fun schemesToCheck(): List<EditorColorsScheme> {
        val manager = EditorColorsManager.getInstance()
        val default = requireNotNull(manager.getScheme(EditorColorsScheme.getDefaultSchemeName()))
        val darcula = requireNotNull(manager.getScheme("Darcula")) { "have ${manager.allSchemes.map { it.name }}" }
        fun bundled(resource: String, parent: EditorColorsScheme): EditorColorsScheme {
            val stream = requireNotNull(EditorColorsManager::class.java.classLoader.getResourceAsStream(resource)) {
                "the platform no longer bundles $resource"
            }
            return EditorColorsSchemeImpl(parent).apply { stream.use { readExternal(JDOMUtil.load(it)) } }
        }
        return listOf(
            default,
            darcula,
            bundled("themes/expUI/expUI_lightScheme.xml", default),
            bundled("themes/expUI/expUI_darkScheme.xml", darcula),
            bundled("themes/islands/IslandSchemeDark.xml", darcula),
        )
    }

    /**
     * Inside a string literal, in the light schemes and the dark ones, a field and an operator are
     * each a colour of their own — distinct from the string, from plain text, and from each other.
     *
     * **The sandbox report this pins: `AND` showed no colour.** The fields did, because they borrowed
     * the keyword colour; the operator borrowed the operator-sign colour, which the classic schemes
     * leave unset — so it drew in the string's own colour — and which the New UI and Islands schemes
     * set to the plain-text grey, so it read as ordinary text. Either way the annotation was there and
     * could not be seen. Asserting the boundaries alone, as the other tests here do, never catches it.
     */
    fun testFieldsAndOperatorsAreVisiblyDistinctInsideAStringInLightAndDarkSchemes() {
        SolrCodeFixtures.givenSolrJ(myFixture)
        myFixture.configureByText("Search.java", searching("""q.setQuery("category:books AND price:9");"""))

        val keys = keysPainting(listOf("category", "AND"))
        val field = keys["category"] ?: return fail("nothing painted the field: $keys")
        val operator = keys["AND"] ?: return fail("nothing painted the operator: $keys")

        for (scheme in schemesToCheck()) {
            val name = scheme.name
            val string = scheme.getAttributes(JavaHighlightingColors.STRING)?.foregroundColor
            val text = scheme.defaultForeground
            val fieldColour = visibleForeground(scheme, field, string)
            val operatorColour = visibleForeground(scheme, operator, string)

            assertFalse("$name: the field draws in the string's colour ($string)", fieldColour == string)
            assertFalse("$name: the operator draws in the string's colour ($string)", operatorColour == string)
            // Kotlin's string key falls back to the platform's rather than to Java's, so a scheme that
            // coloured the two differently would put a Kotlin query on a different background colour.
            val anyString = scheme.getAttributes(DefaultLanguageHighlighterColors.STRING)?.foregroundColor
            assertFalse("$name: the field draws in a Kotlin string's colour", fieldColour == anyString)
            assertFalse("$name: the operator draws in a Kotlin string's colour", operatorColour == anyString)
            assertFalse("$name: the field draws as plain text ($text)", fieldColour == text)
            assertFalse("$name: the operator draws as plain text ($text)", operatorColour == text)
            assertFalse("$name: the field and the operator are one colour ($fieldColour)", fieldColour == operatorColour)
        }
    }

    // --- what it leaves alone -----------------------------------------------------------------------

    /**
     * A field list is not a query, and nothing in it is coloured.
     *
     * `fl` holds names, not clauses. Colouring them as fields would be true of the names and false
     * of the structure — there are no clauses and no operators there to see.
     */
    fun testAFieldListIsNotColoured() {
        SolrCodeFixtures.givenSolrJ(myFixture)

        assertEmpty(colouredIn("Search.java", searching("""q.setFields("id,category");""")))
    }

    /** An ordinary string that is no argument of a Solr call is left alone. */
    fun testAnUnrelatedStringIsNotColoured() {
        SolrCodeFixtures.givenSolrJ(myFixture)

        assertEmpty(colouredIn("Search.java", searching("""int n = 1; q.setRows(n);""")))
    }

    /**
     * A module with no Solr client is not coloured, however the string is spelled.
     *
     * The annotator is offered every element of every file in the IDE, so this is the case that
     * decides whether it is quiet in the projects that have never heard of Solr.
     */
    fun testAModuleWithoutASolrClientIsNotColoured() {
        SolrCodeFixtures.givenSolrJ(myFixture)
        givenNoSolrOnTheClasspath()

        assertEmpty(colouredIn("Search.java", searching("""q.setQuery("category:books AND price:9");""")))
    }
}
