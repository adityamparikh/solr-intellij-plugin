package org.apache.solr.ide.configset.schema.intention

import com.intellij.codeInsight.intention.IntentionAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiFile
import com.intellij.psi.util.parentOfType
import com.intellij.psi.xml.XmlAttribute
import org.apache.solr.ide.configset.schema.annotator.SolrRestatedAttribute

/**
 * Removing an attribute whose value the field would have had anyway.
 *
 * **The companion to [the dim][org.apache.solr.ide.configset.schema.annotator.SolrRestatedDefaultAnnotator],
 * and offered rather than applied.** Restating a default is correct Solr and sometimes deliberate —
 * an author may write `indexed="true"` to record that they considered it — so nothing here decides
 * the attribute is unwanted. It reports that removing it changes nothing and leaves the choice
 * alone, which is what an intention means and a quick-fix would not.
 *
 * Both surfaces read one predicate, so the offer cannot disagree with the dim.
 *
 * Dumb-aware through the marker interface, as the companion intentions are. The whole answer comes
 * from the configset's own files.
 */
class SolrRemoveRestatedAttributeIntention : IntentionAction, DumbAware {

    /**
     * The attribute [isAvailable] found, so that [getText] can name it.
     *
     * The platform calls [isAvailable] then [getText] on the same shared instance, which is the
     * accepted way to carry context between them — [getText] takes no parameters. As with the
     * companion intentions the cost of the sharing is confined to the label: [invoke] re-reads the
     * caret and never consults this field, so a daemon pass for another file can at worst put the
     * wrong attribute name in a menu item, never remove the wrong attribute.
     */
    @Volatile
    private var attributeName: String? = null

    /** @return the family name, which groups this in the intentions settings tree */
    override fun getFamilyName(): String = "Remove restated default"

    /**
     * What the Alt-Enter list shows, naming the attribute so the reader knows what will go.
     *
     * @return the label
     */
    override fun getText(): String = attributeName?.let { "$familyName: $it" } ?: familyName

    /**
     * Whether the caret sits on an attribute that restates what the field resolves to without it.
     *
     * @param project the open project
     * @param editor the editor the caret is in
     * @param file the file being edited
     * @return true when the attribute can be removed without changing the field
     */
    override fun isAvailable(project: Project, editor: Editor?, file: PsiFile?): Boolean {
        attributeName = null
        val attribute = attributeAtCaret(editor, file) ?: return false
        // Decided on a local, never on the field the label reads: the instance is shared, so
        // availability must not depend on anything another file's pass could have overwritten.
        val restated = SolrRestatedAttribute.isRestated(attribute)
        if (restated) attributeName = attribute.name
        return restated
    }

    /**
     * Deletes the attribute, leaving a file whose parsed model is identical.
     *
     * @param project the open project
     * @param editor the editor the caret is in
     * @param file the file being edited
     */
    override fun invoke(project: Project, editor: Editor?, file: PsiFile?) {
        val attribute = attributeAtCaret(editor, file) ?: return
        if (!SolrRestatedAttribute.isRestated(attribute)) return
        attribute.delete()
    }

    /** @return true; removing an attribute is an ordinary PSI write */
    override fun startInWriteAction(): Boolean = true

    private fun attributeAtCaret(editor: Editor?, file: PsiFile?): XmlAttribute? {
        val offset = editor?.caretModel?.offset ?: return null
        return file?.findElementAt(offset)?.parentOfType<XmlAttribute>(withSelf = true)
    }
}
