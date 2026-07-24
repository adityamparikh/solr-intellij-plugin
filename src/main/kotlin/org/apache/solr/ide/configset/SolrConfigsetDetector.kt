package org.apache.solr.ide.configset

import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiFile

/**
 * Decides whether a file is a Solr configset file, gating all Phase 1 feature activation.
 *
 * A file is recognized when its name matches a known configset file name AND at least one
 * confidence signal holds:
 *  - it sits under a directory the user manually marked (see [SolrConfigsetSettings]); or
 *  - directory heuristics indicate a real configset — a sibling `solrconfig.xml`/schema file,
 *    or a conventional `conf/` parent directory.
 *
 * The heuristics keep a stray `schema.xml` from an unrelated framework from activating features,
 * while the manual override covers layouts the heuristics miss.
 */
object SolrConfigsetDetector {

    fun isConfigsetFile(project: Project, file: VirtualFile): Boolean {
        if (file.isDirectory) return false
        val settings = SolrConfigsetSettings.getInstance(project)
        if (!settings.isDetectionEnabled) return false
        if (SolrConfigsetFileKind.forFileName(file.name) == null) return false

        return settings.isUnderManualRoot(file) || hasDirectoryEvidence(file)
    }

    fun isConfigsetFile(psiFile: PsiFile): Boolean {
        val vFile = psiFile.virtualFile ?: return false
        return isConfigsetFile(psiFile.project, vFile)
    }

    /** The configset kind for [file], or null if [file] is not a recognized configset file. */
    fun kindOf(project: Project, file: VirtualFile): SolrConfigsetFileKind? =
        if (isConfigsetFile(project, file)) SolrConfigsetFileKind.forFileName(file.name) else null

    private fun hasDirectoryEvidence(file: VirtualFile): Boolean {
        val parent = file.parent ?: return false
        if (parent.name == "conf") return true
        return parent.children.orEmpty().any { sibling ->
            sibling != file && SolrConfigsetFileKind.forFileName(sibling.name) != null
        }
    }
}
