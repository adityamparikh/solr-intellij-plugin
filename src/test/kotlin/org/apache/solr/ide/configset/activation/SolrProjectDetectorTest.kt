package org.apache.solr.ide.configset.activation

import com.intellij.testFramework.DumbModeTestUtils

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

    // --- module scope, which is what a code recognizer asks -------------------------------------

    /**
     * The gate a code recognizer needs is about a module, not a project.
     *
     * The project-level answer is the plugin's activation gate: is there any point in this plugin
     * being awake at all. A recognizer asks something narrower — could *this* module be talking to
     * Solr — because the layout it will most often meet is a repository of many modules in which one
     * does. Answering the project question there would offer completions in every module of a
     * repository where a single unrelated one depends on SolrJ.
     */
    fun testAModuleWithSolrjIsASolrModule() {
        assertTrue(detector.isSolrModule(module))
    }

    fun testAModuleWithNoLibrariesIsNotASolrModule() {
        givenNoSolrOnTheClasspath()
        assertFalse(detector.isSolrModule(module))
    }

    /** The same coordinates decide both questions; a second list is how the two would disagree. */
    fun testAModuleMatchesEveryClientWrapperTheProjectDoes() {
        for (library in listOf(
            "Gradle: org.apache.camel:camel-solr:4.4.0",
            "Gradle: io.quarkiverse.jnosql:quarkus-jnosql-document-solr:3.3.0",
        )) {
            givenNoSolrOnTheClasspath()
            givenLibrary(library)
            assertTrue("expected $library to be recognized at module scope", detector.isSolrModule(module))
        }
    }

    /** An unrelated library merely mentioning Solr is not a client, at module scope either. */
    fun testAModuleWithAnUnrelatedSolrNamedLibraryIsNotASolrModule() {
        givenNoSolrOnTheClasspath()
        givenLibrary("Gradle: com.example:solr-config-linter:1.0.0")
        assertFalse(detector.isSolrModule(module))
    }

    /**
     * The general gate answers about the coordinates it is handed, not about Solr in general.
     *
     * What lets each recognizer declare the library it needs. A recognizer reading Camel URIs is
     * worth nothing on a module carrying only SolrJ, and until this existed every recognizer would
     * have run wherever any Solr client did.
     */
    fun testTheGateNarrowsToTheCoordinatesItIsGiven() {
        assertTrue(detector.moduleDependsOn(module, listOf("solr-solrj")))
        assertFalse(detector.moduleDependsOn(module, listOf("camel-solr")))
        assertTrue(detector.moduleDependsOn(module, listOf("camel-solr", "solr-solrj")))
    }

    /**
     * The gate answers while the IDE is still indexing, which is the property that makes it safe.
     *
     * Every recognizer in the plugin runs behind this question, and it is asked on files the user
     * opens — including during the indexing that follows opening a project, which is exactly when
     * somebody first looks at their code. Answering it by resolving `SolrClient` through PSI would
     * be exact and would throw here. Reading library names off the project model needs no index, and
     * this is the test that stops that from silently becoming untrue.
     */
    fun testTheGateAnswersWhileTheIdeIsIndexing() {
        DumbModeTestUtils.runInDumbModeSynchronously(project) {
            assertTrue(detector.moduleDependsOn(module, listOf("solr-solrj")))
            assertFalse(detector.moduleDependsOn(module, listOf("camel-solr")))
        }
    }

    /**
     * The two answers agree on the fixture, which has one module.
     *
     * Worth pinning because it is the only place they can be compared cheaply, and because a module
     * gate that had silently become a project gate would pass every other test in this file.
     */
    fun testProjectAndModuleAgreeOnASingleModuleFixture() {
        assertEquals(detector.isSolrProject(), detector.isSolrModule(module))
        givenNoSolrOnTheClasspath()
        assertEquals(detector.isSolrProject(), detector.isSolrModule(module))
    }
}
