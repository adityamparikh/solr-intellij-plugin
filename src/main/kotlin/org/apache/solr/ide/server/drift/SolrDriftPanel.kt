package org.apache.solr.ide.server.drift

import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.application.smartReadAction
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
import com.intellij.util.ui.WrapLayout
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
import org.apache.solr.ide.server.topology.valueIn

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
        // A refusal leads with a sentence that must be readable to its end; the JSON under it is
        // short-lined and wraps harmlessly.
        lineWrap = true
        wrapStyleWord = true
        emptyText.text = SolrBundle.message("drift.payload.none")
    }

    // Editable: the list is what the server said it holds, and a name it did not list — an alias, a
    // collection created a moment ago — is still worth comparing.
    private val collectionCombo = com.intellij.openapi.ui.ComboBox<String>(DefaultComboBoxModel(), COLLECTION_CHOOSER_WIDTH)
        .apply { isEditable = true }

    // The configset each offered collection was built from, by name, for the chooser's renderer.
    private val collectionSources = mutableMapOf<String, String?>()

    // Set while a popup is re-shown to fit a list that changed under it, so re-showing does not
    // count as the user opening it again and ask the server a second time.
    private var refittingPopup = false

    init {
        banner.border = JBUI.Borders.empty(4, 8)
        table.emptyText.text = SolrBundle.message("drift.empty.notCompared")
        configsetCombo.renderer = SolrConfigsetComboRenderer()
        collectionCombo.renderer = SolrCollectionComboRenderer { collectionSources[it] }

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
        watchForConfigsets()
        render(SolrDriftView.NotCompared)
    }

    /**
     * Re-reads the configset list whenever it could have changed under this view.
     *
     * **Two ways the list goes stale, and one answer covers both.** A view built while the IDE
     * indexes — a tool window restored open at startup is the ordinary case — cannot read the list
     * yet, because configsets are found through the filename index; [reloadConfigsets] waits for
     * indexing to end rather than reading an empty answer, so no separate listener for that moment is
     * needed. And a configset created while the view is open appears in no event this view hears
     * about; so opening the chooser re-reads, which is the moment a user is asking what the list
     * holds. Neither costs anything the rest of the time.
     *
     * The collection chooser is re-read the same way, on opening, from the server rather than the
     * index — see [loadCollections].
     */
    private fun watchForConfigsets() {
        configsetCombo.addPopupMenuListener(whenOpened { reloadConfigsets() })
        collectionCombo.addPopupMenuListener(whenOpened { loadCollections() })
    }

    private fun whenOpened(read: () -> Unit) = object : javax.swing.event.PopupMenuListener {
        override fun popupMenuWillBecomeVisible(event: javax.swing.event.PopupMenuEvent) {
            if (!refittingPopup) read()
        }
        override fun popupMenuWillBecomeInvisible(event: javax.swing.event.PopupMenuEvent) = Unit
        override fun popupMenuCanceled(event: javax.swing.event.PopupMenuEvent) = Unit
    }

    /**
     * Re-shows [combo]'s popup where it is open, so it is sized for the list that just arrived.
     *
     * A Swing popup measures its list once, when shown. Both lists here arrive after the popup has
     * opened — they are read in the background — so without this, a chooser opened on an empty list
     * would stay a sliver however many entries then filled it.
     */
    private fun refitPopup(combo: JComboBox<*>) {
        if (!combo.isPopupVisible) return
        refittingPopup = true
        try {
            combo.hidePopup()
            combo.showPopup()
        } finally {
            refittingPopup = false
        }
    }

    /** The most recent read of the server's collections, so a test can wait for it. */
    internal var collectionLoad: Job? = null
        private set

    /**
     * Asks the selected server what it holds, and offers that in the collection chooser.
     *
     * **Only when the chooser is opened**, which is a request, and never otherwise: server data
     * moves on request. A server that cannot be read leaves the chooser as it was — Compare is what
     * reports a failure, in the banner, naming what went wrong — and whatever was typed survives
     * either way.
     *
     * @return the read, or null where there is no connection to ask
     */
    internal fun loadCollections(): Job? {
        val connection = SolrConnectionSettings.getInstance(project).selectedConnection ?: return null
        collectionLoad?.cancel()
        val load = project.service<SolrCollectionsScope>().scope.launch {
            val topology = valueIn(SolrServerReader.getInstance(project).topology(connection)) ?: return@launch
            val choices = collectionChoicesIn(topology)
            withContext(Dispatchers.EDT) { showCollections(choices) }
        }
        collectionLoad = load
        return load
    }

    private fun showCollections(choices: List<SolrCollectionChoice>) {
        collectionSources.clear()
        choices.forEach { collectionSources[it.name] = it.configset }
        val names = choices.map { it.name }
        if (names == offeredCollections) return
        val typed = collectionText()
        collectionCombo.model = DefaultComboBoxModel(names.toTypedArray()).apply {
            // A new model selects its first entry, which in an editable chooser replaces what the
            // user typed. What they typed is kept, and nothing is chosen for them.
            selectedItem = typed.ifEmpty { null }
        }
        refitPopup(collectionCombo)
    }

    /**
     * The collection named in the chooser, typed or picked.
     *
     * Read from the editor rather than the selection: an editable combo commits typed text to its
     * selection only on Enter or focus loss, and a Compare pressed straight after typing must
     * compare what is on screen.
     */
    internal fun collectionText(): String = collectionCombo.editor.item?.toString()?.trim().orEmpty()

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
        actionToolbar = bar
        // Wrapping rather than flowing: in a border layout's north slot a plain flow layout is given one
        // row's height and never wraps, so at a tool window's default width the toolbar - Compare
        // included - was laid out past the right edge and could not be pressed.
        return JPanel(WrapLayout(FlowLayout.LEFT, 4, 2)).apply {
            add(JBLabel(SolrBundle.message("drift.configset")))
            add(configsetCombo)
            add(JBLabel(SolrBundle.message("drift.collection")))
            add(collectionCombo)
            add(bar.component)
        }.also { controlsRow = it }
    }

    /** The row holding the choosers and the toolbar, so a test can lay it out at a given width. */
    internal lateinit var controlsRow: JComponent
        private set

    /** The toolbar itself, whose place in [controlsRow] a test checks once its buttons are built. */
    internal lateinit var actionToolbar: com.intellij.openapi.actionSystem.ActionToolbar
        private set

    /**
     * The most recent re-read of the configset list, so a test can wait for it rather than sleep.
     */
    internal var configsetLoad: Job? = null
        private set

    /**
     * Rebuilds the configset list from what the project holds, keeping the chosen one where it still
     * exists.
     *
     * **Read off the EDT, in a smart read action, and shown on it.** The list comes from the filename
     * index, and every caller of this is on the EDT — a mouse press opening the chooser, the
     * constructor. In a running 2026.2 IDE a mouse press holds no read lock, so asking
     * the index there threw, and Swing abandoned the popup: the chooser did nothing when clicked. A
     * test's EDT holds the lock implicitly, which is how that shipped past a green suite. Where the
     * lock was held, the same call still tripped the platform's slow-operations check. Reading in the
     * background answers both, and a popup that opens on the previous list and then refreshes is
     * indistinguishable from one that opened late.
     *
     * **Smart, so a read made during indexing waits instead of answering empty.** A tool window
     * restored open at startup is built while the IDE indexes, and its list fills when indexing
     * ends. A newer read cancels an older one still waiting, so reopening the chooser during
     * indexing does not queue a read per click.
     *
     * **The chooser stays enabled when the list is empty.** Opening it is what re-reads the list, so
     * a disabled empty chooser could never learn about the project's first configset.
     *
     * @return the read, which a test may wait for
     */
    internal fun reloadConfigsets(): Job {
        configsetLoad?.cancel()
        val load = project.service<SolrCollectionsScope>().scope.launch {
            val configsets = smartReadAction(project) { SolrProjectConfigsets.getInstance(project).all() }
            withContext(Dispatchers.EDT) { showConfigsets(configsets) }
        }
        configsetLoad = load
        return load
    }

    private fun showConfigsets(configsets: List<SolrConfigset>) {
        // Left alone when nothing changed, so a popup already open is not rebuilt under the pointer.
        if (configsets == (0 until configsetCombo.itemCount).map { configsetCombo.getItemAt(it) }) return
        val chosen = configsetCombo.selectedItem as? SolrConfigset
        configsetCombo.model = DefaultComboBoxModel(configsets.toTypedArray()).apply {
            if (chosen in configsets) selectedItem = chosen
        }
        refitPopup(configsetCombo)
    }

    private fun canCompare(): Boolean =
        configsetCombo.selectedItem != null &&
            collectionText().isNotEmpty() &&
            SolrConnectionSettings.getInstance(project).selectedConnection != null

    /**
     * Reads the named collection and compares it against the chosen configset.
     *
     * The only thing that issues a request, so "on request and never otherwise" is a property of who
     * calls this rather than a rule spread through the panel.
     */
    internal fun compare(): Job? {
        val configset = configsetCombo.selectedItem as? SolrConfigset ?: return null
        val collection = collectionText().ifEmpty { return null }
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
        val collection = collectionText().ifEmpty { return null }
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
        val collection = collectionText().ifEmpty { return }
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
            view.drift.solrVersion.lineName,
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

    /** Types [collection] into the collection chooser, as a user would. */
    internal fun setCollection(collection: String) {
        collectionCombo.editor.item = collection
    }

    /** The collection chooser itself, so a test can open it the way a user does. */
    internal val collectionChooser: JComboBox<String> get() = collectionCombo

    /** The collections the chooser currently offers, in order. */
    internal val offeredCollections: List<String>
        get() = (0 until collectionCombo.itemCount).map { collectionCombo.getItemAt(it) }

    /** What the chooser's list shows for [name], as its renderer draws it. */
    internal fun renderedCollection(name: String): String {
        val list = javax.swing.JList<String>()
        val rendered = collectionCombo.renderer.getListCellRendererComponent(list, name, 0, false, false)
        return (rendered as? com.intellij.ui.SimpleColoredComponent)?.getCharSequence(false)?.toString().orEmpty()
    }

    /** The configset chooser itself, so a test can open it the way a user does. */
    internal val configsetChooser: JComboBox<SolrConfigset> get() = configsetCombo

    /** The names the configset chooser currently offers, in order. */
    internal val offeredConfigsets: List<String>
        get() = (0 until configsetCombo.itemCount).map { configsetCombo.getItemAt(it).name }

    /** Whether the payload pane wraps long lines. */
    internal val payloadWraps: Boolean get() = payloadArea.lineWrap && payloadArea.wrapStyleWord

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

        // Wide enough for a typical collection name; the chooser's list grows to fit its entries.
        const val COLLECTION_CHOOSER_WIDTH = 180
    }
}

/**
 * A collection in the chooser, with the configset the server says built it.
 *
 * @param sourceOf the configset a collection was built from, or null where the server did not say
 */
internal class SolrCollectionComboRenderer(
    private val sourceOf: (String) -> String?,
) : com.intellij.ui.ColoredListCellRenderer<String>() {
    override fun customizeCellRenderer(
        list: javax.swing.JList<out String>,
        value: String?,
        index: Int,
        selected: Boolean,
        hasFocus: Boolean,
    ) {
        append(value.orEmpty())
        val source = value?.let(sourceOf) ?: return
        append("  " + SolrBundle.message("drift.collection.source", source), com.intellij.ui.SimpleTextAttributes.GRAYED_ATTRIBUTES)
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
