package org.apache.solr.ide.code

import com.intellij.spring.profiles.SpringProfilesService
import java.io.File
import org.apache.solr.ide.configset.activation.SolrConfigsetTestCase

/**
 * Finding the Solr servers a project talks to, end to end, on the demo's own shape.
 *
 * The demo is a Spring Boot application whose client bean takes `@Value("${app.solr.url}")`, with the
 * URL and a username in a `dev` and a `staging` profile. What this asserts is the plan's acceptance
 * for the step: the URL is reached by following that reference into the active profile, switching
 * the profile changes the username with it, and nothing is found where no Spring Boot is in play.
 */
class SolrEndpointDiscoveryTest : SolrConfigsetTestCase() {

    private fun givenSolrJAndSpringStubs() {
        myFixture.addFileToProject(
            "org/apache/solr/client/solrj/impl/Http2SolrClient.java",
            """
            package org.apache.solr.client.solrj.impl;
            public class Http2SolrClient {
                public static class Builder {
                    public Builder(String baseUrl) {}
                    public Builder withBasicAuthCredentials(String user, String password) { return this; }
                    public Http2SolrClient build() { return null; }
                }
            }
            """.trimIndent(),
        )
        myFixture.addFileToProject(
            "org/springframework/beans/factory/annotation/Value.java",
            """
            package org.springframework.beans.factory.annotation;
            public @interface Value { String value(); }
            """.trimIndent(),
        )
    }

    private fun givenTheDemoClientBean() {
        myFixture.addFileToProject(
            "com/example/demo/SolrConfig.java",
            """
            package com.example.demo;
            import org.apache.solr.client.solrj.impl.Http2SolrClient;
            import org.springframework.beans.factory.annotation.Value;
            class SolrConfig {
                Http2SolrClient solrClient(@Value("${'$'}{app.solr.url}") String url) {
                    return new Http2SolrClient.Builder(url).build();
                }
            }
            """.trimIndent(),
        )
    }

    private fun givenTheDemoProfiles() {
        myFixture.addFileToProject(
            "application.yml",
            """
            spring:
              profiles:
                active: dev
            ---
            spring:
              config:
                activate:
                  on-profile: dev
            app:
              solr:
                url: http://localhost:8983/solr
                username: dev-user
            ---
            spring:
              config:
                activate:
                  on-profile: staging
            app:
              solr:
                url: http://solr-staging.internal:8983/solr
                username: staging-user
            """.trimIndent(),
        )
    }

    private fun givenSpringBoot() = givenLibrary("Gradle: org.springframework.boot:spring-boot:3.5.6")

    private fun discovered() = SolrEndpointDiscovery.candidatesIn(project)

    override fun tearDown() {
        try {
            SpringProfilesService.getInstance(project).setActiveProfiles(module, emptySet())
            // The light project outlives the test, and a Spring Boot library left on it would make the
            // next test's "without Spring Boot" pass for the wrong reason — or fail for one.
            givenNoSolrOnTheClasspath()
        } finally {
            super.tearDown()
        }
    }

    // --- the demo -----------------------------------------------------------------------------------

    fun testTheDemoOffersOneRowPerProfileWithItsUsername() {
        givenSolrJAndSpringStubs()
        givenSpringBoot()
        givenTheDemoClientBean()
        givenTheDemoProfiles()

        assertEquals(
            listOf(
                Triple("dev", "http://localhost:8983/solr", "dev-user"),
                Triple("staging", "http://solr-staging.internal:8983/solr", "staging-user"),
            ),
            discovered().map { Triple(it.profile, it.url, it.username) },
        )
        assertTrue("the configuration activates dev", discovered().first().active)
    }

