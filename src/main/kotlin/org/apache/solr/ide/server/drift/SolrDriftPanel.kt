package org.apache.solr.ide.server.drift

import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.application.EDT
import com.intellij.openapi.components.service
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.MessageDialogBuilder
import com.intellij.openapi.ui.SimpleToolWindowPanel
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.BorderLayout
import java.awt.FlowLayout
import javax.swing.DefaultComboBoxModel
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.table.DefaultTableModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.apache.solr.ide.SolrBundle
import org.apache.solr.ide.configset.activation.SolrConfigset
import org.apache.solr.ide.configset.activation.SolrProjectConfigsets
import org.apache.solr.ide.configset.reading.SolrConfigsetReader
import org.apache.solr.ide.model.SolrAgreement
import org.apache.solr.ide.model.SolrConfigsetFacts
import org.apache.solr.ide.server.connection.SolrConnection
import org.apache.solr.ide.server.connection.SolrConnectionSettings
import org.apache.solr.ide.server.reading.SolrServerReader
import org.apache.solr.ide.server.topology.SolrCollectionsScope
import org.apache.solr.ide.server.topology.failureMessageFor

/**
 * The drift view: what a configset and a collection do not agree about.
 *
 * **Which configset and which collection are named by a human, every time.** A configset directory
 * on disk says nothing about which collection on which server was created from it — the same name
 * may exist on three servers and mean three different things — so the plugin asks rather than
 * inferring, and the answer is shown beside the result so the comparison can be attributed.
 *
 * What is compared lives in [SolrDrift] and what state the view is in lives in [SolrDriftView], both
 * pure and tested. [render] is reachable so every state can be exercised without a server.
 *
 * @param project the project whose configsets and connections this compares
 */
class SolrDriftPanel(private val project: Project) : SimpleToolWindowPanel(true, true), Disposable {

    private val columns = arrayOf(
        SolrBundle.message("drift.column.state"),
        SolrBundle.message("drift.column.kind"),
        SolrBundle.message("drift.column.name"),
        SolrBundle.message("drift.column.repository"),
        SolrBundle.message("drift.column.server"),
    )

    private val tableModel = object : DefaultTableModel(columns, 0) {
        override fun isCellEditable(row: Int, column: Int) = false
    }
    private val table = JBTable(tableModel)
    private val banner = JBLabel()
    private val configsetCombo = JComboBox<SolrConfigset>()

    // The rows behind the table, so a selected row can be asked what request it would send. The
    // table model holds rendered text; the entries hold the change.
    private val shownEntries = mutableListOf<SolrDriftEntry>()
    private val payloadArea = com.intellij.ui.components.JBTextArea().apply {
        isEditable = false
        lineWrap = false
        emptyText.text = SolrBundle.message("drift.payload.none")
    }
    private val collectionField = com.intellij.ui.components.JBTextField(20)

    init {
        banner.border = JBUI.Borders.empty(4, 8)
        table.emptyText.text = SolrBundle.message("drift.empty.notCompared")
        configsetCombo.renderer = SolrConfigsetComboRenderer()

        toolbar = buildToolbar()
        // Selecting a row shows the request that would close it. Reading what a tool would do
        // before deciding is the whole reason a refused row still carries a payload.
        table.selectionModel.addListSelectionListener { if (!it.valueIsAdjusting) showPayloadForSelection() }

        setContent(
            JPanel(BorderLayout()).apply {
                add(banner, BorderLayout.NORTH)
                add(
                    com.intellij.ui.JBSplitter(true, 0.6f).apply {
                        firstComponent = JBScrollPane(table)
                        secondComponent = JBScrollPane(payloadArea)
                    },
                    BorderLayout.CENTER,
                )
            },
        )
        reloadConfigsets()
        render(SolrDriftView.NotCompared)
    }

    /**
     * The toolbar's actions, so a test can ask each one whether it would be enabled.
     *
     * Enablement is the only thing telling a user why a button does nothing — every guard inside
     * these actions returns quietly — so it is worth asserting through the action rather than
     * through the predicate it happens to call.
     */
    internal lateinit var toolbarActions: DefaultActionGroup
        private set

