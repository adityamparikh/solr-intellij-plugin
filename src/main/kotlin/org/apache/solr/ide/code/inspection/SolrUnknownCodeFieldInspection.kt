package org.apache.solr.ide.code.inspection

import com.intellij.codeInspection.InspectionManager
import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.codeInspection.ProblemDescriptor
import com.intellij.codeInspection.ProblemHighlightType
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiFile
import org.apache.solr.ide.SolrBundle
import org.apache.solr.ide.code.SolrRecognizers
import org.apache.solr.ide.configset.activation.SolrProjectConfigsets
import org.apache.solr.ide.configset.editing.SolrInspections
import org.apache.solr.ide.configset.reading.SolrConfigsetReader

/**
 * Reports a field name written in Java or Kotlin that no configset in this project declares.
 *
 * **The gap this closes is the one the specification opens with.** A field name inside
 * `addFilterQuery("categry:books")` or `@Field("prce")` is a string. It compiles, it deploys, and
 * Solr answers a query against a field that does not exist with zero results rather than an error —
 * so the mistake surfaces as an empty page in production, and nothing between the typo and there
 * has any reason to look at it.
 *
 * **Silence wherever the answer is not certain, and here that means three separate refusals.**
 *
 * The first is the module: the recognizers run only where a Solr client is on the classpath, so a
 * module that has never heard of Solr is not examined at all.
 *
 * The second is the file: only names the source spells out are read, which
 * [org.apache.solr.ide.code.solrj.SolrJRecognizer] decides and this inherits.
 *
 * The third is the one that belongs here, and it is the one worth stating plainly: **a project with
 * no configsets to check against reports nothing.** That is not a special case, it is the ordinary
 * one — a service talking to a Solr whose schema lives in another repository is the normal
 * deployment, and warning there would make this inspection wrong for most of its users. It is also
 * what keeps the check honest while the IDE is still indexing, when the configsets are not yet
 * findable and an eager reading of "no configset declares it" would underline every field name in
 * the codebase at once.
 */
class SolrUnknownCodeFieldInspection : LocalInspectionTool() {

    /**
     * Waits for indexing, unlike every other inspection in this plugin.
     *
     * The others parse a configset's own text and need nothing else. This one has to find the
     * project's configsets first, which goes through the filename index, and that index answers
     * nothing during indexing. Running early would not degrade the check, it would invert it: every
     * field name would be undeclared until the index finished.
     */
    override fun isDumbAware(): Boolean = false

    /**
     * Checks a whole file rather than visiting elements one at a time.
     *
     * The recognizer's unit is a file: it builds a UAST view once and reads every usage out of it,
     * which is the expensive part. Registering an element visitor would rebuild that view for each
     * node and ask it about one.
     *
     * @param file the Java or Kotlin file being inspected
     * @param manager creates the problem descriptors
     * @param isOnTheFly whether this is an editor pass rather than a batch run
     * @return the problems found, or null where there are none
     */
    override fun checkFile(file: PsiFile, manager: InspectionManager, isOnTheFly: Boolean): Array<ProblemDescriptor>? {
        val usages = SolrRecognizers.fieldUsagesIn(file).filter { SolrInspections.isCheckableFieldName(it.fieldName) }
        if (usages.isEmpty()) return null

        val declaring = configsetModelsIn(file.project) ?: return null
        val problems = usages
            .filterNot { usage -> declaring.any { it.resolve(usage.fieldName) != null } }
            .map { usage ->
                manager.createProblemDescriptor(
                    usage.element,
                    // The name inside the expression rather than the expression itself: a warning
                    // over `"categry:books"` entire underlines the quotes and the value a reader
                    // already knows are fine, and hides which half is wrong.
                    nameRangeIn(usage.element.text, usage.fieldName),
                    SolrBundle.message("inspection.codeField.unknown", usage.fieldName),
                    ProblemHighlightType.GENERIC_ERROR_OR_WARNING,
                    isOnTheFly,
                    *SolrInspections.replacementFixes(
                        usage.fieldName,
                        namesFor(file.project),
                        SolrBundle.message("inspection.codeField.family"),
                    ),
                )
            }
        return problems.takeIf { it.isNotEmpty() }?.toTypedArray()
    }

    /**
     * Where [name] sits inside [text], or null to let the whole element carry the warning.
     *
     * Null where the name is not found verbatim, which happens when the source spelled it across a
     * concatenation: there is no single range to point at, and pointing at part of one would be
     * worse than pointing at all of it.
     */
    private fun nameRangeIn(text: String, name: String): TextRange? =
        text.indexOf(name).takeIf { it >= 0 }?.let { TextRange(it, it + name.length) }

    /**
     * A model per configset the project holds, or null where it holds none.
     *
     * **Null rather than an empty list, because the two mean opposite things.** An empty list reads
     * as "no configset declares this name", which is true of every name and would condemn all of
     * them; null says the question cannot be asked at all. Collapsing the two is how a check that
     * cannot see becomes a check that accuses.
     */
    private fun configsetModelsIn(project: Project) =
        SolrProjectConfigsets.getInstance(project).all()
            .takeIf { it.isNotEmpty() }
            ?.map { SolrConfigsetReader.getInstance(project).modelFor(it) }

    /** Every name the project's configsets declare, for the "did you mean" fixes. */
    private fun namesFor(project: Project): Set<String> {
        val reader = SolrConfigsetReader.getInstance(project)
        return SolrProjectConfigsets.getInstance(project).all()
            .flatMapTo(mutableSetOf()) { reader.modelFor(it).fields.keys }
    }
}
