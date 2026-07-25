package org.apache.solr.ide.configset

import com.intellij.testFramework.fixtures.BasePlatformTestCase

/**
 * Base class for configset tests that touch [SolrConfigsetSettings].
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

    override fun setUp() {
        super.setUp()
        resetConfigsetSettings()
    }

    private fun resetConfigsetSettings() {
        settings.setDetectionEnabled(true)
        settings.state.manualConfigsetRoots.clear()
    }
}
