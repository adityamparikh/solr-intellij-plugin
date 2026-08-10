package org.apache.solr.ide.configset.reading

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileVisitor
import org.apache.solr.ide.configset.activation.SolrConfigset
import org.apache.solr.ide.configset.activation.SolrConfigsetFileKind
import org.apache.solr.ide.configset.activation.SolrConfigsetSettings
import org.apache.solr.ide.configset.activation.SolrProjectDetector

/**
 * Finds every configset in the project, rather than the one owning a particular file.
 *
 * `SolrConfigsetLocator` answers per file and on demand, which is what the editor path needs and
 * exactly the wrong shape for two other jobs: a model of *the project's* configsets, and telling a
 * user which configsets detection actually found. Both need the whole list, and neither should
 * discover it by opening every file.
 *
 * **This walks the project tree, so it is not an editor-path operation.** Callers should treat it as
 * they would an indexing pass — on demand, off the typing path — and it prunes aggressively so that
 * "on demand" stays affordable in a repository with a `node_modules` in it.
 */
@Service(Service.Level.PROJECT)
class SolrConfigsetScanner(private val project: Project) {

    /**
     * Every configset in the project, in a stable order.
     *
     * Returns nothing when the project has no Solr client on its dependencies and no manually marked
     * root, matching [SolrProjectDetector]: a scan that ignored the activation gate would report
     * configsets in a project where every other surface is deliberately silent.
     *
     * Manually marked roots are always included, whether or not they contain a recognized file. The
     * user asserted the directory is a configset, and a scan that second-guessed that would make the
     * override useless in the one project shape that depends on it.
     *
     * @return the configsets found, ordered by path so that callers and tests see a stable list
     */
    fun scan(): List<SolrConfigset> {
        val settings = SolrConfigsetSettings.getInstance(project)
        if (!settings.isDetectionEnabled) return emptyList()

        val manualRoots = settings.manualRoots.toSet()
        if (!SolrProjectDetector.getInstance(project).isSolrProject() && manualRoots.isEmpty()) return emptyList()

        val fileIndex = ProjectFileIndex.getInstance(project)
        val found = LinkedHashSet<VirtualFile>()
        for (contentRoot in ProjectRootManager.getInstance(project).contentRoots) {
            VfsUtilCore.visitChildrenRecursively(
                contentRoot,
                object : VirtualFileVisitor<Unit>() {
                    override fun visitFileEx(file: VirtualFile): Result {
                        if (!file.isDirectory) return CONTINUE
                        if (shouldPrune(file, fileIndex)) return SKIP_CHILDREN
                        if (file.path in manualRoots || holdsSelfIdentifyingFile(file)) found += file
                        return CONTINUE
                    }
                },
            )
        }
        // Marked roots outside every content root would otherwise be missed, and a configset
        // repository added as a plain directory is exactly that case.
        for (root in manualRoots) {
            if (found.none { it.path == root }) {
                contentRootRelative(root)?.let { found += it }
            }
        }
        return found.sortedBy { it.path }.map { SolrConfigset(it) }
    }

    private fun holdsSelfIdentifyingFile(directory: VirtualFile): Boolean =
        directory.children.orEmpty().any {
            !it.isDirectory && it.name in SolrConfigsetFileKind.SELF_IDENTIFYING_FILE_NAMES
        }

    /**
     * Whether the scan should decline to descend into [directory].
     *
     * Two independent reasons, because neither covers the other. The IDE's own exclusions cover
     * `build/` and `out/` in a project that has been imported properly; the name list covers
     * directories the IDE was never told about — `node_modules` in a project opened as plain files,
     * a `.git` in any project at all. Descending into either is how a "find the configsets" action
     * turns into a visible freeze.
     */
    private fun shouldPrune(directory: VirtualFile, fileIndex: ProjectFileIndex): Boolean =
        directory.name in PRUNED_DIRECTORY_NAMES ||
            directory.name.startsWith(".") && directory.name != "." ||
            fileIndex.isExcluded(directory)

    private fun contentRootRelative(path: String): VirtualFile? =
        ProjectRootManager.getInstance(project).contentRoots
            .firstNotNullOfOrNull { root -> VfsUtilCore.findRelativeFile(path, root) }

    /** Service lookup and the pruning list. */
    companion object {
        /**
         * Directory names never descended into.
         *
         * Dependency and build-output trees, which are large, deep, and cannot contain a configset
         * anyone edits. A generated configset inside `build/` is a copy of one in the source tree,
         * and reporting the copy would send the user to edit a file their next build overwrites.
         */
        val PRUNED_DIRECTORY_NAMES: Set<String> = setOf(
            "node_modules", "build", "out", "target", "dist", "venv", "__pycache__", "vendor",
        )

        /**
         * The scanner for [project].
         *
         * @param project the project to scan
         * @return the project-level scanner service
         */
        fun getInstance(project: Project): SolrConfigsetScanner = project.service()
    }
}
