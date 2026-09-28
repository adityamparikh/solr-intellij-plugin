package org.apache.solr.ide.code

import com.intellij.openapi.module.ModuleUtilCore
import com.intellij.psi.PsiFile
import org.apache.solr.ide.code.solrj.SolrJRecognizer
import org.apache.solr.ide.configset.activation.SolrProjectDetector

/**
 * Every recognizer, and the one gate they all pass through.
 *
 * **This is the entry point for anything that wants to know what a source file says about Solr.** An
 * inspection, a completion contributor or a reference provider asks here rather than asking a
 * recognizer, and in exchange never has to remember that recognizers are conditional. A recognizer
 * that is asked directly will happily read a module with no Solr on it at all.
 *
 * **The gate reads the project model and no index**, which is what makes it safe to run on every file
 * the user opens, including while the IDE is still indexing. Answering "does this module use SolrJ"
 * by resolving `org.apache.solr.client.solrj.SolrClient` through PSI would be exact, and would put
 * the widest index-reading path in the plugin on the editor's hot path. Library names carry their
 * coordinates, so reading those answers the same question for the cost of walking a module's own
 * order entries. The blind spot is known rather than unknown: a jar attached under a name that does
 * not carry its coordinates is not recognized, and that degrades to silence.
 */
object SolrRecognizers {

    /**
     * Every recognizer the plugin knows, in the order they are consulted.
     *
     * A list rather than an extension point, because nothing outside this plugin implements
     * [SolrUsageRecognizer] and an extension point exists to let something outside contribute. If
     * that changes the registration moves to `plugin.xml` and this object keeps its signature.
     */
    private val registered: List<SolrUsageRecognizer> = listOf(SolrJRecognizer)

    /**
     * The field references [file] makes through any recognized library.
     *
     * @param file the file a caret or an inspection is looking at
     * @return the field usages it contains, empty where the module carries no recognized library
     */
    fun fieldUsagesIn(file: PsiFile): List<SolrFieldUsage> =
        applicableTo(file).flatMap { it.readFieldUsages(file) }

    /**
     * The Solr servers [file] says it talks to, through any recognized library.
     *
     * @param file the file a caret or an inspection is looking at
     * @return the endpoints it names, empty where the module carries no recognized library
     */
    fun endpointsIn(file: PsiFile): List<SolrEndpointUsage> =
        applicableTo(file).flatMap { it.readEndpoints(file) }

    /**
     * Whether any recognized Solr library is on [file]'s module.
     *
     * The same gate [fieldUsagesIn] applies, asked without reading anything — which is what
     * completion needs, because it runs while a name is being typed and there is nothing written to
     * read. Exposed rather than reproduced: a second spelling of "is this module a Solr module"
     * would be a second answer able to disagree with this one.
     *
     * @param file the file a caret is in
     * @return true where at least one recognizer would run here
     */
    fun recognizeSolrIn(file: PsiFile): Boolean = applicableTo(file).isNotEmpty()

    /**
     * The recognizers whose library is on [file]'s module.
     *
     * The module is resolved once and each recognizer's own coordinates are asked about separately,
     * so a module carrying Camel's Solr component but not SolrJ runs the Camel recognizer alone.
     */
    private fun applicableTo(file: PsiFile): List<SolrUsageRecognizer> {
        val module = ModuleUtilCore.findModuleForPsiElement(file) ?: return emptyList()
        val detector = SolrProjectDetector.getInstance(file.project)
        return registered.filter { detector.moduleDependsOn(module, it.libraryCoordinates) }
    }
}
