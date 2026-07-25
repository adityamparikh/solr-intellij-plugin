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

    /**
     * Whether [file] should be treated as part of a Solr configset in [project].
     *
     * Directories never qualify, and nothing qualifies while detection is switched off in
     * [SolrConfigsetSettings]. A recognized file name is necessary but not sufficient: it must be
     * corroborated either by a manually marked root or by directory evidence.
     *
     * @param project the project whose [SolrConfigsetSettings] supply the manual overrides
     * @param file the file to classify
     * @return true if configset features should activate for [file]
     * @see SolrConfigsetFileKind.forFileName
     */
    fun isConfigsetFile(project: Project, file: VirtualFile): Boolean {
        if (file.isDirectory) return false
        val settings = SolrConfigsetSettings.getInstance(project)
        if (!settings.isDetectionEnabled) return false
        if (SolrConfigsetFileKind.forFileName(file.name) == null) return false

        return settings.isUnderManualRoot(file) || hasDirectoryEvidence(file)
    }

    /**
     * Whether [psiFile] should be treated as part of a Solr configset.
     *
     * Convenience overload for PSI-based callers such as inspections, annotators and reference
     * providers, which hold a [PsiFile] rather than a [VirtualFile].
     *
     * Files with no backing [VirtualFile] are rejected. Detection rests on where a file sits on
     * disk, and an in-memory or synthetic file has no location to reason about.
     *
     * @param psiFile the PSI file to classify
     * @return true if configset features should activate for [psiFile]
     */
    fun isConfigsetFile(psiFile: PsiFile): Boolean {
        val vFile = psiFile.virtualFile ?: return false
        return isConfigsetFile(psiFile.project, vFile)
    }

    /**
     * The configset kind for [file], or null if [file] is not a recognized configset file.
     *
     * Narrower than [SolrConfigsetFileKind.forFileName], which classifies a name in isolation:
     * this returns a kind only for files that pass full detection, so a `schema.xml` belonging to
     * some unrelated framework yields null.
     *
     * @param project the project whose [SolrConfigsetSettings] supply the manual overrides
     * @param file the file to classify
     * @return the kind of configset file, or null if [file] is not one
     */
    fun kindOf(project: Project, file: VirtualFile): SolrConfigsetFileKind? =
        if (isConfigsetFile(project, file)) SolrConfigsetFileKind.forFileName(file.name) else null

    /**
     * Whether [file]'s directory looks like a Solr configset.
     *
     * Two signals count as evidence: a `conf/` parent, the conventional location inside a core or
     * collection, and a sibling that is itself a recognized configset file — a `schema.xml` next to
     * a `solrconfig.xml` is far more likely to be Solr's than either would be alone.
     *
     * Both signals are deliberately cheap and local. Walking further up the tree looking for
     * `core.properties` or a ZooKeeper layout would catch more configsets, but detection runs on
     * every file the user opens, so the cost has to stay proportional to the benefit. The manual
     * override in [SolrConfigsetSettings] covers what this misses.
     */
    private fun hasDirectoryEvidence(file: VirtualFile): Boolean {
        val parent = file.parent ?: return false
        if (parent.name == "conf") return true
        return parent.children.orEmpty().any { sibling ->
            sibling != file && SolrConfigsetFileKind.forFileName(sibling.name) != null
        }
    }
}