    private fun buildToolbar(): JComponent {
        val actions = DefaultActionGroup(
            object : DumbAwareAction(
                SolrBundle.message("drift.action.compare"),
                SolrBundle.message("drift.action.compare.description"),
                com.intellij.icons.AllIcons.Actions.Diff,
            ) {
                override fun actionPerformed(event: AnActionEvent) {
                    compare()
                }
                override fun update(event: AnActionEvent) {
                    event.presentation.isEnabled = canCompare()
                }
                override fun getActionUpdateThread() = ActionUpdateThread.EDT
            },
            object : DumbAwareAction(
                SolrBundle.message("drift.action.upload"),
                SolrBundle.message("drift.action.upload.description"),
                com.intellij.icons.AllIcons.Actions.Upload,
            ) {
                override fun actionPerformed(event: AnActionEvent) {
                    uploadAndReload()
                }
                override fun update(event: AnActionEvent) {
                    event.presentation.isEnabled = canCompare()
                }
                override fun getActionUpdateThread() = ActionUpdateThread.EDT
            },
            object : DumbAwareAction(
                SolrBundle.message("drift.action.apply"),
                SolrBundle.message("drift.action.apply.description"),
                com.intellij.icons.AllIcons.Actions.Commit,
            ) {
                override fun actionPerformed(event: AnActionEvent) = applyAdditive()
                override fun update(event: AnActionEvent) {
                    // Enabled only where there is something this plugin will actually send. A
                    // comparison of nothing but type changes offers no button, which is the honest
                    // reading of "only additive changes get the second action".
                    event.presentation.isEnabled = canCompare() && applicableChanges().isNotEmpty()
                }
                override fun getActionUpdateThread() = ActionUpdateThread.EDT
            },
        )
        toolbarActions = actions
        val bar = ActionManager.getInstance().createActionToolbar(TOOLBAR_PLACE, actions, true)
        bar.targetComponent = this
        return JPanel(FlowLayout(FlowLayout.LEFT, 4, 2)).apply {
            add(JBLabel(SolrBundle.message("drift.configset")))
            add(configsetCombo)
            add(JBLabel(SolrBundle.message("drift.collection")))
            add(collectionField)
            add(bar.component)
        }
    }

    /** Rebuilds the configset list from what the project holds. */
    internal fun reloadConfigsets() {
        val configsets = SolrProjectConfigsets.getInstance(project).all()
        configsetCombo.model = DefaultComboBoxModel(configsets.toTypedArray())
        configsetCombo.isEnabled = configsets.isNotEmpty()
    }

    private fun canCompare(): Boolean =
        configsetCombo.selectedItem != null &&
            collectionField.text.isNotBlank() &&
            SolrConnectionSettings.getInstance(project).selectedConnection != null

    /**
     * Reads the named collection and compares it against the chosen configset.
     *
     * The only thing that issues a request, so "on request and never otherwise" is a property of who
     * calls this rather than a rule spread through the panel.
     */
    internal fun compare(): Job? {
        val configset = configsetCombo.selectedItem as? SolrConfigset ?: return null
        val collection = collectionField.text.trim().ifEmpty { return null }
        val connection = SolrConnectionSettings.getInstance(project).selectedConnection ?: return null

        val repository = SolrConfigsetReader.getInstance(project).factsFor(configset)
        render(SolrDriftView.Comparing)
        // The job is returned so a caller that needs to know when the read finished can wait for it.
        // Nothing in the UI does; a test does, and without it the only alternative is sleeping and
        // hoping, which is how a suite acquires failures that depend on the machine it runs on.
        return project.service<SolrCollectionsScope>().scope.launch {
            val response = SolrServerReader.getInstance(project).read(connection, collection)
            withContext(Dispatchers.EDT) {
                render(driftViewFor(configset.name, collection, repository, response))
            }
        }
    }

