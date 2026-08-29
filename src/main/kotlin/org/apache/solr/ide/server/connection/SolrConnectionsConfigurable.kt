package org.apache.solr.ide.server.connection

import com.intellij.openapi.options.Configurable
import com.intellij.openapi.project.Project
import com.intellij.ui.CollectionListModel
import com.intellij.ui.ColoredListCellRenderer
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.ToolbarDecorator
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.BorderLayout
import javax.swing.JComponent
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.ListSelectionModel
import org.apache.solr.ide.SolrBundle

/**
 * The Settings page listing the Solr servers this developer can talk to.
 *
 * **The plugin's first settings page, and it is a project one rather than an application one.**
 * Connections persist to the workspace file because they are a fact about one developer's checkout —
 * a personal port-forward, a personal credential — and a page that offered them globally would be
 * offering to edit something the storage does not hold.
 *
 * Everything this class decides is delegated: what counts as a change and what gets written live in
 * [SolrConnectionsEditorModel], and what may be entered lives in [SolrConnectionValidation]. What is
 * left here is the list, its buttons, and the platform's four-method contract — which is the part
 * that cannot be exercised without a UI, and so is kept as small as it will go.
 *
 * @param project the project whose connections are edited
 */
class SolrConnectionsConfigurable(private val project: Project) : Configurable {

    private val model = SolrConnectionsEditorModel(SolrConnectionSettings.getInstance(project))
    private val listModel = CollectionListModel<SolrConnection>()
    private val list = JBList(listModel)

    /**
     * The page's name, as it appears in the Settings tree and its search.
     *
     * @return the localized page name
     */
    override fun getDisplayName(): String = SolrBundle.message("settings.connections.displayName")

    /**
     * Builds the page: the connection list and its add, edit and remove buttons.
     *
     * @return the panel the Settings dialog shows
     */
    override fun createComponent(): JComponent {
        list.selectionMode = ListSelectionModel.SINGLE_SELECTION
        list.cellRenderer = SolrConnectionRenderer()
        list.emptyText.text = SolrBundle.message("settings.connections.empty")

        val table = ToolbarDecorator.createDecorator(list)
            .setAddAction { addConnection() }
            .setEditAction { editSelected() }
            .setRemoveAction { removeSelected() }
            .disableUpDownActions()
            .createPanel()

        return JPanel(BorderLayout()).apply {
            add(table, BorderLayout.CENTER)
            add(
                JBLabel(SolrBundle.message("settings.connections.hint")).apply {
                    border = JBUI.Borders.emptyTop(8)
                    componentStyle = UIUtil.ComponentStyle.SMALL
                },
                BorderLayout.SOUTH,
            )
        }
    }

    /**
     * Whether the page has unsaved changes, which is what enables its Apply button.
     *
     * @return true where applying would change something
     */
    override fun isModified(): Boolean = model.isModified()

    /** Writes the edited connections, and any secret that was typed, to the settings. */
    override fun apply() {
        model.apply()
    }

    /**
     * Restores the page to what is saved.
     *
     * The list is repopulated as well as the model. A page that resets one and not the other shows
     * rows that no longer exist, and every later edit is then indexed against the wrong ones.
     */
    override fun reset() {
        model.reset()
        listModel.replaceAll(model.connections)
    }

    private fun addConnection() {
        val dialog = SolrConnectionDialog(project, initial = null, hasStoredPassword = false)
        if (dialog.showAndGet()) record(null, dialog)
    }

    private fun editSelected() {
        val index = list.selectedIndex.takeIf { it >= 0 } ?: return
        val existing = listModel.getElementAt(index)
        val dialog = SolrConnectionDialog(
            project,
            initial = existing,
            // Whether one is stored, never what it is. The dialog needs the fact to say so and to
            // offer to forget it; reading the secret to display it is what the storage design avoids.
            hasStoredPassword = SolrConnectionSettings.getInstance(project).getPassword(existing.id) != null,
        )
        if (dialog.showAndGet()) record(index, dialog)
    }

    private fun record(index: Int?, dialog: SolrConnectionDialog) =
        record(index, dialog.connection, dialog.passwordEdited, dialog.password)

    /**
     * Files what a completed form produced, into the row at [index] or as a new one.
     *
     * Separate from the two handlers that show a dialog, because asking the user and recording the
     * answer are different jobs and only the first of them needs a screen. It is the whole of what
     * add and edit do once the dialog is dismissed.
     *
     * @param index the row to replace, or null to append
     * @param connection the connection as entered
     * @param passwordEdited whether the secret should be rewritten at all
     * @param password the secret to store, or null to forget it; read only when [passwordEdited]
     */
    internal fun record(index: Int?, connection: SolrConnection, passwordEdited: Boolean, password: CharArray?) {
        if (index == null) {
            model.add(connection)
            listModel.add(connection)
        } else {
            model.replace(index, connection)
            listModel.setElementAt(connection, index)
        }
        if (passwordEdited) model.setPassword(connection.id, password)
    }

    private fun removeSelected() {
        removeAt(list.selectedIndex.takeIf { it >= 0 } ?: return)
    }

    /**
     * Drops the row at [index], and forgets any secret typed for it but not yet applied.
     *
     * @param index the row to remove
     */
    internal fun removeAt(index: Int) {
        model.remove(index)
        listModel.remove(index)
    }

    /** The rows as the page currently shows them. */
    internal val rows: List<SolrConnection> get() = listModel.items.toList()
}

/**
 * One row: the label the user chose, and the URL it stands for.
 *
 * Both, because a label is free text and several servers are routinely called the same thing across
 * checkouts — the URL is what actually distinguishes two rows, and hiding it would make picking
 * between them guesswork.
 */
internal class SolrConnectionRenderer : ColoredListCellRenderer<SolrConnection>() {
    override fun customizeCellRenderer(
        list: JList<out SolrConnection>,
        value: SolrConnection?,
        index: Int,
        selected: Boolean,
        hasFocus: Boolean,
    ) {
        val connection = value ?: return
        append(connection.displayName)
        if (connection.displayName != connection.baseUrl) {
            append("  ${connection.baseUrl}", SimpleTextAttributes.GRAYED_ATTRIBUTES)
        }
        connection.username?.let { append("  $it", SimpleTextAttributes.GRAYED_ITALIC_ATTRIBUTES) }
    }
}
