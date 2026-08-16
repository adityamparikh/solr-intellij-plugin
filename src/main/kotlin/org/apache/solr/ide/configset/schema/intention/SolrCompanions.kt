package org.apache.solr.ide.configset.schema.intention

import org.apache.solr.ide.model.schema.SolrField
import org.apache.solr.ide.model.SolrFieldModel
import org.apache.solr.ide.model.schema.SolrFieldType
import org.apache.solr.ide.model.schema.SolrMatchAnalysis
import org.apache.solr.ide.model.schema.SolrMatchCapability

/**
 * What adding a capability to one field would take.
 *
 * Computed before anything is written, so an intention can name the type it is about to use in the
 * text the user reads — a schema declaring more than one usable type is rare, but when it happens
 * the choice should be visible before it is taken rather than discovered afterwards.
 *
 * @property companionField the name of the field to add
 * @property typeName the field type the companion will use, whether reused or generated
 * @property generatesType true when [typeName] has to be written as well, false when the schema
 *   already declares it
 * @property multiValued true when the source field holds several values, which the companion must
 *   hold too: `copyField` carries every value across, and a single-valued destination fed by a
 *   multi-valued source fails while Solr is indexing rather than anywhere the editor could show it
 */
data class SolrCompanionPlan(
    val companionField: String,
    val typeName: String,
    val generatesType: Boolean,
    val multiValued: Boolean = false,
)

/**
 * The rules both companion intentions share, with the two policy questions left to the caller.
 *
 * The `_prefix` and `_exact` companions differ in exactly two places — what makes a field *already*
 * capable, and which declared types may be reused — and agree everywhere else. Sharing the rest is
 * what keeps them from drifting: a fix to the companion-name rule that reached one and not the other
 * would be invisible until a user hit it.
 */
internal object SolrCompanions {

    /**
     * The plan for giving [field] a capability, or null where the intention should not appear.
     *
     * Null covers every availability rule that both companions share, and each is a case where
     * acting would be wrong rather than merely unhelpful:
     *
     * - the field's type is not declared, so there is nothing to classify;
     * - the field already has the capability, so there is nothing to add;
     * - the chain held a factory [SolrMatchAnalysis] does not know, so it was not fully understood —
     *   the same bar the inline hint uses, and for the same reason;
     * - the companion name is already declared, and inventing a numbered variant is worse than
     *   declining;
     * - a type would have to be generated under a name the schema already uses for something else.
     *
     * **`indexed` is deliberately not consulted.** `copyField` copies the incoming document value
     * before analysis, so the source field's own `indexed` and `stored` settings do not affect what
     * the companion receives. A display-only field is the cleanest case for either pattern, and a
     * plausible-looking `indexed="true"` guard would have suppressed the feature precisely where it
     * helps most.
     *
     * @param field the field that may lack the capability
     * @param model the configset the field belongs to
     * @param suffix appended to [field]'s name to name the companion
     * @param generatedTypeName the type to write when nothing declared can be reused
     * @param alreadyCapable whether the source field already has what this would add
     * @param reusable whether a declared type is one this companion may point at
     * @return the plan, or null if the intention should not be offered
     */
    fun plan(
        field: SolrField,
        model: SolrFieldModel,
        suffix: String,
        generatedTypeName: String,
        alreadyCapable: (SolrMatchCapability) -> Boolean,
        reusable: (SolrFieldType) -> Boolean,
    ): SolrCompanionPlan? {
        val fieldType = model.typeOf(field) ?: return null
        val capability = SolrMatchAnalysis.of(fieldType)
        if (!capability.confident || alreadyCapable(capability)) return null

        val companionField = field.name + suffix
        if (companionField in model.fields) return null

        // First in document order wins, and the intention names the winner so that a schema
        // declaring several does not have one chosen for it silently.
        val multiValued = isMultiValued(field, fieldType)

        val declared = model.fieldTypes.values.map { it.effective }.firstOrNull(reusable)
        if (declared != null) {
            return SolrCompanionPlan(companionField, declared.name, generatesType = false, multiValued = multiValued)
        }

        // Only the free-name case is checked, because the other half of the rule cannot be reached:
        // a type under this name that *were* reusable would have been found above, and reuse never
        // falls through to here.
        if (generatedTypeName in model.fieldTypes) return null
        return SolrCompanionPlan(companionField, generatedTypeName, generatesType = true, multiValued = multiValued)
    }

    /**
     * Whether the source field holds several values, which the companion has to hold too.
     *
     * **This is the one property of the source that the companion cannot ignore.** `copyField` copies
     * every value the source receives, so a multi-valued source feeding a single-valued destination
     * fails at index time — Solr reports *multiple values encountered for non-multiValued copy field*
     * — and it fails on the user's data, long after the intention was accepted and the schema looked
     * fine. Writing a companion that cannot receive what it is fed is worse than not offering one.
     *
     * Read from the field, then the type, then Solr's default of false, which is the same order
     * `multiValued` resolves in generally. There is no version or class dependence to consult:
     * unlike `docValues` and `uninvertible`, this property has one flat default across every schema
     * version and every field type.
     *
     * The type's attribute is compared ignoring case, because Solr reads it with
     * `Boolean.parseBoolean`; a spelling that is neither word is Solr's `false`, and here that
     * agrees with the default anyway.
     */
    private fun isMultiValued(field: SolrField, fieldType: SolrFieldType): Boolean =
        field.multiValued
            ?: fieldType.attributes["multiValued"]?.equals("true", ignoreCase = true)
            ?: false
}
