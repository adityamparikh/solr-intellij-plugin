package org.apache.solr.ide.configset

import com.intellij.openapi.components.BaseState
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.components.SimplePersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.vfs.VirtualFile

/**
 * Project-level persistent settings controlling Solr configset detection.
 *
 * Detection is heuristic, so this service provides the escape hatch required by the spec's
 * "manual override": directories the user explicitly marks are always treated as configset roots
 * (recognized files under them activate features), and detection can be disabled entirely.
 */
@Service(Service.Level.PROJECT)
@State(name = "SolrConfigsetSettings", storages = [Storage("solr.xml")])
class SolrConfigsetSettings :
    SimplePersistentStateComponent<SolrConfigsetSettings.State>(State()),
    PersistentStateComponent<SolrConfigsetSettings.State> {

    class State : BaseState() {
        /** When false, no file is ever treated as a Solr configset file. */
        var detectionEnabled: Boolean by property(true)

        /** Absolute paths of directories the user manually marked as configset roots. */
        val manualConfigsetRoots: MutableList<String> by list()
    }

    val isDetectionEnabled: Boolean
        get() = state.detectionEnabled

    fun setDetectionEnabled(enabled: Boolean) {
        state.detectionEnabled = enabled
    }

    /** True if [file] lives under a directory the user manually marked as a configset root. */
    fun isUnderManualRoot(file: VirtualFile): Boolean {
        val path = file.path
        return state.manualConfigsetRoots.any { FileUtil.isAncestor(it, path, false) }
    }

    fun addManualRoot(dir: VirtualFile) {
        val path = dir.path
        if (path !in state.manualConfigsetRoots) {
            state.manualConfigsetRoots.add(path)
        }
    }

    fun removeManualRoot(path: String) {
        state.manualConfigsetRoots.remove(path)
    }

    companion object {
        fun getInstance(project: Project): SolrConfigsetSettings = project.service()
    }
}
