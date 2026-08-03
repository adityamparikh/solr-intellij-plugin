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
import org.apache.solr.ide.model.SolrFieldProperties

/**
 * Reports a query-field parameter in `solrconfig.xml` naming a field the schema declares but does
 * not index.
 *
 * The sibling of [SolrUnknownFieldReferenceInspection], asking a harder question at the same
 * position. There the field is missing and a reader who goes looking can at least see that; here the
 * field is right there in the schema, spelled correctly, and the parameter still does nothing. A
 * `qf` naming an `indexed="false"` field contributes no clause to the query and no boost to the
 * score: the terms were never written to the inverted index, so there is nothing for the query
 * parser to match against. Solr does not complain, the handler still answers, and the results are
 * quietly ranked by everything except the field the author thought they had emphasised.
 *
 * **The parameters examined are `qf`, `pf`, `pf2` and `pf3` — the DisMax query- and phrase-field
 * family — and no others.** Those four are the ones whose values become term and phrase queries
 * against the inverted index, which is the structure `indexed` governs. `bf` and `boost` look like
 * they belong beside them and do not: their values are function queries, evaluated per document
 * from doc values or an un-inverted field, so `bf=popularity` over a non-indexed field with doc
 * values is correct and common. Flagging those would be a warning on a working configuration, which
 * is the failure this package is organised to avoid.
 *
 * **A dynamic field is flagged like any other.** [org.apache.solr.ide.model.SolrFieldModel.resolve]
 * applies Solr's own longest-literal rule, so what a `qf` token resolves to here is what it resolves
 * to at query time, and its `indexed` is as real. This case earns its keep rather than merely being
 * consistent: a schema carrying the common `<dynamicField name="*" .../>` catch-all leaves the
 * unknown-field inspection with nothing to say about *any* name, and this is then the only check
 * that can tell the author their `qf` is searching an ignore rule.
 *
 * **`indexed` is resolved, never read.** The attribute is usually absent, and the answer then comes
 * from the field type, from the schema's declared `version`, or from Solr's default — a three-tier
 * resolution [SolrFieldProperties.resolve] already owns. Only an explicit, resolved `false` is
 * reported; [org.apache.solr.ide.model.SolrPropertyOrigin.UNDETERMINED] yields a null value and is
 * passed over in silence, because a field type whose class the catalog has never seen is exactly
 * where asserting a default would be inventing one.
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
        if (holder.file.name != "solrconfig.xml") return PsiElementVisitor.EMPTY_VISITOR
        val model = SolrConfigsetReader.getInstance(holder.project).modelFor(holder.file)
            ?: return PsiElementVisitor.EMPTY_VISITOR

        return object : XmlElementVisitor() {
            override fun visitXmlTag(tag: XmlTag) {
                for (occurrence in SolrConfigParameters.fieldNameOccurrences(tag)) {
                    if (occurrence.parameterName !in QUERY_FIELD_PARAMETERS) continue
                    if (!SolrInspections.isCheckableFieldName(occurrence.fieldName)) continue
                    // An undeclared field is the other inspection's finding. Saying it twice, in two
                    // vocabularies, on the same underline is worse than saying it once.
                    val field = model.resolve(occurrence.fieldName) ?: continue
                    val fieldType = model.typeOf(field)
                    val indexed = SolrFieldProperties.resolve(
                        INDEXED,
                        field,
                        fieldType,
                        model.schemaVersion,
                        model.traitsOf(fieldType),
                    )
                    if (indexed.value != "false") continue
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

    private companion object {

        /**
         * The parameters whose values become queries against the inverted index.
         *
         * DisMax's `qf` and edismax's inheritance of it, plus the phrase-field family. Deliberately
         * short: every parameter added here is a new way to be wrong about a correct file, and the
         * ones that look adjacent — `bf`, `boost`, `sort`, `facet.field` — are all served by doc
         * values rather than by the index, so `indexed="false"` says nothing about them.
         */
        val QUERY_FIELD_PARAMETERS = setOf("qf", "pf", "pf2", "pf3")

        /**
         * The `indexed` property, looked up once rather than per occurrence.
         *
         * Read from the table rather than restated, so that a future version-conditional or
         * type-conditional default is picked up here without this file changing.
         */
        val INDEXED = requireNotNull(SolrFieldProperties.byName("indexed")) {
            "the property table must carry 'indexed'"
        }
    }
}
