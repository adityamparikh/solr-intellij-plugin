package org.apache.solr.ide.configset

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference

/**
 * Resolves which [SolrConfigset] owns a given file, with a cache sized for the editor path.
 *
 * Resolution answers a different question from [SolrConfigsetDetector.isConfigsetFile]. The detector
 * decides *whether* to activate; this decides *what model to activate against*, which matters as
 * soon as a project holds more than one configset.
 *
 * Nothing resolves at all unless [SolrProjectDetector] says the project depends on a Solr client, or
 * the user marked the directory by hand. That gate is what lets the rules below stay as simple as
 * they are: once the project is known to be a Solr project, a recognized file name means what it
 * says, and no corroboration is needed.
 *
 * **Why this is cached.** Resolution lists directory children, and it runs on every file the user
 * opens — and, once inspections and reference resolution land, repeatedly while they type. Listing
 * the same `conf/` directory on every keystroke is affordable today and will not be. Results are
 * therefore memoized per starting directory and dropped wholesale when either input changes:
 *
 *  - the VFS *structure* (files created, deleted, renamed or moved). Content edits are deliberately
 *    ignored — every signal here is a name or a directory listing, so editing inside `schema.xml`
 *    cannot change the answer, and invalidating on content would throw the cache away constantly.
 *  - [SolrConfigsetSettings], because marking a manual root changes resolution without touching a
 *    single file on disk.
 *
 * Dropping the whole cache rather than the affected entries is intentional: entries are cheap to
 * recompute, structural changes are rare compared with lookups, and per-entry invalidation would
 * have to model which directories a given change could affect — a source of stale-cache bugs out of
 * all proportion to the saving.
 */
@Service(Service.Level.PROJECT)
class SolrConfigsetLocator(private val project: Project) {

    /**
     * Memoized resolution, keyed by the path of the directory a lookup started from.
     *
     * Keyed by path rather than by [VirtualFile] so that a deleted-and-recreated file, which is a
     * different instance for the same path, does not leak an entry.
     */
    private val cache = ConcurrentHashMap<String, Resolution>()

    /** The input state the cached entries were computed against; a change invalidates all of them. */
    private val cacheStamp = AtomicReference(Stamp(-1, -1))

    /**
     * The configset owning [file], or null if it belongs to none.
     *
     * Returns null while detection is switched off in [SolrConfigsetSettings], matching
     * [SolrConfigsetDetector]: the master switch has to silence every surface, not just activation.
     *
     * @param file the file or directory to resolve; a directory resolves from itself
     * @return the owning configset, or null
     */
    fun configsetFor(file: VirtualFile): SolrConfigset? {
        val settings = SolrConfigsetSettings.getInstance(project)
        if (!settings.isDetectionEnabled) return null

        val from = if (file.isDirectory) file else file.parent ?: return null

        // The project-level gate. A marked root bypasses it deliberately: a configset repository
        // with no build file has no dependencies to find, and marking the directory is the user
        // saying so explicitly, which is better evidence than any dependency.
        if (!SolrProjectDetector.getInstance(project).isSolrProject() &&
            settings.manualRootFor(from) == null
        ) {
            return null
        }

        invalidateIfStale(settings)
        return cache.computeIfAbsent(from.path) { Resolution(resolveRoot(from, settings)) }
            .root
            ?.takeIf { it.isValid }
            ?.let { SolrConfigset(it) }
    }

    /**
     * Discards every memoized resolution.
     *
     * Exposed for tests, which mutate settings and the filesystem faster than the trackers this
     * class watches are guaranteed to distinguish.
     */
    fun dropCache() {
        cache.clear()
    }

    /**
     * The number of entries currently memoized.
     *
     * Exposed so tests can assert that a lookup was actually served from the cache, and that an
     * invalidating change actually cleared it — behavior with no other observable effect.
     */
    val cacheSize: Int get() = cache.size

    private fun invalidateIfStale(settings: SolrConfigsetSettings) {
        val current = Stamp(
            vfsStructure = VirtualFileManager.getInstance().structureModificationCount,
            settings = settings.state.modificationCount,
        )
        if (cacheStamp.getAndSet(current) != current) cache.clear()
    }

    /**
     * Walks up from [from] looking for the configset root that owns it.
     *
     * Manual roots are consulted first and without a depth limit: the user asserted the directory is
     * a configset, so the heuristics have nothing to add, and the check is a handful of string
     * comparisons over a list that is almost always empty.
     *
     * The heuristic walk is bounded instead. Two levels covers what real layouts need — a file
     * directly in `conf/`, and a language resource in `conf/lang/` — while keeping the worst case on
     * the editor path to three directory listings. Walking to the project root would find configsets
     * nobody nests that deep at the cost of paying for every unrelated file the user opens; the
     * manual override exists precisely for layouts this misses.
     */
    private fun resolveRoot(from: VirtualFile, settings: SolrConfigsetSettings): VirtualFile? {
        settings.manualRootFor(from)?.let { return it }

        var directory: VirtualFile? = from
        var ascended = 0
        while (directory != null && ascended <= MAX_ASCENT) {
            if (hasConfigsetEvidence(directory)) return directory
            directory = directory.parent
            ascended++
        }
        return null
    }

    /**
     * Whether [directory] holds a self-identifying configset file.
     *
     * One is enough, and only the self-identifying tier counts. An earlier revision required
     * corroboration from the surroundings — a second recognized file, or a `conf/` parent — which
     * was an inference about a directory. This asks a narrower question with an exact answer: does
     * this directory contain a file whose *name* is Solr's own vocabulary, such as `solrconfig.xml`
     * or `managed-schema.xml`.
     *
     * Ambiguous names deliberately do not count; see [SolrConfigsetFileRole.AMBIGUOUS]. Otherwise an
     * XSD called `schema.xml`, in a project that uses Solr somewhere else entirely, would make its
     * directory a configset. The project-level check in [SolrProjectDetector] cannot prevent that —
     * it establishes that the project uses Solr, not that this file is Solr's.
     */
    private fun hasConfigsetEvidence(directory: VirtualFile): Boolean =
        directory.children.orEmpty().any { child ->
            !child.isDirectory && child.name in SolrConfigsetFileKind.SELF_IDENTIFYING_FILE_NAMES
        }

    /** Memoized outcome of one resolution, including the negative one. */
    private class Resolution(val root: VirtualFile?)

    /** The tracker values a cache generation was computed against. */
    private data class Stamp(val vfsStructure: Long, val settings: Long)

    /** Service lookup and the tuning constants behind resolution. */
    companion object {
        /**
         * How many parent directories a heuristic walk may climb past the starting directory.
         *
         * Two, so `conf/lang/stopwords_en.txt` reaches `conf/`.
         */
        private const val MAX_ASCENT = 2

        /**
         * The locator for [project].
         *
         * @param project the project whose configsets are being resolved
         * @return the project-level service holding the resolution cache
         */
        fun getInstance(project: Project): SolrConfigsetLocator = project.service()
    }
}