    /**
     * Writes the chosen configset to the server, then compares again.
     *
     * **The comparison is redone by reading, never by assuming the write worked.** A configset
     * upload returning `status: 0` is not proof the server reflects it — verified: uploading a
     * configset lacking `_version_` returns 0 and appears in `action=LIST`, and Solr then refuses
     * to build a collection from it. Clearing the diff on the write's own answer would report a
     * deployment that had not happened.
     *
     * A reload follows the upload because a collection already running keeps the configset it
     * started with until told otherwise, so an upload alone changes nothing the drift view can see.
     */
    internal fun uploadAndReload(): Job? {
        val configset = configsetCombo.selectedItem as? SolrConfigset ?: return null
        val collection = collectionField.text.trim().ifEmpty { return null }
        val connection = SolrConnectionSettings.getInstance(project).selectedConnection ?: return null
        if (!confirmed(configset.name, collection, connection.displayName)) return null

        val archive = SolrConfigsetArchive.of(configset.root)
        val repository = SolrConfigsetReader.getInstance(project).factsFor(configset)
        render(SolrDriftView.Writing)
        return project.service<SolrCollectionsScope>().scope.launch {
            val view = writeThenCompare(connection, configset.name, collection, archive, repository)
            withContext(Dispatchers.EDT) { render(view) }
        }
    }

    /**
     * Uploads, reloads, then reads the collection back and compares.
     *
     * **Reachable, because this is the sequence worth testing and none of it needs a screen.** The
     * rule it exists to obey — that a successful write is not proof the server agrees — is
     * observable only by running the whole chain and seeing drift survive it, which a test can do
     * against a fake server and a user cannot check by looking.
     *
     * @param connection the server to write to
     * @param configsetName the configset's name, used both as the upload's name and to attribute
     *   the comparison
     * @param collection the collection to reload and then compare against
     * @param archive the zipped configset
     * @param repository the facts the configset declares
     * @return what to show: the failure that stopped the write, or the comparison that followed it
     */
    internal suspend fun writeThenCompare(
        connection: SolrConnection,
        configsetName: String,
        collection: String,
        archive: ByteArray,
        repository: SolrConfigsetFacts,
    ): SolrDriftView {
        val writer = SolrConfigsetWriter.getInstance(project)
        val written = writer.upload(connection, configsetName, archive)
            .andThen { writer.reload(connection, collection) }
        failureMessageFor(written)?.let { return SolrDriftView.Failed(it) }

        // Read back rather than assume. A configset lacking `_version_` uploads with status 0 and
        // appears in `action=LIST`, and Solr then refuses to build a collection from it — so the
        // only honest report of what the server holds now is one that asked.
        return driftViewFor(
            configsetName,
            collection,
            repository,
            SolrServerReader.getInstance(project).read(connection, collection),
        )
    }

    /** The changes the current comparison offers to send. */
    internal fun applicableChanges(): List<SolrSchemaApiChange> =
        shownEntries.mapNotNull { it.change }.filter { it.applicable }

    /**
     * Sends the additive changes the comparison found, then compares again.
     *
     * **Only what the rows say is applicable is sent**, and the request is the one shown. A
     * `replace-field` is never in it however the comparison arrived — Solr would accept it and
     * report success while every indexed document kept its old encoding.
     *
     * The comparison that follows comes from a fresh read, for the reason the upload path already
     * obeys: an accepted request is not proof the server agrees.
     */
    internal fun applyAdditive() {
        val configset = configsetCombo.selectedItem as? SolrConfigset ?: return
        val collection = collectionField.text.trim().ifEmpty { return }
        val connection = SolrConnectionSettings.getInstance(project).selectedConnection ?: return
        val request = SolrSchemaApi.requestFor(applicableChanges()) ?: return
        if (!confirmedApply(applicableChanges().size, collection, connection.displayName)) return

        val repository = SolrConfigsetReader.getInstance(project).factsFor(configset)
        render(SolrDriftView.Writing)
        project.service<SolrCollectionsScope>().scope.launch {
            val view = applyThenCompare(connection, configset.name, collection, request, repository)
            withContext(Dispatchers.EDT) { render(view) }
        }
    }

