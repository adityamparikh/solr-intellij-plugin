package org.apache.solr.ide.server.indexing

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.components.JBLabel
import com.intellij.json.JsonFileType
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiFileFactory
import com.intellij.util.LocalTimeCounter
import com.intellij.ui.EditorTextField
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.BorderLayout
import javax.swing.DefaultComboBoxModel
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JPanel
import org.apache.solr.ide.SolrBundle
import org.apache.solr.ide.model.SolrConfigsetFacts

/**
 * The document about to be indexed, and what is wrong with it.
 *
 * **The validation is the reason this is a dialog rather than a menu item.** Both mistakes it
 * catches are ones Solr answers `status: 0` to — a field the schema cannot place is *added* to the
 * deployed schema, and a document with no unique key is indexed under a generated identifier. Neither
 * can be caught after sending, so the only place to say so is before.
 *
 * **Every write names its target.** The title carries the collection and the server, on the same
 * discipline the configset upload follows: a connection is chosen once in a toolbar and then
 * forgotten, and the difference between staging and production is a dropdown nobody looks at twice.
 *
 * @param project the project this belongs to
 * @param collection the collection the document will be indexed into
 * @param server the connection's display name, for saying where this is going
 * @param facts the collection's schema, against which the document is checked
 */
class SolrIndexDocumentDialog(
    project: Project,
    private val collection: String,
    private val server: String,
    private val facts: SolrConfigsetFacts,
) : DialogWrapper(project) {

    // A real editor rather than a text area, because a text area cannot offer completion — and
    // completing a field name is the one thing that stops the mistake this dialog exists to catch
    // before it is typed rather than after. The schema travels on the file: completion is called
    // with nothing but a caret, and a document written for a collection belongs to no configset any
    // rule over its location could find.
    private val documentFile: PsiFile = PsiFileFactory.getInstance(project)
        .createFileFromText(
            "solr-document.json",
            JsonFileType.INSTANCE,
            SolrSampleDocument.forSchema(facts),
            LocalTimeCounter.currentTime(),
            // Event system enabled, which is what gives the file a document to edit. Without it the
            // file parses and has no `Document` at all, and the editor built over it opens empty.
            true,
        )
        .apply { putUserData(SOLR_DOCUMENT_SCHEMA, facts) }

    private val documentArea: EditorTextField = EditorTextField(
        checkNotNull(PsiDocumentManager.getInstance(project).getDocument(documentFile)) {
            "the in-memory document file has no document"
        },
        project,
        JsonFileType.INSTANCE,
        false,
        false,
    ).apply {
        setOneLineMode(false)
        preferredSize = java.awt.Dimension(620, 260)
        addSettingsProvider { editor ->
            editor.settings.isLineNumbersShown = false
            editor.settings.isUseSoftWraps = false
        }
    }
    private val commitCombo = JComboBox(DefaultComboBoxModel(SolrCommitMode.entries.toTypedArray()))
    private val problemsLabel = JBLabel()

    init {
        title = SolrBundle.message("indexing.dialog.title", collection, server)
        commitCombo.renderer = SolrCommitModeRenderer()
        commitCombo.selectedItem = SolrCommitMode.WITHIN
        problemsLabel.border = JBUI.Borders.emptyTop(6)
        init()
        refreshProblems()
    }

    override fun createCenterPanel(): JComponent = JPanel(BorderLayout()).apply {
        add(
            JPanel(BorderLayout()).apply {
                add(JBLabel(SolrBundle.message("indexing.dialog.commit")), BorderLayout.WEST)
                add(commitCombo, BorderLayout.CENTER)
            },
            BorderLayout.NORTH,
        )
        add(documentArea, BorderLayout.CENTER)
        add(problemsLabel, BorderLayout.SOUTH)
        documentArea.document.addDocumentListener(
            object : com.intellij.openapi.editor.event.DocumentListener {
                override fun documentChanged(event: com.intellij.openapi.editor.event.DocumentEvent) {
                    refreshProblems()
                }
            },
            disposable,
        )
    }

    /**
     * Whether the document can be sent.
     *
     * **A warning does not stop a send and an error does.** Supplying `_version_` is legal and
     * almost never meant; naming a field the schema cannot place changes the deployed schema, which
     * is not something to do by pressing OK past a note.
     *
     * @return the first error, or null where there is none
     */
    override fun doValidate(): ValidationInfo? =
        problems().firstOrNull { it.severity == SolrDocumentSeverity.ERROR }
            ?.let { ValidationInfo(it.message, documentArea) }

    /** Replaces the document, as typing into it would. */
    internal fun setDocument(text: String) {
        com.intellij.openapi.application.WriteAction.run<RuntimeException> {
            documentArea.document.setText(text)
        }
    }

    /** The document as it currently stands, which is what will be sent. */
    val document: String get() = documentArea.text

    /** When the document should become findable. */
    val commitMode: SolrCommitMode get() = commitCombo.selectedItem as? SolrCommitMode ?: SolrCommitMode.WITHIN

    /**
     * What is wrong with the document as it currently stands.
     *
     * Reachable so the rules can be exercised through the dialog without showing one.
     *
     * @return the problems, most severe first
     */
    internal fun problems(): List<SolrDocumentProblem> =
        SolrDocumentValidation.problemsIn(fieldNamesIn(documentArea.text), facts)

    private fun refreshProblems() {
        val problems = problems()
        problemsLabel.text = when {
            problems.isEmpty() -> SolrBundle.message("indexing.dialog.noProblems")
            else -> problems.first().message
        }
        problemsLabel.foreground =
            if (problems.any { it.severity == SolrDocumentSeverity.ERROR }) UIUtil.getErrorForeground()
            else UIUtil.getContextHelpForeground()
    }

    /**
     * The field names a document names, read without parsing it as JSON.
     *
     * **Deliberately tolerant.** A user editing this will pass through states that are not valid
     * JSON, and a validator that went silent whenever a brace was unbalanced would go silent exactly
     * while they were typing the field name it is meant to check.
     */
    private fun fieldNamesIn(document: String): List<String> =
        KEY.findAll(document).map { it.groupValues[1] }.distinct().toList()

    private companion object {
        /** A quoted string followed by a colon, which in a Solr document is a field name. */
        val KEY = Regex("\"([^\"]+)\"\\s*:")
    }
}

/** A commit mode named by what it means rather than by its constant. */
internal class SolrCommitModeRenderer : com.intellij.ui.SimpleListCellRenderer<SolrCommitMode>() {
    override fun customize(
        list: javax.swing.JList<out SolrCommitMode>,
        value: SolrCommitMode?,
        index: Int,
        selected: Boolean,
        hasFocus: Boolean,
    ) {
        text = value?.label.orEmpty()
    }
}
