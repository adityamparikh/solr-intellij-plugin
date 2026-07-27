package org.apache.solr.ide.configset.activation

import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiFile

/**
 * Decides whether a file is a Solr configset file, gating all feature activation.
 *
 * Two questions are answered here, and they are not the same one:
 *
 *  - **Should features activate for this file?** — [isConfigsetFile]. A file qualifies when its name
 *    is one Solr uses for content rather than for a resource (see [SolrConfigsetFileRole]) and it
 *    resolves to a configset.
 *  - **Which configset does this file belong to?** — [configsetFor], delegated to
 *    [SolrConfigsetLocator]. Fields and analyzer chains are properties of a configset directory, and
 *    a project may hold several, so every model lookup starts from this answer rather than from the
 *    file.
 *
 * Both rest on [SolrProjectDetector]: features activate only in a project that depends on a Solr
 * client. That is a fact about the project rather than an inference from a directory listing, and it
 * is what makes the rules below as simple as they are. The manual override in
 * [SolrConfigsetSettings] covers the case the dependency cannot — a repository of configsets with no
 * build file, and so no dependencies to find.
 *
 * It does not, however, settle whether a *particular file* is Solr's. A project may use Solr and
 * still contain an XSD called `schema.xml` that has nothing to do with it, which is why file names
 * are still tiered by how much they prove on their own: see [SolrConfigsetFileRole].
 *
 * Resource files such as `stopwords.txt` are recognized by [kindOf] but never activate anything on
 * their own; their names are far too common outside Solr to be evidence. They are recognized only
 * from inside a configset already identified, which is what makes navigating a filter's `words=`
 * attribute to the file it names possible.
 */
object SolrConfigsetDetector {

    /**
     * Whether [file] should be treated as part of a Solr configset in [project].
     *
     * Directories never qualify, and nothing qualifies while detection is switched off in
     * [SolrConfigsetSettings]. A recognized file name is necessary but not sufficient: the name must
     * be an identifying one, and the file must resolve to a configset.
     *
     * @param project the project whose [SolrConfigsetSettings] supply the manual overrides
     * @param file the file to classify
     * @return true if configset features should activate for [file]
     * @see SolrConfigsetFileKind.forFileName
     */
    fun isConfigsetFile(project: Project, file: VirtualFile): Boolean {
        if (file.isDirectory) return false
        val kind = SolrConfigsetFileKind.forFileName(file.name) ?: return false
        if (!kind.activatesFeatures) return false
        return configsetFor(project, file) != null
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
     * The configset owning [file], or null if it belongs to none.
     *
     * Broader than [isConfigsetFile] on purpose: a resource file, or the configset directory itself,
     * resolves to its configset without being something that activates features.
     *
     * @param project the project to resolve within
     * @param file the file or directory whose owning configset is wanted
     * @return the owning configset, or null
     */
    fun configsetFor(project: Project, file: VirtualFile): SolrConfigset? =
        SolrConfigsetLocator.getInstance(project).configsetFor(file)

    /**
     * The configset owning [psiFile], or null if it belongs to none.
     *
     * @param psiFile the PSI file whose owning configset is wanted
     * @return the owning configset, or null — including when [psiFile] has no backing file on disk
     */
    fun configsetFor(psiFile: PsiFile): SolrConfigset? =
        psiFile.virtualFile?.let { configsetFor(psiFile.project, it) }

    /**
     * The configset kind for [file], or null if [file] is not a recognized configset entry.
     *
     * Narrower than [SolrConfigsetFileKind.forFile], which classifies a name in isolation: this
     * returns a kind only for entries that actually resolve to a configset, so a `schema.xml`
     * belonging to some unrelated framework yields null — and so does a `stopwords.txt` that is not
     * inside a configset, which is the whole point of the resource/identifying split.
     *
     * @param project the project whose [SolrConfigsetSettings] supply the manual overrides
     * @param file the file or directory to classify
     * @return the kind of configset entry, or null if it is not one
     */
    fun kindOf(project: Project, file: VirtualFile): SolrConfigsetFileKind? {
        val kind = SolrConfigsetFileKind.forFile(file) ?: return null
        return if (configsetFor(project, file) != null) kind else null
    }
}
