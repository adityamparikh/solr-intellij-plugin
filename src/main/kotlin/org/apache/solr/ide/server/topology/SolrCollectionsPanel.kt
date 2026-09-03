package org.apache.solr.ide.server.topology

import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.application.EDT
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.SimpleToolWindowPanel
import com.intellij.ui.ColoredTreeCellRenderer
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.treeStructure.Tree
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.BorderLayout
import java.awt.FlowLayout
import javax.swing.DefaultComboBoxModel
import javax.swing.JComboBox
import javax.swing.JPanel
import javax.swing.JTree
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel
import javax.swing.tree.TreeSelectionModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.apache.solr.ide.SolrBundle
import org.apache.solr.ide.server.connection.SolrConnection
import org.apache.solr.ide.server.connection.SolrConnectionDialog
import org.apache.solr.ide.server.connection.SolrConnectionSettings
import org.apache.solr.ide.server.reading.SolrIndexContents
import org.apache.solr.ide.server.indexing.SolrCommitMode
import org.apache.solr.ide.server.indexing.SolrDocumentIndexer
import org.apache.solr.ide.server.indexing.SolrIndexDocumentDialog
import org.apache.solr.ide.server.reading.SolrServerReader
import org.apache.solr.ide.server.transport.SolrResponse

/**
 * The coroutine scope the collections tool window fetches on.
 *
 * A service rather than a scope the panel makes for itself, so the platform owns its lifetime and
 * cancels it with the project — a panel that outlived its own fetch would deliver a topology into a
 * disposed tree.
 *
 * @property scope where server reads are launched
 */
@Service(Service.Level.PROJECT)
class SolrCollectionsScope(val scope: CoroutineScope)

/**
 * The collections tool window: what the selected server holds.
 *
 * **Server data is read on request and on connection change, and on nothing else.** No timer, no
 * refetch when a repository file is saved, no refetch when the tool window is merely redrawn. That
 * is the specification's rule and it is also the only way the view stays honest about cost — a
 * request to Solr is not free, and a tool window that quietly reissued one would make it look it.
 *
 * What the tree says lives in [SolrTopologyNodes] and what the panel is showing lives in
 * [SolrCollectionsView], both of them pure and tested. What is left here is Swing and the fetch, and
 * [render] is deliberately reachable so every state can be exercised without one.
 *
 * @param project the project whose connections this browses
 */
class SolrCollectionsPanel(private val project: Project) : SimpleToolWindowPanel(true, true), Disposable {

    private val settings get() = SolrConnectionSettings.getInstance(project)

    private val root = DefaultMutableTreeNode()

    // `asksAllowsChildren` because one row's children arrive only when it is opened. A tree left to
    // decide for itself calls any childless node a leaf, draws no handle, and so never fires the
    // expansion the fields fetch hangs off — the row would be a promise nothing could redeem. Asking
    // each node instead moves that decision to `nodeFor`, which knows which rows are still to fill.
    private val treeModel = DefaultTreeModel(root, true)
    private val tree = Tree(treeModel)
    private val banner = JBLabel()
    private val connectionCombo = JComboBox<SolrConnection>()

    // Set while the combo is being repopulated, so rebuilding it does not read as the user choosing
    // something and fire a fetch at whatever happens to land in slot zero.
    private var populating = false

    init {
        tree.isRootVisible = false
        tree.showsRootHandles = true
        tree.selectionModel.selectionMode = TreeSelectionModel.SINGLE_TREE_SELECTION
        tree.cellRenderer = SolrTopologyNodeRenderer()
        tree.addTreeWillExpandListener(
            object : javax.swing.event.TreeWillExpandListener {
                override fun treeWillExpand(event: javax.swing.event.TreeExpansionEvent) {
                    (event.path.lastPathComponent as? DefaultMutableTreeNode)?.let { fieldsRequested(it) }
                }

                override fun treeWillCollapse(event: javax.swing.event.TreeExpansionEvent) = Unit
            },
        )

        banner.isVisible = false
        banner.border = JBUI.Borders.empty(4, 8)

        connectionCombo.renderer = SolrConnectionComboRenderer()
        connectionCombo.addActionListener {
            if (!populating) selectionChanged()
        }

        toolbar = buildToolbar()
        setContent(
            JPanel(BorderLayout()).apply {
                add(banner, BorderLayout.NORTH)
                add(JBScrollPane(tree), BorderLayout.CENTER)
            },
        )

        reloadConnections()
    }

