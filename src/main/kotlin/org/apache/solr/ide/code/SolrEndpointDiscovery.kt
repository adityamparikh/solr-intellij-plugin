package org.apache.solr.ide.code

import com.intellij.openapi.module.Module
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiFile
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.PsiSearchHelper
import org.apache.solr.ide.code.spring.SpelledEndpoint
import org.apache.solr.ide.code.spring.SpringActiveProfiles
import org.apache.solr.ide.code.spring.SpringBootEndpoints
import org.apache.solr.ide.code.spring.SpringConfigFiles
import org.apache.solr.ide.configset.activation.SolrProjectDetector

/**
 * The Solr servers a project's own code and configuration say it talks to, as connection candidates.
 *
 * **Code first, configuration second, and configuration only for a framework that is there.** What
 * the client builders spell is read through [SolrRecognizers], behind the same module gate as every
 * other code surface: a literal URL is a candidate as it stands, and a `${…}` reference is handed to
 * the framework whose configuration can resolve it. Spring Boot is the one framework this release
 * resolves, and only on a module that carries it — a Quarkus module's `application.properties` is
 * not a Spring file, and reading it as one would be shipping Quarkus support by accident.
 *
 * **Not on the editor path, and never while indexing.** Finding the files that construct a client
 * uses the word index, and reading a construction resolves the builder class; both need a smart
 * project. The settings page that offers these asks in the background once indexing is done, and a
 * call made during indexing answers with nothing rather than with a partial list.
 */
object SolrEndpointDiscovery {

    /**
     * Every candidate in [project], across its modules.
     *
     * @param project the project to look through
     * @return the candidates, each server, user and profile once; empty while the project is indexing
     */
    fun candidatesIn(project: Project): List<SolrEndpointCandidate> {
        if (DumbService.isDumb(project)) return emptyList()
        return ModuleManager.getInstance(project).modules
            .filter { SolrRecognizers.recognizeSolrIn(it) }
            .flatMap { candidatesIn(it) }
            .distinctBy { Triple(it.url, it.username, it.profile) }
    }

    private fun candidatesIn(module: Module): List<SolrEndpointCandidate> {
        val usages = filesNamingSolrJ(module).flatMap { SolrRecognizers.endpointsIn(it) }
        val literal = usages.filter { isUrl(it.url) }.map { usage ->
            SolrEndpointCandidate(
                url = usage.url,
                // A username the code injects has no value until a framework resolves it, and a
                // literal URL beside it gives no framework anything to resolve against.
                username = usage.username?.takeUnless { REFERENCE_START in it },
                password = null,
                profile = null,
                active = false,
                origin = "SolrJ in ${usage.element.containingFile.name}",
            )
        }
        val spring = if (SolrProjectDetector.getInstance(module.project).moduleDependsOn(module, SPRING_BOOT)) {
            SpringBootEndpoints.candidates(
                SpringConfigFiles.sourcesIn(module),
                usages.map { SpelledEndpoint(it.url, it.username) },
                SpringActiveProfiles.of(module),
            )
        } else {
            emptyList()
        }
        return spring + literal
    }

    /**
     * The module's files that mention SolrJ at all, by the word index.
     *
     * Every file that constructs a SolrJ client imports from `org.apache.solr.client.solrj`, so the
     * word `solrj` finds them without parsing the rest of the module.
     */
    private fun filesNamingSolrJ(module: Module): List<PsiFile> {
        val files = mutableListOf<PsiFile>()
        PsiSearchHelper.getInstance(module.project)
            .processAllFilesWithWord(SOLRJ_WORD, GlobalSearchScope.moduleScope(module), { files += it; true }, true)
        return files
    }

    private fun isUrl(value: String) = value.startsWith("http://") || value.startsWith("https://")

    private const val SOLRJ_WORD = "solrj"
    private const val REFERENCE_START = "\${"

    /** Spring Boot's artifacts, matched as a substring of the library name as the module gate does. */
    private val SPRING_BOOT = listOf("spring-boot")
}
