package org.apache.solr.ide.configset.reading

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.psi.PsiDirectory
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import org.apache.solr.ide.configset.activation.SolrConfigset
import org.apache.solr.ide.configset.activation.SolrConfigsetDetector
import org.apache.solr.ide.configset.activation.SolrConfigsetFileKind
import org.apache.solr.ide.configset.schema.parsing.SolrSchemaParser
import org.apache.solr.ide.configset.solrconfig.parsing.SolrConfigParser
import org.apache.solr.ide.model.SolrConfigsetFacts
import org.apache.solr.ide.model.SolrFieldModel

/**
 * Builds and caches the field model for each configset in the project.
 *
 * The spine: every feature that knows what a field is reads it from here.
 *
 * **Caching is the platform's**, through `CachedValuesManager`, rather than a map and a set of
 * modification stamps this class compares by hand. The model is a function of the text of the
 * schema and `solrconfig.xml`, so it must be rebuilt when either changes and — the half that costs
 * performance if it is wrong — must *not* be rebuilt when anything else does. `modelFor` documents
 * how the dependency list achieves both, and why neither half of it can be dropped.
 *
 * **Unsaved edits count.** The model is derived from PSI, which reflects the editor's buffer long
 * before anything is written to disk, so a field added in the editor is in the model immediately.
 * Reading from disk instead would mean the plugin disagreed with what the user is looking at until
 * they hit save.
 *
 * PSI is also what every consumer of the model is itself inspecting, so the two can no longer
 * disagree. The previous implementation read the in-memory document directly, which made the model
 * momentarily *ahead* of the PSI an inspection was visiting.
 */
@Service(Service.Level.PROJECT)
class SolrConfigsetReader(private val project: Project) {

    /**
     * The field model for [configset], parsed if the cache has nothing current.
     *
     * @param configset the configset to model
     * @return its model, which is empty rather than null when the configset holds no readable files
     */
    fun modelFor(configset: SolrConfigset): SolrFieldModel =
        directoryOf(configset)?.let { modelFor(it) } ?: EMPTY

    /**
     * The cached model for a configset directory.
     *
     * **The cache lives on the directory, not in a map this class owns.** That is the difference
     * that matters: a map keyed by path outlives the thing it describes, so a deleted-and-recreated
     * configset — or, in tests, the next test method sharing the same light project — finds an entry
     * built against a filesystem that no longer exists. Hanging the cache on the [PsiDirectory]
     * makes its lifetime exactly the directory's, and deletes the bookkeeping along with the map.
     *
     * **The dependency list is the rest of the design**, and each half is load-bearing:
     *
     *  - **the source files**, as PSI. This is what rebuilds the model when *this* schema changes
     *    and leaves it alone when anything else does. The obvious alternative,
     *    [com.intellij.psi.util.PsiModificationTracker.MODIFICATION_COUNT], is wrong here: it
     *    advances on any PSI change in the project, so a keystroke in unrelated Java would reparse
     *    both configset files.
     *  - **the VFS structure count**, which advances when files are created, deleted, renamed or
     *    moved, but not when one is edited. Without it a `solrconfig.xml` added to a configset that
     *    had none would never be noticed, because the dependency list can only name files that
     *    existed when the model was built.
     */
    private fun modelFor(directory: PsiDirectory): SolrFieldModel =
        CachedValuesManager.getCachedValue(directory) {
            val sources = sourcesOf(directory)
            val facts = sources.fold(SolrConfigsetFacts()) { accumulated, source -> accumulated + source.facts() }
            CachedValueProvider.Result.create(
                SolrFieldModel.of(facts),
                sources.map { it.file } + VirtualFileManager.VFS_STRUCTURE_MODIFICATIONS,
            )
        }

    /** The configset root as PSI, or null once it has stopped existing. */
    private fun directoryOf(configset: SolrConfigset): PsiDirectory? =
        configset.root.takeIf { it.isValid }?.let { PsiManager.getInstance(project).findDirectory(it) }

    /**
     * The raw repository facts for [configset], before merging with any server half.
     *
     * Exposed for the drift comparison, which needs the two halves separately rather than the
     * merged model.
     *
     * @param configset the configset to read
     * @return the facts its files declare
     */
    fun factsFor(configset: SolrConfigset): SolrConfigsetFacts =
        directoryOf(configset)
            ?.let { sourcesOf(it) }
            .orEmpty()
            .fold(SolrConfigsetFacts()) { accumulated, source -> accumulated + source.facts() }

    /**
     * The files in [directory] that contribute to the model, each carrying the parser that reads it,
     * in a stable order.
     *
     * PSI rather than `VirtualFile` because these double as the cache's dependencies, and a
     * dependency has to be something the platform can ask for a modification count. Taking the text
     * from the same objects also removes a discrepancy the previous implementation had to document:
     * it read the in-memory document while stamping the file, so the two could disagree on first
     * read.
     *
     * **The parser travels with the file** rather than being chosen again later. Selecting the
     * files by kind and then dispatching on kind a second time needs a branch for *a file that
     * passed the filter and is neither* — which nothing can reach, no test can cover, and which
     * would silently contribute no facts if a third kind were ever added to the filter alone.
     */
    private fun sourcesOf(directory: PsiDirectory): List<SolrSource> =
        directory.files
            .mapNotNull { file -> parserFor(file)?.let { SolrSource(file, it) } }
            .sortedBy { it.file.name }

    /**
     * The parser for [file], or null when the model takes nothing from it.
     *
     * Only the schema and `solrconfig.xml` are read today. The other identifying files —
     * `elevate.xml`, `currency.xml` — describe things the field model does not yet represent, and
     * reading them would add facts nothing consumes.
     */
    private fun parserFor(file: PsiFile): ((CharSequence) -> SolrConfigsetFacts)? {
        val kind = SolrConfigsetFileKind.forFileName(file.name) ?: return null
        return when {
            kind.isSchema -> SolrSchemaParser::parse
            kind == SolrConfigsetFileKind.SOLR_CONFIG -> SolrConfigParser::parse
            else -> null
        }
    }

    /** One file the model is built from, and the parser that reads it. */
    private class SolrSource(val file: PsiFile, val parse: (CharSequence) -> SolrConfigsetFacts) {
        /** The facts this file declares, read from the PSI the cache also depends on. */
        fun facts(): SolrConfigsetFacts = parse(file.text)
    }

    /**
     * The model for the configset owning [file], or null when [file] belongs to none.
     *
     * The convenience every editor feature wants: it folds the activation gate, configset
     * resolution and model lookup into the one question a feature actually asks. It lives here
     * rather than beside any one feature, because inspections, completion, hints, documentation and
     * references all ask it, and whichever feature owned it would be imported by the other four.
     *
     * @param file the file being edited
     * @return the model, or null outside a configset — which includes outside a Solr project
     */
    fun modelFor(file: PsiFile): SolrFieldModel? {
        if (!SolrConfigsetDetector.isConfigsetFile(file)) return null
        val configset = SolrConfigsetDetector.configsetFor(file) ?: return null
        return modelFor(configset)
    }

    /** Service lookup, and the answer for a configset that has stopped existing. */
    companion object {
        private val EMPTY = SolrFieldModel.of(SolrConfigsetFacts())

        /**
         * The reader for [project].
         *
         * @param project the project whose configsets are being modelled
         * @return the project-level reader service
         */
        fun getInstance(project: Project): SolrConfigsetReader = project.service()
    }
}
