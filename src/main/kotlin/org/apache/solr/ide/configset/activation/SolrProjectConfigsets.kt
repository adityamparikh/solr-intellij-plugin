package org.apache.solr.ide.configset.activation

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope

/**
 * Every configset this project holds.
 *
 * **Found through the schema files that identify them rather than through a list someone
 * maintains.** `managed-schema` is self-identifying — nothing but Solr calls a file that — and every
 * configset has one of the schema spellings by definition, so the files are a better register of
 * what exists than anything a user or this plugin would have to keep up to date.
 *
 * Distinct from [SolrConfigsetDetector], which answers "which configset owns *this* file" for a file
 * the user is already in. This answers "what is there at all", which is the question every surface
 * that is not opened on a configset file has to ask — a query written in an `.http` file, a drift
 * comparison choosing what to compare.
 */
@Service(Service.Level.PROJECT)
class SolrProjectConfigsets(private val project: Project) {

    /**
     * The configsets in this project, in no particular order.
     *
     * **Empty during indexing rather than partial.** Finding them means asking the filename index,
     * which is unavailable in dumb mode; answering from whatever happened to be indexed so far would
     * give a list that grows for reasons the caller cannot see, and a caller that offered it would
     * look like it had found everything.
     *
     * @return every configset found, without duplicates
     */
    fun all(): List<SolrConfigset> {
        if (DumbService.isDumb(project)) return emptyList()
        val scope = GlobalSearchScope.projectScope(project)
        return SCHEMA_KINDS.flatMap { it.fileNames }
            .flatMap { FilenameIndex.getVirtualFilesByName(it, scope) }
            .mapNotNull { SolrConfigsetDetector.configsetFor(project, it) }
            .distinct()
    }

    /** Service lookup. */
    companion object {
        private val SCHEMA_KINDS =
            listOf(SolrConfigsetFileKind.SCHEMA_MANAGED, SolrConfigsetFileKind.SCHEMA_CLASSIC)

        /**
         * The configsets in [project].
         *
         * @param project the project to look in
         * @return the project-level service
         */
        fun getInstance(project: Project): SolrProjectConfigsets = project.service()
    }
}