    /**
     * The profile the user chose in the IDE decides which row is offered first.
     *
     * The plan's criterion on the demo fixture: switching to `staging` offers staging's URL *and*
     * staging's user, together, without anything in the configuration changing.
     */
    fun testTheProfileChosenInTheIdeIsOfferedFirst() {
        givenSolrJAndSpringStubs()
        givenSpringBoot()
        givenTheDemoClientBean()
        givenTheDemoProfiles()

        SpringProfilesService.getInstance(project).setActiveProfiles(module, setOf("staging"))
        val offered = discovered().first()

        assertEquals("staging", offered.profile)
        assertEquals("http://solr-staging.internal:8983/solr", offered.url)
        assertEquals("staging-user", offered.username)
    }

    /**
     * The same, on the demo's own files rather than a copy of their shape.
     *
     * Read from `demo/` on disk, so that the plan's "asserted on the demo fixture" is literally true
     * and a change to the demo that broke discovery would fail here rather than on stage.
     */
    fun testTheDemoItselfOffersEachProfilesUser() {
        givenSolrJAndSpringStubs()
        givenSpringBoot()
        val demo = File("demo/src/main")
        myFixture.addFileToProject(
            "com/example/demo/SolrConfig.java",
            File(demo, "java/com/example/demo/SolrConfig.java").readText(),
        )
        myFixture.addFileToProject("application.yml", File(demo, "resources/application.yml").readText())

        assertEquals(
            listOf("dev" to "dev-reader", "staging" to "staging-reader"),
            discovered().map { it.profile to it.username },
        )

        SpringProfilesService.getInstance(project).setActiveProfiles(module, setOf("staging"))
        assertEquals("http://solr-staging.internal:8983/solr" to "staging-reader", discovered().first().let { it.url to it.username })
    }

    // --- plain SolrJ --------------------------------------------------------------------------------

    /** A client built from a literal URL is offered as itself, with no framework involved. */
    fun testALiteralClientUrlIsOfferedWithoutAnyFramework() {
        givenSolrJAndSpringStubs()
        myFixture.addFileToProject(
            "com/example/Plain.java",
            """
            package com.example;
            import org.apache.solr.client.solrj.impl.Http2SolrClient;
            class Plain {
                Http2SolrClient client() { return new Http2SolrClient.Builder("http://localhost:8983/solr").build(); }
            }
            """.trimIndent(),
        )

        val offered = discovered().single()
        assertEquals("http://localhost:8983/solr", offered.url)
        assertNull(offered.profile)
        assertTrue(offered.origin, "Plain.java" in offered.origin)
    }

    // --- silence ------------------------------------------------------------------------------------

    /** Without Spring Boot on the module, its configuration files mean nothing and are not read. */
    fun testSpringConfigurationIsNotReadWithoutSpringBoot() {
        givenSolrJAndSpringStubs()
        givenTheDemoClientBean()
        givenTheDemoProfiles()

        assertEmpty(discovered())
    }

    /** Without a Solr client, nothing is offered however Solr-shaped the configuration looks. */
    fun testNothingIsOfferedWithoutASolrClient() {
        givenSolrJAndSpringStubs()
        givenSpringBoot()
        givenTheDemoClientBean()
        givenTheDemoProfiles()
        givenNoSolrOnTheClasspath()

        assertEmpty(discovered())
    }

    /**
     * A Quarkus project behaves exactly as one with no framework support.
     *
     * The plan's guard on what this step released: Quarkus's inline `%dev.` profile and its
     * `@ConfigProperty` injection are Step 31's, and reading either here — even partly — would ship
     * Quarkus resolution nobody tested as such.
     */
    fun testAQuarkusProjectIsOfferedNothing() {
        givenSolrJAndSpringStubs()
        givenLibrary("Gradle: io.quarkus:quarkus-core:3.15.1")
        myFixture.addFileToProject(
            "com/example/QuarkusConfig.java",
            """
            package com.example;
            import org.apache.solr.client.solrj.impl.Http2SolrClient;
            class QuarkusConfig {
                Http2SolrClient solrClient(@ConfigProperty(name = "app.solr.url") String url) {
                    return new Http2SolrClient.Builder(url).build();
                }
            }
            """.trimIndent(),
        )
        myFixture.addFileToProject("application.properties", "%dev.app.solr.url=http://localhost:8983/solr")

        assertEmpty(discovered())
    }
}
