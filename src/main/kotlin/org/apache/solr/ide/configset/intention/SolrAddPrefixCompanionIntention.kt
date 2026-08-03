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
import org.apache.solr.ide.SolrBundle
import org.apache.solr.ide.configset.activation.SolrConfigsetDetector
import org.apache.solr.ide.configset.activation.SolrSchemaTags
import org.apache.solr.ide.configset.parsing.SolrConfigsetReader
import org.apache.solr.ide.model.SolrField
import org.apache.solr.ide.model.SolrFieldModel

/**
 * Generates the field that makes prefix searching work, from the field that cannot do it.
 *
 * The plugin already tells a reader that `description` cannot match a prefix. Before this, it left
 * them to write the remedy from memory — a three-part pattern of type, companion field and copy rule
 * that is usually copied off a blog post, and usually copied wrong in one specific way. See
 * [SolrPrefixCompanion] for what gets written and why the edge n-gram belongs on the index side only.
 *
 * **An intention rather than an inspection quick-fix.** A field without prefix support is correct
 * Solr, and underlining it would be manufacturing a problem in order to have somewhere to attach the
 * fix. The platform's idiom for "true but improvable" is an intention: nothing is highlighted and
 * nothing reaches the Problems view.
 *
 * Dumb-aware through the marker interface, which is the mechanism this extension point uses. Nothing
 * here reads an index — the whole answer comes from the configset's own files.
 */
class SolrAddPrefixCompanionIntention : IntentionAction, DumbAware {

    /**
     * The plan [isAvailable] computed, so that [getText] can name the type it chose.
     *
     * The platform calls [isAvailable] and then [getText] on the same instance while building the
     * Alt-Enter list, which is the accepted way to carry context between them. Volatile because that
     * pairing is the only ordering guaranteed, and [invoke] recomputes rather than trusting this —
     * the file may have changed between the menu opening and the user choosing.
     */
    @Volatile
    private var plan: SolrPrefixCompanionPlan? = null

    /**
     * What the Alt-Enter list shows, which names the reused type when there is one.
     *
     * Naming it is the mitigation for the one real ambiguity here: a schema declaring several
     * edge-n-gram types has one picked for it, and reading that choice before taking the fix is the
     * difference between a decision and a surprise. Falls back to [getFamilyName] only if the
     * platform asks before [isAvailable] has run, which it does not do in the Alt-Enter flow.
     */
    override fun getText(): String {
        val plan = plan ?: return familyName
        return if (plan.generatesType) {
            SolrBundle.message("intention.prefixCompanion.generate")
        } else {
            SolrBundle.message("intention.prefixCompanion.reuse", plan.typeName)
        }
    }

    /** The stable label, which groups both variants under one entry in the intention settings. */
    override fun getFamilyName(): String = SolrBundle.message("intention.prefixCompanion.family")

    /**
     * Whether the caret sits on a field this can help, which [SolrPrefixCompanion] decides.
     *
     * Only a `<field>` qualifies, not a `<dynamicField>`: a companion for a pattern would have to be
     * a pattern too, and what to call `*_txt`'s prefix companion is a naming question this does not
     * try to answer.
     */
    override fun isAvailable(project: Project, editor: Editor?, file: PsiFile?): Boolean {
        plan = null
        val model = modelFor(file) ?: return false
        val field = fieldAtCaret(editor, file, model) ?: return false
        plan = SolrPrefixCompanion.planFor(field, model)
        return plan != null
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
        val plan = SolrPrefixCompanion.planFor(field, model) ?: return
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

    private fun fieldAtCaret(editor: Editor?, file: PsiFile?, model: SolrFieldModel): SolrField? {
        val name = fieldTagAtCaret(editor, file)?.getAttributeValue(NAME) ?: return null
        return model.fields[name]?.effective
    }

    /**
     * The generated type, whose asymmetry is the reason generating it is worth more than generating
     * the field: the edge n-gram appears on the index side only.
     *
     * Put it on the query side as well and a search for `wid` is itself ground into `wi` and `wid`,
     * both of which match a large fraction of the index. Relevance collapses, and the symptom gets
     * reported as "search is broken" rather than as a schema bug.
     *
     * The gram bounds are conventional rather than derived: below two a single character matches
     * most of the index, and above fifteen the index carries prefixes nobody types.
     */
    private fun generatedType(name: String): String = """
        <fieldType name="$name" class="solr.TextField" positionIncrementGap="100">
          <analyzer type="index">
            <tokenizer class="solr.StandardTokenizerFactory"/>
            <filter class="solr.LowerCaseFilterFactory"/>
            <filter class="solr.EdgeNGramFilterFactory" minGramSize="2" maxGramSize="15"/>
          </analyzer>
          <analyzer type="query">
            <tokenizer class="solr.StandardTokenizerFactory"/>
            <filter class="solr.LowerCaseFilterFactory"/>
          </analyzer>
        </fieldType>
    """.trimIndent()

    private companion object {
        const val NAME = "name"
        const val FIELD = "field"
    }
}