    private fun buildToolbar(): JPanel {
        val actions = DefaultActionGroup(
            object : DumbAwareAction(
                SolrBundle.message("collections.action.refresh"),
                SolrBundle.message("collections.action.refresh.description"),
                com.intellij.icons.AllIcons.Actions.Refresh,
            ) {
                override fun actionPerformed(event: AnActionEvent) = refresh()
                override fun update(event: AnActionEvent) {
                    event.presentation.isEnabled = settings.selectedConnection != null
                }
                override fun getActionUpdateThread() = ActionUpdateThread.EDT
            },
            object : DumbAwareAction(
                SolrBundle.message("indexing.action.index"),
                SolrBundle.message("indexing.action.index.description"),
                com.intellij.icons.AllIcons.Actions.Upload,
            ) {
                override fun actionPerformed(event: AnActionEvent) {
                    indexTestDocument()
                }
                override fun update(event: AnActionEvent) {
                    event.presentation.isEnabled = selectedCollection() != null &&
                        settings.selectedConnection != null
                }
                override fun getActionUpdateThread() = ActionUpdateThread.EDT
            },
            object : DumbAwareAction(
                SolrBundle.message("collections.action.add"),
                SolrBundle.message("collections.action.add.description"),
                com.intellij.icons.AllIcons.General.Add,
            ) {
                override fun actionPerformed(event: AnActionEvent) = addConnection()
                override fun getActionUpdateThread() = ActionUpdateThread.EDT
            },
        )
        val bar = ActionManager.getInstance().createActionToolbar(TOOLBAR_PLACE, actions, true)
        bar.targetComponent = this
        return JPanel(FlowLayout(FlowLayout.LEFT, 4, 2)).apply {
            add(JBLabel(SolrBundle.message("collections.connection.label")))
            add(connectionCombo)
            add(bar.component)
        }
    }

    /**
     * Rebuilds the connection list from settings and reads the server that is selected.
     *
     * Called when the panel opens and after a connection is added, which are the two moments the
     * list can have changed under it.
     */
    internal fun reloadConnections() {
        val connections = settings.connections
        populating = true
        try {
            connectionCombo.model = DefaultComboBoxModel(connections.toTypedArray())
            connectionCombo.selectedItem = settings.selectedConnection
        } finally {
            populating = false
        }
        connectionCombo.isEnabled = connections.isNotEmpty()
        refresh()
    }

    private fun selectionChanged() {
        settings.selectedConnectionId = (connectionCombo.selectedItem as? SolrConnection)?.id
        // A connection change is one of the two events that may refetch, and this is it.
        refresh()
    }

    private fun addConnection() {
        val dialog = SolrConnectionDialog(project, initial = null, hasStoredPassword = false)
        if (!dialog.showAndGet()) return
        val added = dialog.connection
        settings.addConnection(added)
        if (dialog.passwordEdited) settings.setPassword(added.id, dialog.password)
        // Chosen as well as added: a user who just described a server meant to look at it.
        settings.selectedConnectionId = added.id
        reloadConnections()
    }

    /**
     * Reads the selected server, or shows that there is none to read.
     *
     * The only thing that issues a request, so "on request and on connection change" is a property
     * of who calls this rather than a rule scattered through the panel.
     */
    internal fun refresh() {
        val connection = settings.selectedConnection ?: return render(SolrCollectionsView.NoConnection)
        render(SolrCollectionsView.Loading)
        project.service<SolrCollectionsScope>().scope.launch {
            val response = SolrServerReader.getInstance(project).topology(connection)
            withContext(Dispatchers.EDT) { render(viewFor(response)) }
        }
    }

    /**
     * Reads a collection's indexed fields, if [node] is the row that stands for them and they have
     * not been read already.
     *
     * **Expanding is the asking.** Reading an index costs one request per collection, so a server
     * holding thirty of them would turn opening this tool window into thirty requests — which is
     * slow, and a quiet breach of the rule that server data moves only when someone asks for it.
     * Fetched once and kept: re-expanding a row already filled asks nothing, because nothing
     * changed by the row being collapsed. Refresh is what asks again.
     *
     * @param node the row about to expand
     */
    internal fun fieldsRequested(node: DefaultMutableTreeNode) {
        val row = node.userObject as? SolrTopologyNode ?: return
        if (row.kind != SolrTopologyNodeKind.FIELDS || node.childCount > 0) return
        val collection = row.collection ?: return
        val connection = settings.selectedConnection ?: return

        project.service<SolrCollectionsScope>().scope.launch {
            val response = SolrServerReader.getInstance(project).indexContents(connection, collection)
            withContext(Dispatchers.EDT) { fillFields(node, response) }
        }
    }

