package org.apache.solr.ide.configset

import com.intellij.openapi.components.BaseState
import com.intellij.openapi.components.PathMacroManager
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
 *
 * A marked root is a fact about the project, not about one developer's machine — the same
 * directory is a configset for everyone on the team — so state lives in the shared `solr.xml`
 * rather than in workspace-local storage. That makes path portability a requirement: paths are
 * collapsed through [PathMacroManager] on the way in (`$PROJECT_DIR$/core/conf`) and expanded on
 * the way out, so a root marked in one checkout resolves in every other. Roots outside the
 * project stay absolute, which is the best that can be done for them.
 */
@Service(Service.Level.PROJECT)
@State(name = "SolrConfigsetSettings", storages = [Storage("solr.xml")])
class SolrConfigsetSettings(private val project: Project) :
    SimplePersistentStateComponent<SolrConfigsetSettings.State>(State()) {

    class State : BaseState() {
        /** When false, no file is ever treated as a Solr configset file. */
        var detectionEnabled: Boolean by property(true)

        /**
         * Directories the user manually marked as configset roots, stored in collapsed form
         * (see the class KDoc). Read them as usable absolute paths via [manualRoots].
         */
        val manualConfigsetRoots: MutableList<String> by list()
    }

    private val macros: PathMacroManager get() = PathMacroManager.getInstance(project)

    val isDetectionEnabled: Boolean
        get() = state.detectionEnabled

    fun setDetectionEnabled(enabled: Boolean) {
        state.detectionEnabled = enabled
    }

    /**
     * The marked configset roots as absolute paths, in the order they were added.
     *
     * Absolute paths written by an earlier version expand to themselves, so previously saved
     * settings keep working without a migration step.
     */
    val manualRoots: List<String>
        get() = state.manualConfigsetRoots.map { macros.expandPath(it) ?: it }

    /** True if [file] lives under a directory the user manually marked as a configset root. */
    fun isUnderManualRoot(file: VirtualFile): Boolean {
        val path = file.path
        return manualRoots.any { FileUtil.isAncestor(it, path, false) }
    }

    /**
     * Marks [dir] as a configset root.
     *
     * Marking a file rather than a directory would make that single file its own root, since
     * [FileUtil.isAncestor] is non-strict, so it is rejected outright.
     */
    fun addManualRoot(dir: VirtualFile) {
        require(dir.isDirectory) { "configset root must be a directory: ${dir.path}" }
        val stored = macros.collapsePath(dir.path) ?: dir.path
        if (stored !in state.manualConfigsetRoots) {
            state.manualConfigsetRoots.add(stored)
        }
    }

    /**
     * Removes a previously marked root. [path] may be absolute or already collapsed.
     *
     * Both forms are removed because the stored form is not knowable from the caller's side: a
     * root written by an earlier version is a raw absolute path, while anything written since is
     * collapsed. Matching only one form would silently no-op on the other.
     */
    fun removeManualRoot(path: String) {
        val equivalentForms = setOfNotNull(path, macros.collapsePath(path), macros.expandPath(path))
        state.manualConfigsetRoots.removeAll(equivalentForms)
    }

    companion object {
        fun getInstance(project: Project): SolrConfigsetSettings = project.service()
    }
}
