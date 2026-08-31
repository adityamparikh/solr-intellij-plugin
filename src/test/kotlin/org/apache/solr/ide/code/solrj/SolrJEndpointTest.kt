package org.apache.solr.ide.code.solrj

import com.intellij.psi.PsiFile
import org.apache.solr.ide.code.SolrRecognizers
import org.apache.solr.ide.configset.activation.SolrConfigsetTestCase

/**
 * Reading the server a piece of code constructs a client against.
 *
 * The stubs mirror SolrJ's real arrangement rather than a convenient one, because the arrangement is
 * what the recognizer matches on: a `Builder` nested inside a class in `org.apache.solr.client.solrj.impl`
 * whose name ends in `SolrClient`. Two of the classes below exist only in Solr 9 and one spans 9 and
 * 10, which is the situation the shape rule exists to survive.
 */
class SolrJEndpointTest : SolrConfigsetTestCase() {

    private fun givenSolrJClients() {
        // The Solr 9 spelling, removed in 10.
        addClient("Http2SolrClient")
        // Present in both supported lines.
        addClient("HttpJdkSolrClient")
        myFixture.addFileToProject(
            "org/apache/solr/client/solrj/impl/CloudSolrClient.java",
            """
            package org.apache.solr.client.solrj.impl;
            import java.util.List;
            public class CloudSolrClient {
                public static class Builder {
                    public Builder(List<String> zkHosts) {}
                    public CloudSolrClient build() { return null; }
                }
            }
            """.trimIndent(),
        )
    }

    private fun addClient(name: String) {
        myFixture.addFileToProject(
            "org/apache/solr/client/solrj/impl/$name.java",
            """
            package org.apache.solr.client.solrj.impl;
            public class $name {
                public static class Builder {
                    public Builder(String baseUrl) {}
                    public Builder withBasicAuthCredentials(String user, String password) { return this; }
                    public $name build() { return null; }
                }
            }
            """.trimIndent(),
        )
    }

    private fun javaFile(body: String, name: String): PsiFile =
        myFixture.addFileToProject(
            "src/$name.java",
            """
            import org.apache.solr.client.solrj.impl.*;
            import java.util.List;
            class $name {
                void go() {
                    $body
                }
            }
            """.trimIndent(),
        )

    private fun kotlinFile(body: String, name: String): PsiFile =
        myFixture.addFileToProject(
            "src/$name.kt",
            """
            import org.apache.solr.client.solrj.impl.Http2SolrClient
            fun go$name() {
                $body
            }
            """.trimIndent(),
        )

    private fun endpoints(file: PsiFile) = SolrRecognizers.endpointsIn(file)

    // --- what is read -----------------------------------------------------------------------------

    /** A client built from a spelled-out URL names a server. */
    fun testAClientBuiltFromALiteralUrlNamesItsServer() {
        givenSolrJClients()
        val found = endpoints(
            javaFile("""new Http2SolrClient.Builder("http://localhost:8983/solr").build();""", "Plain"),
        )

        assertEquals(listOf("http://localhost:8983/solr"), found.map { it.url })
        assertNull("nothing in this source names a user", found.single().username)
    }

    /**
     * The credential chained onto the builder travels with the URL.
     *
     * The case the endpoint type carries a username for: the server and the identity are written in
     * one expression, and a reader returning only the URL would send every consumer back to the
     * source to ask who to connect as.
     */
    fun testAChainedCredentialNamesTheUser() {
        givenSolrJClients()
        val found = endpoints(
            javaFile(
                """
                new Http2SolrClient.Builder("http://localhost:8983/solr")
                    .withBasicAuthCredentials("solr", "SolrRocks")
                    .build();
                """.trimIndent(),
                "Credentialed",
            ),
        )

        assertEquals("solr", found.single().username)
        assertEquals("http://localhost:8983/solr", found.single().url)
    }

    /** The client that spans both supported lines is recognized by the same rule. */
    fun testTheClientCommonToBothLinesIsRecognized() {
        givenSolrJClients()
        val found = endpoints(
            javaFile("""new HttpJdkSolrClient.Builder("http://solr.internal:8983/solr").build();""", "Jdk"),
        )

        assertEquals(listOf("http://solr.internal:8983/solr"), found.map { it.url })
    }

