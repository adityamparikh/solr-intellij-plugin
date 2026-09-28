package org.apache.solr.ide.server.connection

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.ui.CollectionListModel
import com.intellij.ui.ColoredListCellRenderer
import com.intellij.ui.DoubleClickListener
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.TitledSeparator
import com.intellij.ui.ToolbarDecorator
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.util.concurrency.AppExecutorUtil
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.BorderLayout
import java.awt.event.MouseEvent
import java.util.UUID
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.ListSelectionModel
import org.apache.solr.ide.SolrBundle
import org.apache.solr.ide.code.SolrEndpointCandidate
import org.apache.solr.ide.code.SolrEndpointDiscovery

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

    private var discovered: List<SolrEndpointCandidate> = emptyList()
    private val discoveredModel = CollectionListModel<SolrEndpointCandidate>()
    private val discoveredList = JBList(discoveredModel)
    private var uiDisposable: Disposable? = null

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

        val connections = JPanel(BorderLayout()).apply {
            add(table, BorderLayout.CENTER)
            add(smallLabel("settings.connections.hint"), BorderLayout.SOUTH)
        }
        return JPanel(BorderLayout()).apply {
            add(connections, BorderLayout.CENTER)
            add(discoveredSection(), BorderLayout.SOUTH)
        }.also { discoverInBackground() }
    }

    /**
     * The servers the project names, beneath the connections, each one a press away from being one.
     *
     * Below rather than mixed in, because a discovered row is a suggestion and a connection is a
     * decision — one list holding both would make it impossible to tell which servers the plugin
     * actually talks to.
     */
    private fun discoveredSection(): JComponent {
        discoveredList.selectionMode = ListSelectionModel.SINGLE_SELECTION
        discoveredList.cellRenderer = SolrCandidateRenderer()
        discoveredList.emptyText.text = SolrBundle.message("settings.connections.discovered.empty")
        discoveredList.visibleRowCount = DISCOVERED_ROWS
        val addButton = JButton(SolrBundle.message("settings.connections.discovered.add")).apply {
            isEnabled = false
            addActionListener { addSelectedCandidate() }
        }
        discoveredList.addListSelectionListener { addButton.isEnabled = discoveredList.selectedValue != null }
        object : DoubleClickListener() {
            override fun onDoubleClick(event: MouseEvent): Boolean {
                addSelectedCandidate()
                return true
            }
        }.installOn(discoveredList)

        return JPanel(BorderLayout()).apply {
            border = JBUI.Borders.emptyTop(12)
            add(TitledSeparator(SolrBundle.message("settings.connections.discovered")), BorderLayout.NORTH)
            add(ScrollPaneFactory.createScrollPane(discoveredList), BorderLayout.CENTER)
            add(
                JPanel(BorderLayout()).apply {
                    add(smallLabel("settings.connections.discovered.hint"), BorderLayout.CENTER)
                    add(addButton, BorderLayout.EAST)
                },
                BorderLayout.SOUTH,
            )
        }
    }

    private fun smallLabel(key: String) = JBLabel(SolrBundle.message(key)).apply {
        border = JBUI.Borders.emptyTop(8)
        componentStyle = UIUtil.ComponentStyle.SMALL
    }

    /**
     * Looks for servers once indexing is done, off the UI thread, and lists them when it has.
     *
     * Discovery reads the word index and resolves builder classes, so it waits for a smart project;
     * the page is usable meanwhile, and the section simply fills in.
     */
    private fun discoverInBackground() {
        val disposable = Disposer.newDisposable("Solr connection discovery").also { uiDisposable = it }
        ReadAction.nonBlocking<List<SolrEndpointCandidate>> { SolrEndpointDiscovery.candidatesIn(project) }
            .inSmartMode(project)
            .expireWith(disposable)
            .finishOnUiThread(ModalityState.any()) { showDiscovered(it) }
            .submit(AppExecutorUtil.getAppExecutorService())
    }

    /** Stops any discovery still running when Settings closes. */
    override fun disposeUIResources() {
        uiDisposable?.let(Disposer::dispose)
        uiDisposable = null
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
        refreshDiscovered()
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
        refreshDiscovered()
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
        refreshDiscovered()
    }

    /** The rows as the page currently shows them. */
    internal val rows: List<SolrConnection> get() = listModel.items.toList()

    /**
     * Lists [candidates] in the discovered section, less any server the page already holds.
     *
     * @param candidates what discovery found, the active profile's first
     */
    internal fun showDiscovered(candidates: List<SolrEndpointCandidate>) {
        discovered = candidates
        refreshDiscovered()
    }

    /** The discovered rows as the page currently shows them. */
    internal val discoveredRows: List<SolrEndpointCandidate> get() = discoveredModel.items.toList()

    /**
     * The connection a discovered server's form starts with: its URL and user, named for its profile.
     *
     * @param candidate the discovered server
     * @return a new connection, not yet recorded
     */
    internal fun prefillFor(candidate: SolrEndpointCandidate) = SolrConnection(
        id = UUID.randomUUID().toString(),
        displayName = candidate.profile?.let { SolrBundle.message("settings.connections.discovered.name", it) }.orEmpty(),
        baseUrl = candidate.url,
        username = candidate.username,
    )

    /**
     * Records a discovered server as a connection, storing its found password only if asked to.
     *
     * The page holds the secret, not the form: the form's checkbox says whether, and this says what.
     *
     * @param candidate the discovered server, carrying any password found beside it
     * @param connection the connection as the form left it
     * @param storePassword whether the user ticked the box to store the found password
     */
    internal fun adopt(candidate: SolrEndpointCandidate, connection: SolrConnection, storePassword: Boolean) {
        val password = candidate.password?.takeIf { storePassword }?.toCharArray()
        record(null, connection, passwordEdited = password != null, password = password)
    }

    private fun addSelectedCandidate() {
        val candidate = discoveredList.selectedValue ?: return
        val dialog = SolrConnectionDialog(
            project,
            initial = null,
            hasStoredPassword = false,
            prefill = prefillFor(candidate),
            discoveredPasswordSource = candidate.password?.let { sourceOf(candidate) },
        )
        if (!dialog.showAndGet()) return
        // A password typed into the form is the user's answer and wins over the one that was found.
        if (dialog.passwordEdited) record(null, dialog) else adopt(candidate, dialog.connection, dialog.storeDiscoveredPassword)
    }

    private fun sourceOf(candidate: SolrEndpointCandidate) =
        candidate.profile?.let { "${candidate.origin} ($it)" } ?: candidate.origin

    /** Hides discovered servers the page now holds, matched on URL and user. */
    private fun refreshDiscovered() {
        val held = listModel.items.map { sameServer(it.baseUrl) to it.username }.toSet()
        discoveredModel.replaceAll(discovered.filter { (sameServer(it.url) to it.username) !in held })
    }

    private fun sameServer(url: String) = url.trim().trimEnd('/')

    private companion object {
        const val DISCOVERED_ROWS = 4
    }
}

/**
 * One discovered server: its URL, the profile it resolves under, and who it connects as.
 *
 * The profile leads because it is what distinguishes the rows of one application, and the active one
 * says so — it is the one the application would use if run now.
 */
internal class SolrCandidateRenderer : ColoredListCellRenderer<SolrEndpointCandidate>() {
    override fun customizeCellRenderer(
        list: JList<out SolrEndpointCandidate>,
        value: SolrEndpointCandidate?,
        index: Int,
        selected: Boolean,
        hasFocus: Boolean,
    ) {
        val candidate = value ?: return
        candidate.profile?.let { append("$it  ", SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES) }
        append(candidate.url)
        candidate.username?.let { append("  $it", SimpleTextAttributes.GRAYED_ITALIC_ATTRIBUTES) }
        append("  ${candidate.origin}", SimpleTextAttributes.GRAYED_ATTRIBUTES)
        if (candidate.active) {
            append("  " + SolrBundle.message("settings.connections.discovered.active"), SimpleTextAttributes.GRAYED_ITALIC_ATTRIBUTES)
        }
    }
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
