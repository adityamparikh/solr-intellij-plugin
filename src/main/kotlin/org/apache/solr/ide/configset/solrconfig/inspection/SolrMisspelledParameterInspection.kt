package org.apache.solr.ide.configset.solrconfig.inspection

import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.psi.PsiElementVisitor
import com.intellij.psi.XmlElementVisitor
import com.intellij.psi.xml.XmlTag
import org.apache.solr.ide.SolrBundle
import org.apache.solr.ide.configset.activation.SolrConfigsetFileKind
import org.apache.solr.ide.configset.editing.SolrInspections
import org.apache.solr.ide.configset.reading.SolrConfigsetReader
import org.apache.solr.ide.configset.solrconfig.SolrConfigParameters
import org.apache.solr.ide.model.vocabulary.SolrParameterCatalog

/**
 * Reports a request parameter whose name is almost one Solr ships.
 *
 * **The one rule the catalog can prove wrong, and the reason it is the only one.** A parameter name
 * the generated catalog does not carry is the ordinary case rather than an error: `solrconfig.xml`
 * accepts components from outside Solr that read parameters of their author's choosing, so absence
 * proves nothing and flagging it would put a warning on every project with a custom component. What
 * is reportable is a name that is *almost* one of Solr's, because nothing else explains writing it.
 *
 * **Never fires on a name the catalog knows**, which is what keeps Solr's own parameter families
 * quiet. `pf2` and `pf3` are genuinely different parameters one edit apart, and a rule that compared
 * every name against every other would report each as a misspelling of the other in a file that is
 * completely correct. Knownness is checked first and decides the matter.
 *
 * The value-set half of what this step originally asked for is deliberately absent: closedness is
 * knowable for only a minority of parameters, and an inspection firing on the knowable few while
 * silent on the rest teaches a reader that an unflagged value was checked.
 */
class SolrMisspelledParameterInspection : LocalInspectionTool() {

    /**
     * Runs while the project is still indexing.
     *
     * Nothing here consults an index — the catalog is a generated resource and the parameter names
     * come from the file's own text.
     */
    override fun isDumbAware(): Boolean = true

    /**
     * @param holder collects the problems found
     * @param isOnTheFly whether this is an editor pass rather than a batch run
     * @return a visitor over parameter names, or an empty one outside a configset's `solrconfig.xml`
     */
    override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean): PsiElementVisitor {
        if (SolrConfigsetFileKind.forFileName(holder.file.name)?.isSolrConfig != true) {
            return PsiElementVisitor.EMPTY_VISITOR
        }
        // **Declines without a configset, though the catalog alone could answer.** This rule reads no
        // schema, so a version fallback would let it work in any file carrying the name — and that is
        // exactly what would make it the one surface the detection master switch fails to silence.
        // A null model is the shape both that switch and a project with no Solr dependency arrive in,
        // and each of them is the user saying not to treat this file as Solr's.
        val version = SolrConfigsetReader.getInstance(holder.project).modelFor(holder.file)?.solrVersion
            ?: return PsiElementVisitor.EMPTY_VISITOR
        val known = SolrParameterCatalog.parametersFor(version).map { it.name }

        return object : XmlElementVisitor() {
            override fun visitXmlTag(tag: XmlTag) {
                val written = SolrConfigParameters.parameterNameOf(tag) ?: return
                // Not redundant with the check inside [parameterNameOf], which this repeats only for
                // a tag carrying its own `name`. Inside an `<arr name="facet.field">` that helper
                // answers with the array's name for every child `<str>`, and that name belongs to
                // the `<arr>`, which the visitor checks when it reaches it.
                if (!SolrConfigParameters.enclosingIsParameterList(tag.parentTag)) return
                // Knownness decides it. A name Solr ships is correct however close it sits to
                // another, which is the whole of the `pf2`/`pf3` guarantee.
                if (SolrParameterCatalog.parameter(written, version) != null) return
                // A name the catalog knows members *below* is a family root, and Solr's convention is
                // that `X` switches a component on while `X.*` configures it. Without this, the very
                // idiom the convention exists for — `<str name="spellcheck">on</str>`, in all four
                // configsets Solr ships — reads as a typo of `spellcheck.q`, two edits away.
                //
                // It guards a gap that will recur rather than one name. The generator reads parameters
                // off the interfaces that declare them and drops a constant ending in a dot, correctly,
                // since `spellcheck.` is a stem and not a name; the bare toggle is declared elsewhere,
                // on the component itself. Every other component's toggle survives only because its
                // interface happens to declare it a second time.
                if (known.any { it.startsWith("$written.") }) return

                val suggestions = SolrInspections.nearMissesOf(written, known)
                if (suggestions.isEmpty()) return

                val anchor = tag.getAttribute(NAME)?.valueElement ?: return
                SolrInspections.reportOnValue(
                    holder,
                    anchor,
                    SolrBundle.message("inspection.parameter.misspelled", written, suggestions.first()),
                    SolrInspections.replacementFixes(
                        written,
                        suggestions,
                        SolrBundle.message("quickfix.parameter.family"),
                    ),
                )
            }
        }
    }

    private companion object {
        const val NAME = "name"
    }
}
