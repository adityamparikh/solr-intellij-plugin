package org.apache.solr.ide.configset.activation

import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.roots.LibraryOrderEntry
import com.intellij.openapi.roots.ModuleRootManager
import com.intellij.openapi.roots.ModuleRootModificationUtil
import com.intellij.openapi.roots.libraries.LibraryTablesRegistrar
import com.intellij.testFramework.PsiTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.apache.solr.ide.server.connection.SolrConnectionSettings

/**
 * Base class for configset tests that touch [SolrConfigsetSettings] or
 * [SolrConnectionSettings].
 *
 * [BasePlatformTestCase] reuses a single light project across test methods *and* test classes, and
 * [SolrConfigsetSettings] is a project-level [com.intellij.openapi.components.PersistentStateComponent].
 * Its state therefore survives from one test into the next: a test that disables detection, or marks
 * a manual root, silently changes the starting conditions of everything that runs after it.
 *
 * Resetting in `setUp` (rather than `tearDown`) keeps the guarantee one-sided but reliable — it holds
 * even when a preceding test fails partway through, or when state is left behind by a class that
 * does not extend this one.
 */
abstract class SolrConfigsetTestCase : BasePlatformTestCase() {

    protected val settings: SolrConfigsetSettings get() = SolrConfigsetSettings.getInstance(project)

    protected val connectionSettings: SolrConnectionSettings
        get() = SolrConnectionSettings.getInstance(project)

    protected val locator: SolrConfigsetLocator get() = SolrConfigsetLocator.getInstance(project)

    override fun setUp() {
        super.setUp()
        resetConfigsetSettings()
        resetConnectionSettings()
        locator.dropCache()
        SolrProjectDetector.getInstance(project).dropCache()
        givenSolrOnTheClasspath()
    }

    /**
     * Puts a Solr client on the fixture's classpath, so detection is enabled for most tests.
     *
     * [SolrProjectDetector] gates everything on the project depending on a Solr client, which the
     * light fixture does not by default. Without this, every detection test would assert against a
     * project the plugin is correctly ignoring, and would pass for the wrong reason.
     *
     * The library carries no actual jar because none is needed: the detector matches on the library
     * *name*, which is where Gradle and Maven put the Maven coordinates.
     */
    protected fun givenSolrOnTheClasspath() {
        if (!moduleDependsOnSolr()) {
            val table = LibraryTablesRegistrar.getInstance().getLibraryTable(project)
            val existing = table.getLibraryByName(FIXTURE_SOLRJ_LIBRARY)
            if (existing == null) {
                givenLibrary(FIXTURE_SOLRJ_LIBRARY)
            } else {
                // The library outlived the test that created it — the light project is shared — but
                // the module's dependency on it was removed. Re-link rather than recreate.
                ModuleRootModificationUtil.addDependency(module, existing)
            }
        }
        dropDetectionCaches()
    }

    private fun moduleDependsOnSolr(): Boolean =
        ModuleRootManager.getInstance(module).orderEntries
            .filterIsInstance<LibraryOrderEntry>()
            .any { it.libraryName == FIXTURE_SOLRJ_LIBRARY }

    /**
     * Removes the libraries these helpers added, leaving a project with no Solr dependency.
     *
     * The starting state for tests that assert the plugin stays silent outside a Solr project.
     */
    protected fun givenNoSolrOnTheClasspath() {
        ModuleRootModificationUtil.updateModel(module) { model ->
            model.orderEntries
                .filterIsInstance<LibraryOrderEntry>()
                .filter { it.libraryName in addedByTests }
                .forEach { model.removeOrderEntry(it) }
        }
        // Drop the libraries themselves too, not just the module's dependency on them: the light
        // project is shared, so a library left in the table makes the next `addProjectLibrary` of
        // the same name fail with "already exists".
        //
        // Scoped to libraries these helpers created. Emptying the whole table would be the same
        // class of shared-project mutation this base class exists to prevent, and would silently
        // break the first test that needs a library of its own.
        val table = LibraryTablesRegistrar.getInstance().getLibraryTable(project)
        WriteAction.runAndWait<RuntimeException> {
            val model = table.modifiableModel
            table.libraries.filter { it.name in addedByTests }.forEach { model.removeLibrary(it) }
            model.commit()
        }
        dropDetectionCaches()
    }

    /**
     * Adds a library by name, with no jar behind it — [SolrProjectDetector] matches on the name.
     *
     * @param name the library name, spelled as Gradle or Maven would spell a resolved dependency
     */
    protected fun givenLibrary(name: String) {
        addedByTests += name
        PsiTestUtil.addProjectLibrary(module, name, emptyList())
        dropDetectionCaches()
    }

    private fun dropDetectionCaches() {
        SolrProjectDetector.getInstance(project).dropCache()
        locator.dropCache()
    }

    private companion object {
        /**
         * Library names these helpers have created, across every test in the JVM.
         *
         * Static on purpose. Test instances are per-method, so an instance field could not remember
         * a library added by an earlier method — and the light project those libraries live in
         * outlives both the method and the class.
         */
        val addedByTests: MutableSet<String> = mutableSetOf()

        /**
         * A stand-in library for the fixture — not the production matcher, which lives in
         * [SolrProjectDetector.SOLR_CLIENT_COORDINATES] and matches artifact ids as substrings.
         *
         * Spelled the way Gradle spells a resolved dependency, because that string is exactly what
         * the detector reads. The version in it is arbitrary and is never matched on; see
         * `SolrProjectDetectorTest` for the test that pins version independence.
         */
        const val FIXTURE_SOLRJ_LIBRARY = "Gradle: org.apache.solr:solr-solrj:9.10.0"
    }

    private fun resetConfigsetSettings() {
        settings.setDetectionEnabled(true)
        settings.state.manualConfigsetRoots.clear()
    }

    /**
     * Clears connections *through the service*, not by emptying the list, so that each removal also
     * forgets the credential it filed in PasswordSafe. PasswordSafe is application-level and so
     * outlives the project the light fixture recycles; a leaked secret there would survive not just
     * into the next test but into the next test class.
     */
    private fun resetConnectionSettings() {
        connectionSettings.connections.forEach { connectionSettings.removeConnection(it.id) }
        connectionSettings.state.connections.clear()
    }
}