    /** The same construction in Kotlin reads the same, which is the parity the interface promises. */
    fun testTheSameConstructionReadsInKotlin() {
        givenSolrJClients()
        val found = endpoints(
            kotlinFile(
                """
                Http2SolrClient.Builder("http://localhost:8983/solr")
                    .withBasicAuthCredentials("solr", "SolrRocks")
                    .build()
                """.trimIndent(),
                "Kt",
            ),
        )

        assertEquals(listOf("http://localhost:8983/solr"), found.map { it.url })
        assertEquals("solr", found.single().username)
    }

    // --- silence ----------------------------------------------------------------------------------

    /**
     * A cloud client names ZooKeeper hosts, not an endpoint.
     *
     * Excluded by the rule that admits the others rather than by naming it: its builder takes a list,
     * so no argument spells out a URL.
     */
    fun testACloudClientNamesNoEndpoint() {
        givenSolrJClients()
        assertEmpty(
            endpoints(javaFile("""new CloudSolrClient.Builder(List.of("zk1:2181")).build();""", "Cloud")),
        )
    }

    /** A `Builder` belonging to something that is not a Solr client is not read. */
    fun testAForeignBuilderIsNotRead() {
        givenSolrJClients()
        myFixture.addFileToProject(
            "com/example/HttpClient.java",
            """
            package com.example;
            public class HttpClient {
                public static class Builder {
                    public Builder(String baseUrl) {}
                }
            }
            """.trimIndent(),
        )
        val file = myFixture.addFileToProject(
            "src/Foreign.java",
            """
            import com.example.HttpClient;
            class Foreign {
                void go() { new HttpClient.Builder("http://localhost:8983/solr"); }
            }
            """.trimIndent(),
        )
        assertEmpty(endpoints(file))
    }

    /**
     * A builder in SolrJ's own `impl` package that is not a client is not read.
     *
     * The boundary of the shape rule, and a real class rather than an invented one: `SolrClientCache`
     * lives beside the clients, in the package the rule matches on. Being in the right package is not
     * enough — the name has to end in `SolrClient`, because what is wanted is a client and not
     * everything shipped near one.
     */
    fun testANonClientBuilderInSolrJsOwnPackageIsNotRead() {
        givenSolrJClients()
        myFixture.addFileToProject(
            "org/apache/solr/client/solrj/impl/SolrClientCache.java",
            """
            package org.apache.solr.client.solrj.impl;
            public class SolrClientCache {
                public static class Builder {
                    public Builder(String baseUrl) {}
                }
            }
            """.trimIndent(),
        )
        assertEmpty(endpoints(javaFile("""new SolrClientCache.Builder("http://localhost:8983/solr");""", "Cache")))
    }

    /**
     * A constructor in that package that is not a `Builder` at all is not read.
     *
     * The other half of the same boundary. SolrJ's clients are constructed through their builders,
     * so a direct construction of something else in the package names no endpoint however its
     * argument reads.
     */
    fun testANonBuilderConstructionIsNotRead() {
        givenSolrJClients()
        myFixture.addFileToProject(
            "org/apache/solr/client/solrj/impl/SolrClientHolder.java",
            """
            package org.apache.solr.client.solrj.impl;
            public class SolrClientHolder {
                public SolrClientHolder(String baseUrl) {}
            }
            """.trimIndent(),
        )
        assertEmpty(endpoints(javaFile("""new SolrClientHolder("http://localhost:8983/solr");""", "Holder")))
    }

    /** A URL held in a variable is not followed, exactly as a field name is not. */
    fun testAVariableUrlIsNotRead() {
        givenSolrJClients()
        assertEmpty(
            endpoints(
                javaFile(
                    """String url = "http://localhost:8983/solr"; new Http2SolrClient.Builder(url).build();""",
                    "Variable",
                ),
            ),
        )
    }

    /** No Solr client on the module, no reading — the same gate the field half passes. */
    fun testAModuleWithNoSolrClientIsNotRead() {
        givenSolrJClients()
        val file = javaFile("""new Http2SolrClient.Builder("http://localhost:8983/solr").build();""", "Gated")
        givenNoSolrOnTheClasspath()
        assertEmpty(endpoints(file))
    }
}