    /**
     * Posts [request] to the collection's Schema API, then reads the collection back and compares.
     *
     * Reachable for the same reason `writeThenCompare` is: the sequence is what is worth testing and
     * none of it needs a screen.
     *
     * @param connection the server to write to
     * @param configsetName the configset being compared, for attributing the result
     * @param collection the collection whose schema to change
     * @param request the Schema API request body
     * @param repository the facts the configset declares
     * @return the failure that stopped the write, or the comparison that followed it
     */
    internal suspend fun applyThenCompare(
        connection: SolrConnection,
        configsetName: String,
        collection: String,
        request: String,
        repository: SolrConfigsetFacts,
    ): SolrDriftView {
        val written = SolrConfigsetWriter.getInstance(project).applySchemaChanges(connection, collection, request)
        failureMessageFor(written)?.let { return SolrDriftView.Failed(it) }
        return driftViewFor(
            configsetName,
            collection,
            repository,
            SolrServerReader.getInstance(project).read(connection, collection),
        )
    }

    private fun confirmedApply(count: Int, collection: String, server: String): Boolean =
        MessageDialogBuilder.yesNo(
            SolrBundle.message("drift.confirmApply.title"),
            SolrBundle.message("drift.confirmApply.message", count, collection, server),
        ).ask(project)

    /** Shows the request the selected row would send, and why it is not offered where it is not. */
    private fun showPayloadForSelection() {
        val entry = shownEntries.getOrNull(table.selectedRow)
        val change = entry?.change
        payloadArea.text = when {
            change == null -> ""
            change.applicable -> change.payload
            // The reason comes first: a reader who sees the JSON before the warning may act on it.
            else -> SolrBundle.message("drift.payload.declined", change.declined.orEmpty()) + "\n\n" + change.payload
        }
        payloadArea.caretPosition = 0
    }

    /**
     * Asks before writing, naming what will be written and where.
     *
     * **Every write names its target server**, because a connection is chosen once in a toolbar and
     * then forgotten, and the difference between staging and production is a dropdown nobody looks
     * at twice.
     */
    private fun confirmed(configset: String, collection: String, server: String): Boolean =
        MessageDialogBuilder.yesNo(
            SolrBundle.message("drift.confirm.title"),
            SolrBundle.message("drift.confirm.message", configset, collection, server),
        ).ask(project)

    /**
     * Shows [view], and nothing about how it was arrived at.
     *
     * @param view what to show
     */
    internal fun render(view: SolrDriftView) {
        tableModel.rowCount = 0
        shownEntries.clear()
        payloadArea.text = ""
        when (view) {
            SolrDriftView.NotCompared -> show(SolrBundle.message("drift.empty.notCompared"), null)
            SolrDriftView.Comparing -> show(SolrBundle.message("drift.empty.comparing"), null)
            SolrDriftView.Writing -> show(SolrBundle.message("drift.empty.writing"), null)
            // The table is left empty rather than showing a previous comparison under a failure
            // banner, which would read as the failure being partial when nothing was compared.
            is SolrDriftView.Failed -> show(SolrBundle.message("drift.empty.failed"), view.message, failed = true)
            is SolrDriftView.Compared -> {
                shownEntries += view.drift.entries
                view.drift.entries.forEach { entry ->
                    tableModel.addRow(
                        arrayOf(
                            labelFor(entry.agreement),
                            entry.kind.name.lowercase().replace('_', ' '),
                            entry.name,
                            entry.repository.orEmpty(),
                            entry.server.orEmpty(),
                        ),
                    )
                }
                show(SolrBundle.message("drift.empty.clean"), summaryOf(view), failed = false)
            }
        }
    }

