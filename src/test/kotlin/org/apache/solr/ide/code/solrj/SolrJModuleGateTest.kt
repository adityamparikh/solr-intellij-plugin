package org.apache.solr.ide.code.solrj

import com.intellij.openapi.module.Module
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.testFramework.HeavyPlatformTestCase
import com.intellij.testFramework.PsiTestUtil
import com.intellij.testFramework.VfsTestUtil
import org.apache.solr.ide.code.SolrRecognizers
import org.apache.solr.ide.configset.activation.SolrProjectDetector

/**
 * That the recognizer runs in the module carrying a Solr client and in no other.
 *
 * **One module cannot test this and never could.** The rest of the suite asserts that removing the
 * client silences the recognizer — which a gate reading the *project* passes just as happily as one
 * reading the module. The two answers differ only where a repository holds both kinds of module at
 * once, which is the ordinary shape of anything larger than a sample.
 *
 * Getting it wrong is not a missing warning but a warning in the wrong place: field names reported
 * against a module whose authors have never heard of Solr.
 *
 * **Heavy rather than light, and not by preference.** The plan proposed a `LightProjectDescriptor`
 * carrying a second module as the cheap route. There is no cheap route: a light project refuses
 * outright — *"Adding modules is not permitted in light tests"* — and its in-memory file system
 * cannot give a module the real path its `.iml` needs. Two modules mean a real project, which is
 * what this builds. It is the only heavy test in the suite, and it buys the one claim nothing
 * lighter can make.
 */
class SolrJModuleGateTest : HeavyPlatformTestCase() {

    private lateinit var withClientRoot: VirtualFile
    private lateinit var withoutClientRoot: VirtualFile

    override fun setUp() {
        super.setUp()
        val withClient: Module = module
        withClientRoot = sourceRootFor(withClient, "searching")
        withoutClientRoot = sourceRootFor(createModule("stranger"), "stranger")

        // The library carries no jar: the gate matches the name a build tool would generate, which
        // is what makes it answerable without an index.
        PsiTestUtil.addProjectLibrary(withClient, "Gradle: org.apache.solr:solr-solrj:9.10.0", emptyList())
        SolrProjectDetector.getInstance(project).dropCache()

        // Declared in both modules, so the class resolves on either side and the only thing that can
        // separate the two files is which module owns them.
        listOf(withClientRoot, withoutClientRoot).forEach { root ->
            VfsTestUtil.createFile(
                root,
                "org/apache/solr/client/solrj/SolrQuery.java",
                """
                package org.apache.solr.client.solrj;
                public class SolrQuery {
                    public SolrQuery(String q) {}
                    public SolrQuery addFilterQuery(String... fq) { return this; }
                }
                """.trimIndent(),
            )
        }
    }

    private fun sourceRootFor(target: Module, name: String): VirtualFile {
        val root = VfsTestUtil.createDir(getOrCreateProjectBaseDir(), name)
        PsiTestUtil.addSourceContentToRoots(target, root)
        return root
    }

    private fun fileIn(root: VirtualFile, name: String, text: String): PsiFile =
        PsiManager.getInstance(project).findFile(VfsTestUtil.createFile(root, name, text))!!

    private val searching = """
        import org.apache.solr.client.solrj.SolrQuery;
        class Search {
            void go() {
                SolrQuery q = new SolrQuery("*:*");
                q.addFilterQuery("categry:books");
            }
        }
    """.trimIndent()

    /**
     * The module with the client is read; the module beside it is not.
     *
     * The two files are byte-identical, so module membership is the only thing that can separate
     * them — which is exactly the claim.
     */
    fun testOnlyTheModuleCarryingTheClientIsRead() {
        val ours = fileIn(withClientRoot, "Search.java", searching)
        val theirs = fileIn(withoutClientRoot, "Search.java", searching)

        assertEquals(listOf("categry"), SolrRecognizers.fieldUsagesIn(ours).map { it.fieldName })
        assertEmpty(SolrRecognizers.fieldUsagesIn(theirs))
    }

    /**
     * Endpoints obey the same gate as field names.
     *
     * Stated separately because they are read through a different entry point, and a gate applied to
     * one of the two would leak a server URL out of a module that never named one.
     */
    fun testTheGateCoversEndpointsToo() {
        listOf(withClientRoot, withoutClientRoot).forEach { root ->
            VfsTestUtil.createFile(
                root,
                "org/apache/solr/client/solrj/impl/Http2SolrClient.java",
                """
                package org.apache.solr.client.solrj.impl;
                public class Http2SolrClient {
                    public static class Builder {
                        public Builder(String url) {}
                        public Http2SolrClient build() { return new Http2SolrClient(); }
                    }
                }
                """.trimIndent(),
            )
        }
        val connecting = """
            import org.apache.solr.client.solrj.impl.Http2SolrClient;
            class Connect {
                void go() {
                    new Http2SolrClient.Builder("http://localhost:8983/solr").build();
                }
            }
        """.trimIndent()

        val ours = fileIn(withClientRoot, "Connect.java", connecting)
        val theirs = fileIn(withoutClientRoot, "Connect.java", connecting)

        assertSize(1, SolrRecognizers.endpointsIn(ours))
        assertEmpty(SolrRecognizers.endpointsIn(theirs))
    }
}