    /**
     * Puts what came back under [node], or says why nothing did.
     *
     * A failure here is reported in the banner like any other and leaves the row empty, rather than
     * filling it with an apology that would read as a field called "could not be read".
     *
     * @param node the fields row being filled
     * @param response what the server said its index holds
     */
    internal fun fillFields(node: DefaultMutableTreeNode, response: SolrResponse<SolrIndexContents>) {
        failureMessageFor(response)?.let { return showBanner(it) }

        // The reader's own group heading is discarded and its rows adopted, because the row being
        // expanded is already that heading — nesting a second one would make the user open "Fields"
        // to find "Fields". Its detail moves up onto the row, which is where the field counts belong.
        val group = SolrTopologyNodes.fieldNodesOf(valueIn(response) ?: SolrIndexContents()).single()
        node.removeAllChildren()
        group.children.forEach { node.add(nodeFor(it)) }
        (node.userObject as? SolrTopologyNode)?.let { node.userObject = it.copy(detail = group.detail) }
        treeModel.nodeStructureChanged(node)
        warningFor(response)?.let { showBanner(it) }
    }

    private fun showBanner(message: String) {
        banner.text = message
        banner.isVisible = true
        banner.foreground = UIUtil.getErrorForeground()
    }

    /**
     * The collection or core the selected row belongs to, or null where none is selected.
     *
     * A row deeper in the tree — a shard, a replica, a field — still answers with the collection it
     * sits under, because that is the one a document would be indexed into and asking a user to
     * click the exact right row would be the plugin being unhelpful on purpose.
     */
    internal fun selectedCollection(): String? {
        var node = tree.selectionPath?.lastPathComponent as? DefaultMutableTreeNode
        while (node != null) {
            val row = node.userObject as? SolrTopologyNode
            row?.collection?.let { return it }
            if (row?.kind == SolrTopologyNodeKind.COLLECTION || row?.kind == SolrTopologyNodeKind.CORE) {
                return row.label
            }
            node = node.parent as? DefaultMutableTreeNode
        }
        return null
    }

    /**
     * Reads the collection's schema, then offers a document to index into it.
     *
     * The schema is read rather than taken from a configset, because the document is checked against
     * what the collection actually has — which is the thing that will accept or silently reshape it.
     */
    internal fun indexTestDocument() {
        val collection = selectedCollection() ?: return
        val connection = settings.selectedConnection ?: return

        project.service<SolrCollectionsScope>().scope.launch {
            val response = SolrServerReader.getInstance(project).read(connection, collection)
            withContext(Dispatchers.EDT) {
                val facts = valueIn(response)?.facts
                if (facts == null) {
                    showBanner(failureMessageFor(response).orEmpty())
                    return@withContext
                }
                val dialog = SolrIndexDocumentDialog(project, collection, connection.displayName, facts)
                if (dialog.showAndGet()) sendDocument(connection, collection, dialog.document, dialog.commitMode)
            }
        }
    }

    private fun sendDocument(
        connection: org.apache.solr.ide.server.connection.SolrConnection,
        collection: String,
        document: String,
        commit: SolrCommitMode,
    ) {
        project.service<SolrCollectionsScope>().scope.launch {
            val result = SolrDocumentIndexer.getInstance(project).index(connection, collection, document, commit)
            withContext(Dispatchers.EDT) {
                showBanner(
                    failureMessageFor(result)
                        ?: SolrBundle.message("indexing.indexed", collection, commit.label),
                )
            }
        }
    }

    /**
     * Shows [view], and nothing about how it was arrived at.
     *
     * Reachable rather than private because it is the whole of what the panel does with a result,
     * and driving it directly is how every state gets exercised without a server to produce one.
     *
     * @param view what to show
     */
    internal fun render(view: SolrCollectionsView) {
        when (view) {
            SolrCollectionsView.NoConnection -> showTree(emptyList(), SolrBundle.message("collections.empty.noConnection"), null)
            SolrCollectionsView.Loading -> showTree(emptyList(), SolrBundle.message("collections.empty.loading"), null)
            is SolrCollectionsView.Loaded ->
                showTree(view.roots, SolrBundle.message("collections.empty.nothingHere"), view.warning)
            // The tree is emptied rather than left showing a previous server's topology under a
            // failure banner, which would read as the failure being partial rather than total.
            is SolrCollectionsView.Failed -> showTree(emptyList(), SolrBundle.message("collections.empty.failed"), view.message)
        }
    }

