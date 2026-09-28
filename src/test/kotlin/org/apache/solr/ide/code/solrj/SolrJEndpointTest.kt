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

    // --- a URL written as a property reference ------------------------------------------------------

    /**
     * A URL injected from configuration is reported as the reference the source spells.
     *
     * The shape the demo uses, and the one most applications do: the client bean takes its URL from
     * `@Value("${app.solr.url}")`, so the literal lives in a profile file rather than here. What this
     * file *does* say is which property to follow, and reporting that is what lets a framework reader
     * resolve it per profile without walking the code a second time.
     */
    fun testAnAnnotatedParameterIsReportedAsItsPropertyReference() {
        givenSolrJClients()
        val file = myFixture.addFileToProject(
            "src/Config.java",
            """
            import org.apache.solr.client.solrj.impl.*;
            class Config {
                Http2SolrClient solrClient(@Value("${'$'}{app.solr.url}") String url) {
                    return new Http2SolrClient.Builder(url).build();
                }
            }
            """.trimIndent(),
        )

        assertEquals(listOf("${'$'}{app.solr.url}"), endpoints(file).map { it.url })
    }

    /** A field injected the same way is followed the same way, and so is the username beside it. */
    fun testAnAnnotatedFieldAndUsernameAreReportedAsReferences() {
        givenSolrJClients()
        val file = myFixture.addFileToProject(
            "src/FieldConfig.java",
            """
            import org.apache.solr.client.solrj.impl.*;
            class FieldConfig {
                @Value("${'$'}{search.endpoint}") String url;
                @Value("${'$'}{search.user}") String user;
                Http2SolrClient solrClient() {
                    return new Http2SolrClient.Builder(url).withBasicAuthCredentials(user, "x").build();
                }
            }
            """.trimIndent(),
        )

        val found = endpoints(file).single()
        assertEquals("${'$'}{search.endpoint}", found.url)
        assertEquals("${'$'}{search.user}", found.username)
    }

    /**
     * The Kotlin spelling — an escaped dollar in the annotation — reads the same.
     *
     * Spring's annotation is stubbed here, as SolrJ's clients are: Kotlin maps a positional
     * annotation argument to `value` only when it can see the annotation's declaration, which in a
     * real project it always can.
     */
    fun testAnAnnotatedKotlinParameterIsReportedAsItsPropertyReference() {
        givenSolrJClients()
        myFixture.addFileToProject(
            "org/springframework/beans/factory/annotation/Value.java",
            """
            package org.springframework.beans.factory.annotation;
            public @interface Value { String value(); }
            """.trimIndent(),
        )
        val file = myFixture.addFileToProject(
            "src/KtConfig.kt",
            """
            import org.apache.solr.client.solrj.impl.Http2SolrClient
            import org.springframework.beans.factory.annotation.Value
            class KtConfig {
                fun solrClient(@Value("\${'$'}{app.solr.url}") url: String) = Http2SolrClient.Builder(url).build()
            }
            """.trimIndent(),
        )

        assertEquals(listOf("${'$'}{app.solr.url}"), endpoints(file).map { it.url })
    }

    /**
     * An annotation whose value is not a whole property reference is not a URL.
     *
     * `@Named("solr")` on the parameter says which bean, not which server, and reporting it would
     * offer a connection to a server called `solr`.
     */
    fun testAnAnnotationThatIsNotAPropertyReferenceIsNotRead() {
        givenSolrJClients()
        val file = myFixture.addFileToProject(
            "src/Named.java",
            """
            import org.apache.solr.client.solrj.impl.*;
            class Named {
                Http2SolrClient solrClient(@Named("solr") String url) {
                    return new Http2SolrClient.Builder(url).build();
                }
            }
            """.trimIndent(),
        )

        assertEmpty(endpoints(file))
    }

    /** No Solr client on the module, no reading — the same gate the field half passes. */
    fun testAModuleWithNoSolrClientIsNotRead() {
        givenSolrJClients()
        val file = javaFile("""new Http2SolrClient.Builder("http://localhost:8983/solr").build();""", "Gated")
        givenNoSolrOnTheClasspath()
        assertEmpty(endpoints(file))
    }
}
