package org.apache.solr.ide.configset.solrconfig.inspection

import com.intellij.codeInspection.ProblemHighlightType
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.psi.XmlElementVisitor
import com.intellij.psi.xml.XmlTag
import org.apache.solr.ide.SolrBundle
import org.apache.solr.ide.configset.editing.SolrInspections
import org.apache.solr.ide.configset.solrconfig.SolrConfigParameters
import org.apache.solr.ide.configset.solrconfig.parsing.SolrConfigParser
import org.apache.solr.ide.model.SolrFieldModel
import org.apache.solr.ide.model.schema.SolrFieldOperation
import org.apache.solr.ide.model.schema.SolrFieldOperations
import org.apache.solr.ide.model.schema.SolrTypeTrait

/**
 * A visitor reporting every parameter in a `solrconfig.xml` whose field cannot serve the operation
 * that parameter asks for.
 *
 * **Shared between the two inspections that ask it, and no wider.** It lived in `configset.editing`
 * beside the rules both aspects use, which made a package every aspect depends on depend in turn on
 * this one — the direction the tree exists to forbid. Only `solrconfig.xml` has field-operation
 * parameters, so it belongs here; what stays in `configset.editing` is what a schema check also reads.
 *
 * **The rule about staying quiet is the part worth having in one place.** Two
 * inspections ask this question of different operations, and everything else about them was
 * identical: which occurrences to read, that an undeclared field is another inspection's finding,
 * and above all that only a definite `false` is reported while `null` — a property the rule could
 * not resolve — is passed over in silence. Written twice, that promise had two places to be broken.
 *
 * Each caller names the operations it owns rather than excluding its sibling's, so an operation
 * added to [SolrFieldOperation] belongs to neither inspection until someone says which, instead of
 * falling into whichever one used a negation.
 *
 * Traits are memoised by type name for the life of the visitor: resolving them scans the generated
 * catalog linearly, and a handler naming twenty fields would otherwise pay twenty scans per
 * highlighting pass where a schema names a dozen types.
 *
 * @param holder collects the problems found
 * @param model the configset's field model
 * @param messages the operations this inspection owns, each mapped to the bundle key for its warning,
 *   which takes the field name then the parameter name. One map rather than a set and a single key,
 *   because an operation and what to say when a field cannot serve it are the same fact — separating
 *   them produced one message for both, which told a reader that faceting needs a single value per
 *   document. That is sorting's requirement; faceting has none, and multiValued fields are exactly
 *   what one facets on
 * @return the visitor
 */
internal fun fieldOperationVisitor(
    holder: ProblemsHolder,
    model: SolrFieldModel,
    messages: Map<SolrFieldOperation, String>,
): XmlElementVisitor {
    val traitsByType = HashMap<String, Set<SolrTypeTrait>?>()
    return object : XmlElementVisitor() {
        override fun visitXmlTag(tag: XmlTag) {
            for (occurrence in SolrConfigParameters.fieldNameOccurrences(tag)) {
                val operation = SolrConfigParser.operationFor(occurrence.parameterName) ?: continue
                val messageKey = messages[operation] ?: continue
                if (!SolrInspections.isCheckableFieldName(occurrence.fieldName)) continue
                // An undeclared field is the unknown-reference inspection's finding, and saying it
                // twice on one underline is worse than saying it once.
                val field = model.resolve(occurrence.fieldName) ?: continue
                val fieldType = model.typeOf(field)
                val traits = traitsByType.getOrPut(field.type) { model.traitsOf(fieldType) }
                val supported = SolrFieldOperations.supports(
                    operation,
                    field,
                    fieldType,
                    model.schemaVersion,
                    traits,
                )
                // Only a definite no. Null means a property the rule needs is undetermined — a
                // custom field type, most often — and asserting a default there is how an
                // inspection starts inventing one.
                if (supported != false) continue
                holder.registerProblem(
                    tag,
                    SolrBundle.message(messageKey, occurrence.fieldName, occurrence.parameterName),
                    ProblemHighlightType.GENERIC_ERROR_OR_WARNING,
                    occurrence.rangeInTag,
                )
            }
        }
    }
}
