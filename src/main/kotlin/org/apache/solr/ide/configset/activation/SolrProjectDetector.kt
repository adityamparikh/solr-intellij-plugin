package org.apache.solr.ide.configset.activation

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.OrderEnumerator
import com.intellij.openapi.roots.ProjectRootManager
import java.util.concurrent.atomic.AtomicReference

/**
 * Decides whether the open project is a Solr project, by looking for a Solr client among its
 * dependencies.
 *
 * This is the plugin's top-level activation gate: no Solr client in the project, no features. It
 * replaces the directory heuristics an earlier revision used to corroborate configset file names —
 * a `conf/` parent, or a second recognized file beside the first. A dependency is a fact rather than
 * an inference, so it needs no corroboration and produces no false positives to explain.
 *
 * **What this trades away.** A repository holding configsets and nothing else — XML deployed to
 * ZooKeeper by CI, with no build file and therefore no dependencies — cannot satisfy this gate.
 * Such a project activates only when the user marks a configset root explicitly in
 * [SolrConfigsetSettings], which is why that override survives this change rather than being
 * removed with the heuristics it used to back up.
 *
 * **Why library names rather than classes.** Resolving `org.apache.solr.client.solrj.SolrClient`
 * through PSI would be exact, but it requires the Java plugin, and the plugin deliberately does not
 * depend on it outside the code-recognizer work. Library *names* come from the project model, which
 * is available everywhere, and both Gradle and Maven put the Maven coordinates into the name they
 * generate.
 */
@Service(Service.Level.PROJECT)
class SolrProjectDetector(private val project: Project) {

    private val cached = AtomicReference<Answer?>(null)

    /**
     * Whether this project depends on a Solr client.
     *
     * Cached against the project's root-modification count, so a dependency added to the build file
     * takes effect on the next Gradle or Maven sync without an IDE restart, and the library list is
     * not walked on every file the user opens.
     *
     * @return true if any module depends on a recognized Solr client library
     */
    fun isSolrProject(): Boolean {
        val stamp = ProjectRootManager.getInstance(project).modificationCount
        cached.get()?.takeIf { it.stamp == stamp }?.let { return it.value }
        val value = hasSolrClientLibrary()
        cached.set(Answer(stamp, value))
        return value
    }

    /**
     * Discards the cached answer.
     *
     * Exposed for tests, which add libraries to a fixture faster than the root-modification tracker
     * is guaranteed to distinguish.
     */
    fun dropCache() {
        cached.set(null)
    }

    /**
     * Walks the project's libraries under a read action.
     *
     * The read action is not optional. This reads the project model, and while the callers that
     * matter today — inspections, annotators, reference providers — already hold the lock, an action
     * or a background task does not. Taking it here rather than relying on the caller keeps the
     * guarantee local to the code that needs it; a nested read action is free.
     *
     * **`computeBlocking` rather than `computeCancellable`, and that is the whole of the choice.**
     * The deprecated `compute` this replaced delegated straight to `computeBlocking`, so the two
     * are the same code path and the swap changes nothing. `computeCancellable` is a different
     * contract: it asserts a background thread and can abandon the read, and this gate is called
     * from the EDT on every file the user opens.
     */
    private fun hasSolrClientLibrary(): Boolean = ReadAction.computeBlocking<Boolean, RuntimeException> {
        var found = false
        OrderEnumerator.orderEntries(project).librariesOnly().forEachLibrary { library ->
            val name = library.name
            if (name != null && SOLR_CLIENT_COORDINATES.any { it in name }) {
                found = true
            }
            !found // stop enumerating once a match is found
        }
        found
    }

    private class Answer(val stamp: Long, val value: Boolean)

    /** Service lookup and the list of libraries that count as a Solr client. */
    companion object {
        /**
         * Artifact ids that mark a project as a Solr project, matched as substrings of the library
         * name so that every version matches and no version is named here.
         *
         * **`solr-solrj` is the one that does the work.** Every wrapper below depends on SolrJ
         * transitively, and the IDE's library list holds resolved transitive dependencies, so a
         * project using Camel's Solr component has `solr-solrj` among its libraries whether or not
         * it names it. The wrappers are listed anyway as a safety net, for builds that shade or
         * relocate SolrJ and for the day one of them stops depending on it.
         *
         * Deliberately an explicit list rather than a match on `*solr*`. A pattern that loose would
         * fire on unrelated artifacts with "solr" in the name and on anything a user happened to
         * call `solr-config`. Every entry is a client library: something a project uses to *talk
         * to* Solr, not something that merely mentions it.
         *
         * A new client wrapper is a maintenance trigger for this list, but a missed one degrades to
         * "the wrapper still pulls in SolrJ", not to silence.
         */
        val SOLR_CLIENT_COORDINATES: List<String> = listOf(
            // Apache SolrJ, the reference client. Transitively present under all of the below.
            "solr-solrj",
            // Spring Data Solr and its Boot starter. Unmaintained upstream, still widely present.
            "spring-data-solr",
            "spring-boot-starter-data-solr",
            // Apache Camel's Solr component.
            "camel-solr",
            // Quarkus: the community Solr extension, and — since "camel-quarkus-solr" contains this
            // string — Camel's Quarkus component too, which "camel-solr" above does not match.
            "quarkus-solr",
            // The Quarkiverse JNoSQL Solr extension, under both artifact ids it has shipped as.
            "quarkus-jnosql-solr",
            "quarkus-jnosql-document-solr",
        )

        /**
         * The detector for [project].
         *
         * @param project the project whose dependencies are being inspected
         * @return the project-level service answering whether this is a Solr project
         */
        fun getInstance(project: Project): SolrProjectDetector = project.service()
    }
}
