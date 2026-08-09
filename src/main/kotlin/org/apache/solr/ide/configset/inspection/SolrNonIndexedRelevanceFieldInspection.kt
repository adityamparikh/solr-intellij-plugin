package org.apache.solr.ide.configset.inspection

import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.codeInspection.ProblemHighlightType
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.psi.PsiElementVisitor
import com.intellij.psi.XmlElementVisitor
import com.intellij.psi.xml.XmlTag
import org.apache.solr.ide.configset.activation.SolrConfigsetFileKind
import org.apache.solr.ide.SolrBundle
import org.apache.solr.ide.configset.parsing.SolrConfigParameters
import org.apache.solr.ide.configset.parsing.SolrConfigsetReader
import org.apache.solr.ide.model.SolrFieldOperation
import org.apache.solr.ide.model.SolrFieldOperations

/**
 * Reports a query-field parameter in `solrconfig.xml` naming a field no query can search.
 *
 * The sibling of [SolrUnknownFieldReferenceInspection], asking a harder question at the same
 * position. There the field is missing and a reader who goes looking can at least see that; here the
 * field is right there in the schema, spelled correctly, and the parameter still does nothing — no
 * clause in the query and no boost to the score, because nothing was written that the query parser
 * could match against. Solr does not complain, the handler still answers, and the results are quietly
 * ranked by everything except the field the author thought they had emphasised.
 *
 * **The parameters examined are `qf`, `pf`, `pf2` and `pf3` — the DisMax query- and phrase-field
 * family — and no others.** Those four are the ones whose values become term and phrase queries.
 * `bf` and `boost` look like they belong beside them and do not: their values are function queries,
 * evaluated per document from doc values or an un-inverted field, so `bf=popularity` over a
 * non-indexed field with doc values is correct and common. Flagging those would be a warning on a
 * working configuration, which is the failure this package is organised to avoid.
 *
 * **Whether a field is searchable is [SolrFieldOperations]' question, not this class's.** It was read
 * here as `indexed="false"` and that was wrong: Solr turns an exact match on a doc-values-only field
 * into a single-value range query over the doc values rather than refusing it, so such a field *is*
 * searchable and this inspection warned about a working configuration. The rule is a disjunction over
 * two properties, it belongs to the model, and it has readers outside this file — the code surface
 * asks it of a SolrJ call and a query console asks it while completing a parameter. Three
 * implementations would be three chances to disagree.
 *
 * **A dynamic field is flagged like any other.** [org.apache.solr.ide.model.SolrFieldModel.resolve]
 * applies Solr's own longest-literal rule, so what a `qf` token resolves to here is what it resolves
 * to at query time, and its properties are as real. This case earns its keep rather than merely being
 * consistent: a schema carrying the common `<dynamicField name="*" .../>` catch-all leaves the
 * unknown-field inspection with nothing to say about *any* name, and this is then the only check
 * that can tell the author their `qf` is searching an ignore rule.
 *
 * **Only a definite no is reported.** A null from the model means a property the rule needs is
 * undetermined — a field type whose class the catalog has never seen — and that is exactly where
 * asserting a default would be inventing one.
 *
 * No quick-fix is offered, and that is deliberate. The two repairs are to index the field or to name
 * a different one, and both are decisions about what the search is meant to do — the first edits
 * another file and forces a reindex, the second changes the query. Neither is a typo correction, so
 * neither belongs behind Alt-Enter beside suggestions that are.
 */
class SolrNonIndexedRelevanceFieldInspection : LocalInspectionTool() {

    /**
     * Runs while the project is still indexing.
     *
     * Nothing here consults an index — the model is parsed from the configset's own text — so the
     * platform's default of skipping this until indexing finishes would withhold a working feature
     * for no reason, exactly when a reader is most likely to be opening files for the first time.
     */
    override fun isDumbAware(): Boolean = true

    /**
     * @param holder collects the problems found
     * @param isOnTheFly whether this is an editor pass rather than a batch run
     * @return a visitor over parameter values, or an empty one outside a configset
     */
    override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean): PsiElementVisitor {
        // Only `solrconfig.xml` carries these; running the visitor over a schema would be wasted
        // work on every keystroke.
        if (SolrConfigsetFileKind.forFileName(holder.file.name)?.isSolrConfig != true) {
            return PsiElementVisitor.EMPTY_VISITOR
        }
        val model = SolrConfigsetReader.getInstance(holder.project).modelFor(holder.file)
            ?: return PsiElementVisitor.EMPTY_VISITOR

        return object : XmlElementVisitor() {
            override fun visitXmlTag(tag: XmlTag) {
                for (occurrence in SolrConfigParameters.fieldNameOccurrences(tag)) {
                    if (SolrFieldOperation.forParameter(occurrence.parameterName) != SolrFieldOperation.SEARCH) continue
                    if (!SolrInspections.isCheckableFieldName(occurrence.fieldName)) continue
                    // An undeclared field is the other inspection's finding. Saying it twice, in two
                    // vocabularies, on the same underline is worse than saying it once.
                    val field = model.resolve(occurrence.fieldName) ?: continue
                    val fieldType = model.typeOf(field)
                    val searchable = SolrFieldOperations.supports(
                        SolrFieldOperation.SEARCH,
                        field,
                        fieldType,
                        model.schemaVersion,
                        model.traitsOf(fieldType),
                    )
                    // Only a definite no. Null means a property the rule needs is undetermined — a
                    // custom field type, most often — and asserting a default there is how this
                    // inspection would start inventing one.
                    if (searchable != false) continue
                    holder.registerProblem(
                        tag,
                        SolrBundle.message(
                            "inspection.nonIndexedRelevanceField.notSearchable",
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
}
