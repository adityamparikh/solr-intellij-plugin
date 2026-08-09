package org.apache.solr.ide.configset.inspection

import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.codeInspection.ProblemHighlightType
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.psi.PsiElementVisitor
import com.intellij.psi.XmlElementVisitor
import com.intellij.psi.xml.XmlTag
import org.apache.solr.ide.SolrBundle
import org.apache.solr.ide.configset.parsing.SolrConfigParameters
import org.apache.solr.ide.configset.parsing.SolrConfigsetReader
import org.apache.solr.ide.model.SolrFieldOperation
import org.apache.solr.ide.model.SolrFieldOperations

/**
 * Reports a faceting or sorting parameter in `solrconfig.xml` naming a field that cannot serve it.
 *
 * **This fails in the opposite direction from the rest of this package, which is why it is worth
 * having.** Every other inspection here guards against warning on a correct file. This one guards
 * against silence on a broken one: a `facet.field` or a `sort` naming a field with neither doc values
 * nor an un-invertible index is not a subtle inefficiency, it is a request Solr refuses outright, and
 * the handler that carries it answers every query with an error. Nothing in the configset says so.
 *
 * **Faceting and sorting want a different structure from searching**, which is why this is not folded
 * into [SolrNonIndexedRelevanceFieldInspection]. Both operations read a field's values per document
 * rather than its terms, so doc values serve them directly and an inverted index serves them only by
 * being un-inverted into memory first — which is what `uninvertible` governs, and which defaults
 * *false* from schema version 1.7. A field that is perfectly searchable can therefore be unfacetable,
 * and the same field in a `qf` and a `facet.field` deserves a warning in one place and not the other.
 *
 * **The rule is [SolrFieldOperations]', not this class's.** It is a disjunction over three properties
 * with a version-dependent default in the middle of it, and it has readers outside the configuration
 * surface: the same question decides whether a SolrJ `addFacetField` will be rejected, and which
 * fields a query console should offer while a reader types a facet parameter.
 *
 * **Only a definite no is reported**, as everywhere else. A null from the model means a property the
 * rule needs is undetermined — a field type whose class the catalog has never seen — and a custom type
 * is not evidence of a defect.
 *
 * No quick-fix is offered. The repairs are to add doc values, to make the field un-invertible, or to
 * facet on something else; the first two edit another file and force a reindex, and the third changes
 * what the query means. None is a correction of the text under the caret.
 */
class SolrUnsupportedFieldOperationInspection : LocalInspectionTool() {

    /**
     * Runs while the project is still indexing.
     *
     * The model is parsed from the configset's own text and no index is consulted, so deferring this
     * until indexing finishes would withhold a working feature for no reason.
     */
    override fun isDumbAware(): Boolean = true

    /**
     * @param holder collects the problems found
     * @param isOnTheFly whether this is an editor pass rather than a batch run
     * @return a visitor over parameter values, or an empty one outside a configset
     */
    override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean): PsiElementVisitor {
        if (holder.file.name != "solrconfig.xml") return PsiElementVisitor.EMPTY_VISITOR
        val model = SolrConfigsetReader.getInstance(holder.project).modelFor(holder.file)
            ?: return PsiElementVisitor.EMPTY_VISITOR

        return object : XmlElementVisitor() {
            override fun visitXmlTag(tag: XmlTag) {
                for (occurrence in SolrConfigParameters.fieldNameOccurrences(tag)) {
                    val operation = OPERATIONS[occurrence.parameterName] ?: continue
                    if (!SolrInspections.isCheckableFieldName(occurrence.fieldName)) continue
                    // An undeclared field is the unknown-reference inspection's finding, and saying it
                    // twice on one underline is worse than saying it once.
                    val field = model.resolve(occurrence.fieldName) ?: continue
                    val fieldType = model.typeOf(field)
                    val supported = SolrFieldOperations.supports(
                        operation,
                        field,
                        fieldType,
                        model.schemaVersion,
                        model.traitsOf(fieldType),
                    )
                    if (supported != false) continue
                    holder.registerProblem(
                        tag,
                        SolrBundle.message(
                            "inspection.unsupportedFieldOperation.unusable",
                            occurrence.fieldName,
                            occurrence.parameterName,
                        ),
                        ProblemHighlightType.GENERIC_ERROR_OR_WARNING,
                        occurrence.rangeInTag,
                    )
                }
            }
        }
    }

    private companion object {

        /**
         * The parameters that read a field's values per document, and the operation each asks for.
         *
         * Kept to the parameters whose *whole value* is a field list, because that is what the
         * occurrence mapping can locate. `group.field` joins the sort family because grouping orders
         * documents by that field's value and fails the same way. `facet.pivot` takes a comma-separated
         * list of fields to facet on, so every name in it is faceted.
         */
        val OPERATIONS = mapOf(
            "facet.field" to SolrFieldOperation.FACET,
            "facet.pivot" to SolrFieldOperation.FACET,
            "sort" to SolrFieldOperation.SORT,
            "group.sort" to SolrFieldOperation.SORT,
            "group.field" to SolrFieldOperation.SORT,
        )
    }
}
