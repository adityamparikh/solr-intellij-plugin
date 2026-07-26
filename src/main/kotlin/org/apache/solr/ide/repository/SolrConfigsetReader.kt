package org.apache.solr.ide.repository

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import org.apache.solr.ide.configset.SolrConfigset
import org.apache.solr.ide.configset.SolrConfigsetFileKind
import org.apache.solr.ide.model.SolrConfigsetFacts
import org.apache.solr.ide.model.SolrFieldModel
import java.util.concurrent.ConcurrentHashMap

/**
 * Builds and caches the field model for each configset in the project.
 *
 * The spine: every feature that knows what a field is reads it from here.
 *
 * **Caching is keyed on the modification stamps of the files actually read**, not on a global
 * counter. The model is a function of the text of the schema and `solrconfig.xml`, so it must be
 * rebuilt when either changes and — the half that matters for the editor path — must *not* be
 * rebuilt when anything else does. A model rebuilt on every VFS event would parse both files on
 * every keystroke anywhere in the project.
 *
 * **Unsaved edits count.** The text comes from the in-memory document when one exists, so a field
 * added in the editor is in the model before the file is written. Reading the file from disk would
 * mean the plugin disagreed with what the user is looking at until they hit save.
 */
@Service(Service.Level.PROJECT)
class SolrConfigsetReader(private val project: Project) {

    private val cache = ConcurrentHashMap<String, CacheEntry>()

    /**
     * The field model for [configset], parsed if the cache has nothing current.
     *
     * @param configset the configset to model
     * @return its model, which is empty rather than null when the configset holds no readable files
     */
    fun modelFor(configset: SolrConfigset): SolrFieldModel {
        val sources = sourcesOf(configset)
        val stamps = sources.map { stampOf(it) }
        cache[configset.root.path]
            ?.takeIf { it.stamps == stamps }
            ?.let { return it.model }

        val facts = sources.fold(SolrConfigsetFacts()) { accumulated, file -> accumulated + factsOf(file) }
        val model = SolrFieldModel.of(facts)
        cache[configset.root.path] = CacheEntry(stamps, model)
        return model
    }

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
        sourcesOf(configset).fold(SolrConfigsetFacts()) { accumulated, file -> accumulated + factsOf(file) }

    /**
     * Discards every cached model.
     *
     * Exposed for tests, which edit fixtures faster than modification stamps are guaranteed to
     * distinguish.
     */
    fun dropCache() {
        cache.clear()
    }

    /** The number of configsets currently cached, so tests can observe that caching happened. */
    val cacheSize: Int get() = cache.size

    /**
     * The files in [configset] that contribute to the model, in a stable order.
     *
     * Only the schema and `solrconfig.xml` are read today. The other identifying files —
     * `elevate.xml`, `currency.xml` — describe things the field model does not yet represent, and
     * reading them would add facts nothing consumes.
     */
    private fun sourcesOf(configset: SolrConfigset): List<VirtualFile> =
        configset.root.children.orEmpty()
            .filter { !it.isDirectory }
            .filter { SolrConfigsetFileKind.forFileName(it.name)?.let(::contributesToModel) == true }
            .sortedBy { it.name }

    private fun contributesToModel(kind: SolrConfigsetFileKind): Boolean =
        kind.isSchema || kind == SolrConfigsetFileKind.SOLR_CONFIG

    private fun factsOf(file: VirtualFile): SolrConfigsetFacts {
        val text = textOf(file) ?: return SolrConfigsetFacts()
        val kind = SolrConfigsetFileKind.forFileName(file.name)
        return when {
            kind?.isSchema == true -> SolrSchemaParser.parse(text)
            kind == SolrConfigsetFileKind.SOLR_CONFIG -> SolrConfigParser.parse(text)
            else -> SolrConfigsetFacts()
        }
    }

    /**
     * The file's current text, preferring the in-memory document when one already exists.
     *
     * `getCachedDocument` rather than `getDocument`, deliberately. The latter *creates* a document
     * as a side effect, which made the text and the stamp disagree on first read: the stamp came
     * from the file, then the newly-created document supplied a different one on the next call, and
     * the cache invalidated once for no reason. Reading a file that has never been opened straight
     * off disk is also cheaper than materialising a document for it.
     */
    private fun textOf(file: VirtualFile): CharSequence? =
        FileDocumentManager.getInstance().getCachedDocument(file)?.charsSequence
            ?: runCatching { String(file.contentsToByteArray(), file.charset) }.getOrNull()

    /**
     * A file's current version, preferring the in-memory document's stamp.
     *
     * A document's stamp changes on every edit, including edits never saved, which is precisely the
     * granularity the cache needs.
     */
    private fun stampOf(file: VirtualFile): Long =
        FileDocumentManager.getInstance().getCachedDocument(file)?.modificationStamp ?: file.modificationStamp

    private class CacheEntry(val stamps: List<Long>, val model: SolrFieldModel)

    /** Service lookup. */
    companion object {
        /**
         * The reader for [project].
         *
         * @param project the project whose configsets are being modelled
         * @return the project-level reader service
         */
        fun getInstance(project: Project): SolrConfigsetReader = project.service()
    }
}