    /**
     * The line above the table.
     *
     * **It names both sides and says how much agreed**, because an empty table is otherwise
     * ambiguous between "these two agree" and "nothing was compared" — and the count is the only
     * thing on screen that proves the comparison actually ran.
     */
    private fun summaryOf(view: SolrDriftView.Compared): String {
        val counts = view.drift.countsByAgreement
        val summary = if (view.drift.isClean) {
            SolrBundle.message("drift.summary.clean", view.configset, view.collection, view.drift.agreeingCount)
        } else {
            SolrBundle.message(
                "drift.summary.drifted",
                view.configset,
                view.collection,
                counts[SolrAgreement.REPOSITORY_ONLY] ?: 0,
                counts[SolrAgreement.SERVER_ONLY] ?: 0,
                counts[SolrAgreement.DISAGREEING] ?: 0,
                view.drift.agreeingCount,
            )
        }
        // Which Solr the collection runs is material to reading the comparison — a field type that
        // exists on one line and not the other is a difference the versions explain.
        val line = SolrBundle.message(
            "drift.summary.solrLine",
            view.drift.solrVersion.guidePathSegment,
            view.drift.solrVersion.describeSource(),
        )
        return listOfNotNull(summary, line, view.warning).joinToString("  ")
    }

    private fun show(emptyText: String, message: String?, failed: Boolean = false) {
        table.emptyText.text = emptyText
        banner.text = message.orEmpty()
        banner.isVisible = message != null
        banner.foreground = if (failed) UIUtil.getErrorForeground() else UIUtil.getLabelForeground()
    }

    private fun labelFor(agreement: SolrAgreement): String = SolrBundle.message(
        when (agreement) {
            SolrAgreement.REPOSITORY_ONLY -> "drift.state.repositoryOnly"
            SolrAgreement.SERVER_ONLY -> "drift.state.serverOnly"
            SolrAgreement.DISAGREEING -> "drift.state.disagreeing"
            // Never rendered: agreeing declarations are dropped before they reach a row. Present
            // because the compiler asks, and answered honestly rather than with a placeholder.
            SolrAgreement.AGREEING -> "drift.state.agreeing"
        },
    )

    /** The rows currently on screen, as the state column reads them. */
    internal val rowStates: List<String>
        get() = (0 until tableModel.rowCount).map { tableModel.getValueAt(it, 0) as String }

    /** The declaration names currently on screen. */
    internal val rowNames: List<String>
        get() = (0 until tableModel.rowCount).map { tableModel.getValueAt(it, 2) as String }

    /** Whether a cell may be typed into; it may not, and a test says so. */
    internal fun isCellEditable(row: Int, column: Int): Boolean = tableModel.isCellEditable(row, column)

    /** Whether the toolbar's actions have everything they need — a connection, a configset, a collection. */
    internal fun canAct(): Boolean = canCompare()

    /** Sets the collection field, so a test can choose one without typing. */
    internal fun setCollection(collection: String) {
        collectionField.text = collection
    }

    /** What the payload pane is showing for the selected row. */
    internal val payloadText: String get() = payloadArea.text

    /** Selects the row at [index], as clicking it would. */
    internal fun selectRow(index: Int) {
        table.selectionModel.setSelectionInterval(index, index)
    }

    /** What the banner is saying, or null where it is hidden. */
    internal val bannerMessage: String? get() = banner.text.takeIf { banner.isVisible && it.isNotEmpty() }

    /** Releases the panel; the scope it reads on belongs to the project. */
    override fun dispose() = Unit

    private companion object {
        const val TOOLBAR_PLACE = "SolrDriftToolWindow"
    }
}

/** A configset in the chooser, named as the user thinks of it. */
internal class SolrConfigsetComboRenderer : com.intellij.ui.SimpleListCellRenderer<SolrConfigset>() {
    override fun customize(
        list: javax.swing.JList<out SolrConfigset>,
        value: SolrConfigset?,
        index: Int,
        selected: Boolean,
        hasFocus: Boolean,
    ) {
        text = value?.name ?: SolrBundle.message("drift.configset.none")
    }
}
