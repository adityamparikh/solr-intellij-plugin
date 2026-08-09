package org.apache.solr.ide.configset.intention

import com.intellij.codeInsight.intention.IntentionAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiFile
import com.intellij.psi.XmlElementFactory
import com.intellij.psi.util.parentOfType
import com.intellij.psi.xml.XmlFile
import com.intellij.psi.xml.XmlTag
import org.apache.solr.ide.configset.activation.SolrConfigsetDetector
import org.apache.solr.ide.configset.activation.SolrSchemaTags
import org.apache.solr.ide.configset.reading.SolrConfigsetReader
import org.apache.solr.ide.model.schema.SolrField
import org.apache.solr.ide.model.SolrFieldModel

/**
 * Generating a companion field that gives a schema a capability the original field lacks.
 *
 * Both companions — the `_prefix` field backed by an edge n-gram, and the `_exact` field backed by a
 * string type — are the same three-part pattern: a field type, a field using it, and a `copyField`
 * rule to populate it. What differs is which type and when to offer, and that is all a subclass
 * supplies.
 *
 * **An intention rather than an inspection quick-fix.** A field that lacks either capability is
 * correct Solr, and underlining it would be manufacturing a problem in order to have somewhere to
 * attach the fix. The platform's idiom for "true but improvable" is an intention: nothing is
 * highlighted and nothing reaches the Problems view.
 *
 * Dumb-aware through the marker interface, which is the mechanism this extension point uses. Nothing
 * here reads an index — the whole answer comes from the configset's own files.
 */
abstract class SolrAddCompanionIntention : IntentionAction, DumbAware {

    /**
     * The plan for [field], or null when this intention should not be offered.
     *
     * @param field the field the caret is on
     * @param model the configset it belongs to
     * @return the plan, or null
     */
    protected abstract fun planFor(field: SolrField, model: SolrFieldModel): SolrCompanionPlan?

    /**
     * The field type to write when the schema declares nothing reusable.
     *
     * @param typeName the name the plan chose for it
     * @return the complete `fieldType` element, as XML
     */
    protected abstract fun generatedType(typeName: String): String

    /**
     * The Alt-Enter label for [plan], which names the reused type when there is one.
     *
     * @param plan what the intention is about to do
     * @return the label
     */
    protected abstract fun textFor(plan: SolrCompanionPlan): String

    /**
     * The plan [isAvailable] computed, so that [getText] can name the type it chose.
     *
     * The platform calls [isAvailable] and then [getText] on the same instance while building the
     * Alt-Enter list, which is the accepted way to carry context between them: [getText] takes no
     * parameters, so there is no context to recompute from and no way to pass one.
     *
     * **The instance is shared, so this is genuinely global.** If a daemon pass for another file
     * runs [isAvailable] between this file's [isAvailable] and [getText], the label can name a type
     * from that other schema. The cost is confined to the label. [invoke] recomputes the plan from
     * the PSI at the caret and never reads this field, and [isAvailable] decides on a local, so
     * neither what gets written nor whether the intention appears can be affected by another file.
     *
     * The alternative is a fixed label, which costs the type name — and naming the chosen type is a
     * deliberate mitigation recorded in the design record, not an incidental nicety. A wrong word in
     * a menu is the cheaper failure.
     */
    @Volatile
    private var plan: SolrCompanionPlan? = null

    /**
     * What the Alt-Enter list shows.
     *
     * Falls back to [getFamilyName] only if the platform asks before [isAvailable] has run, which it
     * does not do in the Alt-Enter flow.
     */
    override fun getText(): String = plan?.let { textFor(it) } ?: familyName

    /**
     * Whether the caret sits on a field this can help, which the subclass's plan decides.
     *
     * Only a `<field>` qualifies, not a `<dynamicField>`: a companion for a pattern would have to be
     * a pattern too, and what to call `*_txt`'s companion is a naming question this does not try to
     * answer.
     */
    override fun isAvailable(project: Project, editor: Editor?, file: PsiFile?): Boolean {
        plan = null
        val model = modelFor(file) ?: return false
        val name = fieldTagAtCaret(editor, file)?.getAttributeValue(NAME) ?: return false
        val field = model.fields[name]?.effective ?: return false
        // Decided on a local, never on the field: the shared instance means another file's pass can
        // overwrite it at any point, and availability is the half that must not be affected.
        val computed = planFor(field, model)
        plan = computed
        return computed != null
    }

    /**
     * Writes the companion field, its copy rule, and the type when one has to be generated.
     *
     * Each new element joins its own block, because schemas are conventionally written that way and
     * a companion dropped at the end of the file reads as unrelated to the field it serves.
     */
    override fun invoke(project: Project, editor: Editor?, file: PsiFile?) {
        val model = modelFor(file) ?: return
        val fieldTag = fieldTagAtCaret(editor, file) ?: return
        val fieldName = fieldTag.getAttributeValue(NAME) ?: return
        val field = model.fields[fieldName]?.effective ?: return
        val plan = planFor(field, model) ?: return
        val schema = (file as? XmlFile)?.rootTag ?: return
        val factory = XmlElementFactory.getInstance(project)

        if (plan.generatesType) {
            val typeTag = factory.createTagFromText(generatedType(plan.typeName))
            val lastType = schema.subTags.lastOrNull { it.name in SolrSchemaTags.FIELD_TYPE }
            if (lastType != null) schema.addAfter(typeTag, lastType) else schema.addSubTag(typeTag, true)
        }

        // Immediately after the source field, so the pair reads together.
        schema.addAfter(
            factory.createTagFromText(
                """<field name="${plan.companionField}" type="${plan.typeName}" indexed="true" stored="false"/>""",
            ),
            fieldTag,
        )

        val copyRule = factory.createTagFromText(
            """<copyField source="$fieldName" dest="${plan.companionField}"/>""",
        )
        val lastCopy = schema.subTags.lastOrNull { it.name == SolrSchemaTags.COPY_FIELD }
        if (lastCopy != null) schema.addAfter(copyRule, lastCopy) else schema.addSubTag(copyRule, false)
    }

    /**
     * True: [invoke] writes PSI directly.
     *
     * The plugin edits configuration files without asking, so there is no dialog to open first and
     * nothing to do outside a write action.
     */
    override fun startInWriteAction(): Boolean = true

    private fun modelFor(file: PsiFile?): SolrFieldModel? {
        val file = file ?: return null
        if (!SolrConfigsetDetector.isConfigsetFile(file)) return null
        val configset = SolrConfigsetDetector.configsetFor(file) ?: return null
        return SolrConfigsetReader.getInstance(file.project).modelFor(configset)
    }

    private fun fieldTagAtCaret(editor: Editor?, file: PsiFile?): XmlTag? {
        val offset = editor?.caretModel?.offset ?: return null
        val tag = file?.findElementAt(offset)?.parentOfType<XmlTag>(withSelf = true) ?: return null
        return tag.takeIf { it.name == FIELD }
    }

    private companion object {
        const val NAME = "name"
        const val FIELD = "field"
    }
}
