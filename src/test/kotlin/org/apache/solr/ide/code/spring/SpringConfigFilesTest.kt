package org.apache.solr.ide.code.spring

import com.intellij.testFramework.fixtures.BasePlatformTestCase

/**
 * Reading a Spring Boot application's configuration files into property sources.
 *
 * Through the IDE's own YAML and properties parsers, so what is asserted is the flattening and the
 * profile each block belongs to — the parts Spring decides and those parsers do not.
 */
class SpringConfigFilesTest : BasePlatformTestCase() {

    private fun sources() = SpringConfigFiles.sourcesIn(module)

    // --- YAML ---------------------------------------------------------------------------------------

    /** Every document of a multi-document file is read, each under the profile it activates on. */
    fun testEachYamlDocumentIsReadUnderItsProfile() {
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
            """.trimIndent(),
        )

        val found = sources()

        assertEquals(listOf(null, "dev"), found.map { it.profile })
        assertEquals("dev", found[0].properties["spring.profiles.active"])
        assertEquals("http://localhost:8983/solr", found[1].properties["app.solr.url"])
    }

    /** The key Spring Boot 2.3 and earlier used to name a document's profile is read the same way. */
    fun testTheLegacyProfileKeyNamesTheDocument() {
        myFixture.addFileToProject(
            "application.yml",
            """
            spring:
              profiles: staging
            app.solr.url: http://solr-staging.internal:8983/solr
            """.trimIndent(),
        )

        val found = sources().single()

        assertEquals("staging", found.profile)
        assertEquals("http://solr-staging.internal:8983/solr", found.properties["app.solr.url"])
    }

    /** A profile file is that profile's, and is applied after the profile-less file. */
    fun testAProfileFileBelongsToItsProfileAndComesAfterTheDefault() {
        myFixture.addFileToProject("application-staging.yml", "app.solr.url: http://staging:8983/solr")
        myFixture.addFileToProject("application.yml", "app.solr.url: http://localhost:8983/solr")

        assertEquals(listOf(null, "staging"), sources().map { it.profile })
    }

    /** A list is not a single value, and nothing here reads one as though it were. */
    fun testAListIsNotReadAsAValue() {
        myFixture.addFileToProject(
            "application.yml",
            """
            app:
              solr:
                urls:
                  - http://one:8983/solr
                  - http://two:8983/solr
                url: http://localhost:8983/solr
            """.trimIndent(),
        )

        assertEquals(mapOf("app.solr.url" to "http://localhost:8983/solr"), sources().single().properties)
    }

    // --- properties ---------------------------------------------------------------------------------

    /** A `.properties` file splits into documents on `#---`, as Spring Boot 2.4 and later read it. */
    fun testAPropertiesFileSplitsOnItsDocumentSeparator() {
        myFixture.addFileToProject(
            "application.properties",
            """
            spring.profiles.active=dev
            #---
            spring.config.activate.on-profile=dev
            app.solr.url=http://localhost:8983/solr
            app.solr.username=dev-user
            """.trimIndent(),
        )

        val found = sources()

        assertEquals(listOf(null, "dev"), found.map { it.profile })
        assertEquals("dev-user", found[1].properties["app.solr.username"])
    }

    // --- where files are looked for -----------------------------------------------------------------

    /** `config/` beside the classpath root is read too, and after it, as Spring applies it. */
    fun testTheConfigDirectoryIsReadAfterTheRoot() {
        myFixture.addFileToProject("config/application.yml", "app.solr.url: http://override:8983/solr")
        myFixture.addFileToProject("application.yml", "app.solr.url: http://localhost:8983/solr")

        assertEquals(
            listOf("http://localhost:8983/solr", "http://override:8983/solr"),
            sources().map { it.properties["app.solr.url"] },
        )
    }

    /** A file that is not an application configuration is not read, however it is shaped. */
    fun testOtherYamlIsNotRead() {
        myFixture.addFileToProject("compose.yml", "solr:\n  url: http://localhost:8983/solr")

        assertEmpty(sources())
    }
}
