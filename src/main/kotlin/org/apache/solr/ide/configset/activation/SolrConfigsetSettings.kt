package org.apache.solr.ide.configset.activation

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

    /**
     * The persisted form of these settings, serialized to `solr.xml` and therefore shareable
     * across a team through version control.
     */
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

    /**
     * Whether configset detection is active for this project.
     *
     * When false, [SolrConfigsetDetector] rejects every file regardless of name, location or
     * manual roots — the master switch for users who want the plugin installed but silent on a
     * particular project.
     */
    val isDetectionEnabled: Boolean
        get() = state.detectionEnabled

    /**
     * Turns configset detection on or off for this project.
     *
     * @param enabled false to suppress all configset features in this project
     */
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

    /**
     * True if [file] lives under a directory the user manually marked as a configset root.
     *
     * The root itself counts as being under itself, so marking a directory also covers a
     * configset file sitting directly in it.
     *
     * @param file the file to test
     * @return true if any manual root is an ancestor of [file], or is [file] itself
     */
    fun isUnderManualRoot(file: VirtualFile): Boolean {
        val path = file.path
        return manualRoots.any { FileUtil.isAncestor(it, path, false) }
    }

    /**
     * The marked root that owns [file], as a [VirtualFile], or null if no marked root does.
     *
     * Where [isUnderManualRoot] answers whether a file is covered, this answers *by which root* —
     * the question [SolrConfigsetLocator] has to settle to build a per-configset model. Nested roots
     * resolve to the deepest match, so marking both a core directory and its `conf` subdirectory
     * gives files under `conf` the more specific of the two, which is the one the user meant.
     *
     * The match is found by climbing [file]'s own parent chain rather than by resolving the stored
     * path through a file system. Both would work against a real project; only this one works in
     * the platform's in-memory test fixtures, where files live in a temp file system that
     * [com.intellij.openapi.vfs.LocalFileSystem] cannot see. A root that no longer exists on disk,
     * or that names a directory outside [file]'s chain, simply yields null.
     *
     * @param file the file or directory whose owning marked root is wanted
     * @return the deepest marked root containing [file], or null
     */
    fun manualRootFor(file: VirtualFile): VirtualFile? {
        val roots = manualRoots.toSet()
        if (roots.isEmpty()) return null
        var candidate: VirtualFile? = file
        while (candidate != null) {
            if (candidate.path in roots) return candidate
            candidate = candidate.parent
        }
        return null
    }

    /**
     * Marks [dir] as a configset root, so recognized files beneath it activate features even when
     * the directory heuristics find no evidence. This is the user-facing remedy when features fail
     * to activate on a real configset whose layout the heuristics do not match.
     *
     * Marking a file rather than a directory would make that single file its own root, since
     * [FileUtil.isAncestor] is non-strict, so it is rejected outright. Adding the same directory
     * twice is a no-op.
     *
     * @param dir the directory to treat as a configset root
     * @throws IllegalArgumentException if [dir] is not a directory
     * @see SolrConfigsetDetector.isConfigsetFile
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
     *
     * Takes a path rather than a [VirtualFile] so that roots which no longer exist on disk — a
     * deleted or renamed directory — can still be cleared.
     *
     * @param path an absolute or collapsed path; one that matches no stored root is ignored
     */
    fun removeManualRoot(path: String) {
        val equivalentForms = setOfNotNull(path, macros.collapsePath(path), macros.expandPath(path))
        state.manualConfigsetRoots.removeAll(equivalentForms)
    }

    /** Service lookup for these settings. */
    companion object {
        /**
         * The settings instance for [project].
         *
         * @param project the project whose configset settings are wanted
         * @return the project-level service holding the persisted state
         */
        fun getInstance(project: Project): SolrConfigsetSettings = project.service()
    }
}