    private fun showTree(roots: List<SolrTopologyNode>, emptyText: String, message: String?) {
        root.removeAllChildren()
        roots.forEach { root.add(nodeFor(it)) }
        treeModel.reload()
        // Expanded to the depth a user came for: collections and their shards are the question, and
        // a tree that opens fully collapsed makes them click to find out it worked.
        repeat(tree.rowCount) { tree.expandRow(it) }
        tree.emptyText.text = emptyText
        banner.text = message.orEmpty()
        banner.isVisible = message != null
        banner.foreground = if (message != null) UIUtil.getErrorForeground() else UIUtil.getLabelForeground()
    }

    // A row is openable when it already has children or is the one that fetches them on being
    // opened. Everything else says no: with the model asking rather than counting, a default of yes
    // would hang an empty handle off every field and every replica in the tree.
    private fun nodeFor(node: SolrTopologyNode): DefaultMutableTreeNode =
        DefaultMutableTreeNode(node, node.children.isNotEmpty() || node.kind == SolrTopologyNodeKind.FIELDS)
            .apply { node.children.forEach { add(nodeFor(it)) } }

    /** The rows currently on screen, top level first. */
    internal val rootLabels: List<String>
        get() = (0 until root.childCount).map {
            ((root.getChildAt(it) as DefaultMutableTreeNode).userObject as SolrTopologyNode).label
        }

    /** What the banner is saying, or null where it is hidden. */
    internal val bannerMessage: String? get() = banner.text.takeIf { banner.isVisible && it.isNotEmpty() }

    /** Selects [path], as clicking that row would. */
    internal fun selectPath(path: javax.swing.tree.TreePath) {
        tree.selectionPath = path
    }

    /** The tree's invisible root, so a test can walk to the row it means to exercise. */
    internal val treeRoot: DefaultMutableTreeNode get() = root

    /**
     * Whether the tree will offer [node] an expand handle.
     *
     * The property a lazily-filled row depends on and cannot demonstrate for itself: its children
     * arrive on expansion, so it is empty until it is opened, and a row nothing will open stays
     * empty forever.
     *
     * @param node the row to ask about
     * @return true where the tree will let it be opened
     */
    internal fun willOfferExpansion(node: DefaultMutableTreeNode): Boolean = !treeModel.isLeaf(node)

    /**
     * Whether [node] is currently open.
     *
     * Separate from [willOfferExpansion] because the two answers must differ after a render: the
     * fields row has to be openable and has to stay shut, or every redraw fetches.
     *
     * @param node the row to ask about
     * @return true where the row is expanded
     */
    internal fun isExpandedRow(node: DefaultMutableTreeNode): Boolean =
        tree.isExpanded(javax.swing.tree.TreePath(node.path))

    /** Releases the panel; the fetch scope belongs to the project and is cancelled with it. */
    override fun dispose() = Unit

    private companion object {
        const val TOOLBAR_PLACE = "SolrCollectionsToolWindow"
    }
}

/** One row of the topology tree: the server's name for a thing, and what it says about it. */
internal class SolrTopologyNodeRenderer : ColoredTreeCellRenderer() {
    override fun customizeCellRenderer(
        tree: JTree,
        value: Any?,
        selected: Boolean,
        expanded: Boolean,
        leaf: Boolean,
        row: Int,
        hasFocus: Boolean,
    ) {
        val node = (value as? DefaultMutableTreeNode)?.userObject as? SolrTopologyNode ?: return
        append(node.label)
        node.detail?.let { append("  $it", SimpleTextAttributes.GRAYED_ATTRIBUTES) }
    }
}

/** A connection in the selector, named as the user named it. */
internal class SolrConnectionComboRenderer : com.intellij.ui.SimpleListCellRenderer<SolrConnection>() {
    override fun customize(
        list: javax.swing.JList<out SolrConnection>,
        value: SolrConnection?,
        index: Int,
        selected: Boolean,
        hasFocus: Boolean,
    ) {
        text = value?.displayName ?: SolrBundle.message("collections.connection.none")
    }
}
