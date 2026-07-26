package org.apache.solr.ide.configset

class SolrProjectDetectorTest : SolrConfigsetTestCase() {

    private val detector: SolrProjectDetector get() = SolrProjectDetector.getInstance(project)

    fun testProjectWithSolrjIsASolrProject() {
        assertTrue(detector.isSolrProject())
    }

    fun testProjectWithNoLibrariesIsNotASolrProject() {
        givenNoSolrOnTheClasspath()
        assertFalse(detector.isSolrProject())
    }

    /**
     * Versions are never matched on. Pinning this stops the coordinate list from acquiring a version
     * and quietly excluding every other release of the same artifact.
     */
    fun testAnyVersionOfSolrjMatches() {
        for (version in listOf("8.11.2", "9.0.0", "9.10.0", "10.0.0", "11.0.0-SNAPSHOT")) {
            givenNoSolrOnTheClasspath()
            givenLibrary("Gradle: org.apache.solr:solr-solrj:$version")
            assertTrue("expected solr-solrj:$version to be recognized", detector.isSolrProject())
        }
    }

    /** Maven and Gradle spell resolved libraries differently; both carry the artifact id. */
    fun testMavenStyleLibraryNamesMatch() {
        givenNoSolrOnTheClasspath()
        givenLibrary("Maven: org.apache.solr:solr-solrj:9.10.0")
        assertTrue(detector.isSolrProject())
    }

    /**
     * The wrappers, each of which a project may name instead of SolrJ. `camel-quarkus-solr` is the
     * one that motivated listing them individually: it does not contain `camel-solr` as a substring,
     * so a list holding only the plain Camel component would miss it.
     */
    fun testSolrClientWrappersMatch() {
        for (library in listOf(
            "Gradle: org.springframework.data:spring-data-solr:4.3.15",
            "Gradle: org.springframework.boot:spring-boot-starter-data-solr:2.7.18",
            "Gradle: org.apache.camel:camel-solr:4.4.0",
            "Gradle: org.apache.camel.quarkus:camel-quarkus-solr:3.30.0",
            "Gradle: io.quarkiverse.jnosql:quarkus-jnosql-document-solr:3.3.0",
        )) {
            givenNoSolrOnTheClasspath()
            givenLibrary(library)
            assertTrue("expected $library to be recognized", detector.isSolrProject())
        }
    }

    /**
     * The list is explicit rather than a match on `*solr*`, so things that merely mention Solr do
     * not activate the plugin. A user's own module called `solr-config` is the realistic case.
     */
    fun testUnrelatedLibrariesMentioningSolrDoNotMatch() {
        for (library in listOf(
            "Gradle: com.example:solr-config:1.0.0",
            "Gradle: com.example:my-solr-utils:1.0.0",
            "Gradle: org.apache.lucene:lucene-core:10.3.2",
        )) {
            givenNoSolrOnTheClasspath()
            givenLibrary(library)
            assertFalse("expected $library not to be recognized", detector.isSolrProject())
        }
    }

    /** A dependency added by a Gradle sync must take effect without an IDE restart. */
    fun testAddingSolrjLaterFlipsTheAnswer() {
        givenNoSolrOnTheClasspath()
        assertFalse(detector.isSolrProject())

        givenLibrary("Gradle: org.apache.solr:solr-solrj:9.10.0")

        assertTrue("adding the dependency must invalidate the cached answer", detector.isSolrProject())
    }
}
