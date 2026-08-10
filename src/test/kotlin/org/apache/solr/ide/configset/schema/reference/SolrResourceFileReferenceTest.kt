package org.apache.solr.ide.configset.schema.reference

import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.impl.source.resolve.reference.impl.providers.FileReference
import org.apache.solr.ide.configset.activation.SolrConfigsetTestCase

/**
 * Navigation from a filter's resource attribute to the file it names.
 *
 * The activation layer recognizes `stopwords.txt` and its siblings only inside an
 * already-identified configset, precisely so this navigation can exist without the plugin
 * activating on every NLP project that happens to have a file of that name. These tests are the
 * navigation half of that bargain.
 */
class SolrResourceFileReferenceTest : SolrConfigsetTestCase() {

    private fun schema(analyzer: String) = """
        <schema name="products">
          <fieldType name="text" class="solr.TextField">
            <analyzer>
              <tokenizer class="solr.StandardTokenizerFactory"/>
              $analyzer
            </analyzer>
          </fieldType>
        </schema>
    """.trimIndent()

    private fun resolveAtCaret(analyzer: String): PsiElement? {
        myFixture.addFileToProject("stopwords.txt", "the\n")
        myFixture.addFileToProject("synonyms.txt", "tv, television\n")
        myFixture.addFileToProject("protwords.txt", "solr\n")
        myFixture.addFileToProject("lang/stopwords_en.txt", "a\n")
        myFixture.addFileToProject("lang/contractions_fr.txt", "l\n")
        myFixture.addFileToProject("mapping-ISOLatin1Accent.txt", "\"é\" => \"e\"\n")
        myFixture.addFileToProject("en_GB.dic", "1\nhello\n")
        myFixture.configureByText("managed-schema.xml", schema(analyzer))
        return myFixture.getReferenceAtCaretPosition()?.resolve()
    }

    fun testWordsResolvesToTheFileItNames() {
        val target = resolveAtCaret("""<filter class="solr.StopFilterFactory" words="stop<caret>words.txt"/>""")
        assertEquals("stopwords.txt", (target as PsiFile).name)
    }

    fun testSynonymsResolvesToTheFileItNames() {
        val target = resolveAtCaret("""<filter class="solr.SynonymGraphFilterFactory" synonyms="syno<caret>nyms.txt"/>""")
        assertEquals("synonyms.txt", (target as PsiFile).name)
    }

    fun testProtectedResolvesToTheFileItNames() {
        val target = resolveAtCaret("""<filter class="solr.PorterStemFilterFactory" protected="prot<caret>words.txt"/>""")
        assertEquals("protwords.txt", (target as PsiFile).name)
    }

    /** Solr ships per-language resources under `lang/`, referenced with the directory in the path. */
    fun testAPathResolvesThroughTheLangDirectory() {
        val target = resolveAtCaret(
            """<filter class="solr.StopFilterFactory" words="lang/stopwords<caret>_en.txt"/>""",
        )
        assertEquals("stopwords_en.txt", (target as PsiFile).name)
    }

    /** `mapping` sits on a `charFilter` — the other analyzer component that reads resources. */
    fun testMappingOnACharFilterResolvesToTheFileItNames() {
        val target = resolveAtCaret(
            """<charFilter class="solr.MappingCharFilterFactory" mapping="mapping-ISO<caret>Latin1Accent.txt"/>""",
        )
        assertEquals("mapping-ISOLatin1Accent.txt", (target as PsiFile).name)
    }

    fun testArticlesResolvesToTheFileItNames() {
        val target = resolveAtCaret(
            """<filter class="solr.ElisionFilterFactory" articles="lang/contract<caret>ions_fr.txt"/>""",
        )
        assertEquals("contractions_fr.txt", (target as PsiFile).name)
    }

    /** The Hunspell pair names two files; `dictionary` is the representative under test. */
    fun testAHunspellDictionaryResolvesToTheFileItNames() {
        val target = resolveAtCaret(
            """<filter class="solr.HunspellStemFilterFactory" dictionary="en_<caret>GB.dic" affix="en_GB.aff"/>""",
        )
        assertEquals("en_GB.dic", (target as PsiFile).name)
    }

    /** `words` accepts a comma-separated list; each entry is its own reference. */
    fun testEachFileInACommaSeparatedListResolvesOnItsOwn() {
        val target = resolveAtCaret(
            """<filter class="solr.StopFilterFactory" words="stopwords.txt,lang/stopwords<caret>_en.txt"/>""",
        )
        assertEquals("stopwords_en.txt", (target as PsiFile).name)
    }

    /**
     * Soft, like every reference in this package: a missing resource is a real problem, but the
     * platform's unresolved-reference warning is not the voice to report it in, and no inspection
     * for it exists yet.
     */
    fun testAMissingFileResolvesToNothingAndTheReferenceIsSoft() {
        myFixture.addFileToProject("stopwords.txt", "the\n")
        myFixture.configureByText(
            "managed-schema.xml",
            schema("""<filter class="solr.StopFilterFactory" words="no<caret>pe.txt"/>"""),
        )
        val reference = myFixture.getReferenceAtCaretPosition()
        assertNotNull(reference)
        assertNull(reference!!.resolve())
        assertTrue("the reference must be soft", (reference as FileReference).isSoft)
    }

    /** `format="snowball"` is a keyword, not a path; offering a file reference on it would lie. */
    fun testAnUnrelatedAttributeOffersNoFileReference() {
        myFixture.addFileToProject("stopwords.txt", "the\n")
        myFixture.configureByText(
            "managed-schema.xml",
            schema("""<filter class="solr.StopFilterFactory" words="stopwords.txt" format="snow<caret>ball"/>"""),
        )
        assertNull(myFixture.getReferenceAtCaretPosition())
    }

    /** Outside a configset the attribute is just XML, whatever the file contains. */
    fun testOutsideAConfigsetNothingIsOffered() {
        myFixture.addFileToProject("stopwords.txt", "the\n")
        myFixture.configureByText(
            "notes.xml",
            schema("""<filter class="solr.StopFilterFactory" words="stop<caret>words.txt"/>"""),
        )
        assertNull(myFixture.getReferenceAtCaretPosition())
    }
}
