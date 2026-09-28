package org.apache.solr.ide.configset.activation

import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.ColoredListCellRenderer
import com.intellij.ui.CollectionListModel
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.ToolbarDecorator
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.BorderLayout
import javax.swing.JComponent
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.ListSelectionModel
import org.apache.solr.ide.SolrBundle
import org.apache.solr.ide.configset.reading.SolrConfigsetScanner

/**
 * One row of the configset settings page.
 *
 * @property path the configset root's absolute path
 * @property name what the user calls it — the root's directory, or its parent's where the root is the
 *   conventional `conf`
 * @property marked true where the root was marked by hand, false where detection found it
 */
data class SolrConfigsetRow(val path: String, val name: String, val marked: Boolean)

/**
 * Settings → Languages & Frameworks → Solr Configsets: the handle the activation gate's escape hatch
 * never had.
 *
 * **Before this, the hatch existed only in code.** The switch and the marked roots lived in
 * `solr.xml` and nothing but a text editor could change them, so a root a teammate committed could not
 * be removed by the person who received it. The page offers three things, in order of what they are
 * worth:
 *
 * - **What was found, and how.** Every configset the plugin recognises, each saying whether detection
 *   found it or someone marked it. A user whose configset did not light up cannot otherwise tell a
 *   detection miss from a detection that never ran, and seeing the list is how they find out.
 * - **Marked roots, added and removed here.** A detected row is not removable, because detection is
 *   a rule rather than a list and a removal would have nothing to write; switching recognition off is
 *   how to exclude one.
 * - **The switch.** It is the whole plugin's kill switch rather than a detection toggle — with it off,
 *   marked roots stop activating too — and the checkbox says so, since a label promising "detection"
 *   would read as sparing the marked ones.
 *
 * Under Languages & Frameworks rather than beside the connections page under Tools, because this
 * decides how files are read and the connections page decides nothing of the sort.
 *
 * @param project the project whose configset settings are edited
 */
class SolrConfigsetsConfigurable(private val project: Project) : Configurable {

    private val settings get() = SolrConfigsetSettings.getInstance(project)

    // The page's draft: what apply would write. Detected rows are recomputed on reset and never
    // written, since there is nothing of theirs to write.
    private var draftEnabled = true
    private val draftMarked = mutableListOf<String>()
    private val pendingDirectories = mutableMapOf<String, VirtualFile>()
    private var detected: List<SolrConfigsetRow> = emptyList()

    private val listModel = CollectionListModel<SolrConfigsetRow>()
    private val list = JBList(listModel)
    private val enabledBox = JBCheckBox(SolrBundle.message("settings.configsets.enabled"))

    /**
     * The page's name, as it appears in the Settings tree and its search.
     *
     * @return the localized page name
     */
    override fun getDisplayName(): String = SolrBundle.message("settings.configsets.displayName")

    /**
     * Builds the page: the switch, and the list with add and remove.
     *
     * @return the panel the Settings dialog shows
     */
    override fun createComponent(): JComponent {
        list.selectionMode = ListSelectionModel.SINGLE_SELECTION
        list.cellRenderer = SolrConfigsetRowRenderer(project.basePath)
        list.emptyText.text = SolrBundle.message("settings.configsets.empty")
        enabledBox.isSelected = draftEnabled
        enabledBox.addActionListener { draftEnabled = enabledBox.isSelected }

        val table = ToolbarDecorator.createDecorator(list)
            .setAddAction { chooseAndMark() }
            .setRemoveAction { list.selectedValue?.let(::unmark) }
            .setRemoveActionUpdater { list.selectedValue?.let(::canUnmark) ?: false }
            .disableUpDownActions()
            .createPanel()

        return JPanel(BorderLayout()).apply {
            add(enabledBox, BorderLayout.NORTH)
            add(table, BorderLayout.CENTER)
            add(
                // Wrapped at words rather than left as a one-line label: at the Settings dialog's
                // default width the sentence ran past its right edge.
                JBTextArea(SolrBundle.message("settings.configsets.hint")).apply {
                    isEditable = false
                    isOpaque = false
                    lineWrap = true
                    wrapStyleWord = true
                    border = JBUI.Borders.emptyTop(8)
                    font = JBUI.Fonts.smallFont()
                    foreground = UIUtil.getContextHelpForeground()
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
    override fun isModified(): Boolean =
        draftEnabled != settings.isDetectionEnabled || draftMarked.toSet() != settings.manualRoots.toSet()

    /**
     * Writes the switch and the marked roots, then asks every open file to be checked again.
     *
     * **The re-check is what makes the change visible.** Detection is read live, but an editor that
     * already highlighted a file does not ask again on its own, so without it a newly marked configset
     * would stay silent until something else edited it.
     */
    override fun apply() {
        settings.setDetectionEnabled(draftEnabled)
        val saved = settings.manualRoots.toSet()
        (saved - draftMarked.toSet()).forEach { settings.removeManualRoot(it) }
        (draftMarked.toSet() - saved).forEach { path -> pendingDirectories[path]?.let { settings.addManualRoot(it) } }
        pendingDirectories.clear()
        SolrActivationRefresh.afterActivationChanged(project)
        refreshRows()
    }

    /** Restores the page to what is saved, including what detection currently finds. */
    override fun reset() {
        draftEnabled = settings.isDetectionEnabled
        enabledBox.isSelected = draftEnabled
        draftMarked.clear()
        draftMarked += settings.manualRoots
        pendingDirectories.clear()
        refreshRows()
    }

    /** Whether the plugin recognises configsets in this project at all, as the page currently says. */
    var detectionEnabled: Boolean
        get() = draftEnabled
        set(value) {
            draftEnabled = value
            enabledBox.isSelected = value
        }

    /** The rows as the page currently shows them: marked roots first, then what detection found. */
    val rows: List<SolrConfigsetRow> get() = listModel.items.toList()

    /**
     * Marks [dir] as a configset root, pending apply.
     *
     * @param dir the directory to mark
     */
    fun mark(dir: VirtualFile) {
        if (dir.path in draftMarked) return
        draftMarked += dir.path
        pendingDirectories[dir.path] = dir
        showRows()
    }

    /**
     * Unmarks [row], pending apply; a detected row is left alone.
     *
     * @param row the row to unmark
     */
    fun unmark(row: SolrConfigsetRow) {
        if (!canUnmark(row)) return
        draftMarked.remove(row.path)
        pendingDirectories.remove(row.path)
        showRows()
    }

    /**
     * Whether [row] can be unmarked — only a marked one can.
     *
     * @param row the row asked about
     * @return true for a marked root
     */
    fun canUnmark(row: SolrConfigsetRow): Boolean = row.marked

    private fun chooseAndMark() {
        val descriptor = FileChooserDescriptorFactory.createSingleFolderDescriptor()
            .withTitle(SolrBundle.message("settings.configsets.chooseTitle"))
        FileChooser.chooseFile(descriptor, project, null)?.let(::mark)
    }

    // Detection is asked once per reset or apply, not once per edit: it walks the content roots.
    private fun refreshRows() {
        detected = SolrConfigsetScanner.getInstance(project).scan()
            .map { SolrConfigsetRow(it.root.path, it.name, marked = false) }
        showRows()
    }

    private fun showRows() {
        val marked = draftMarked.map { SolrConfigsetRow(it, nameOf(it), marked = true) }
        listModel.replaceAll(marked + detected.filter { row -> row.path !in draftMarked })
    }

    private fun nameOf(path: String): String {
        val parts = path.trimEnd('/').split('/')
        val last = parts.last()
        return if (last == "conf" && parts.size > 1) parts[parts.size - 2] else last
    }
}

/**
 * One row: the configset's name, where it is, and whether it was marked or found.
 *
 * The provenance is the point of the row. A marked root reads as a choice someone made and can undo;
 * a detected one reads as a fact about the files, which the page can only report.
 *
 * **It is written before the path, and the path relative to the project.** Written last, behind an
 * absolute path, it sat past the dialog's right edge in any checkout more than a few directories
 * deep — which is every checkout.
 *
 * @param projectDir the project's base directory, which paths inside it are shown relative to
 */
internal class SolrConfigsetRowRenderer(private val projectDir: String?) : ColoredListCellRenderer<SolrConfigsetRow>() {
    override fun customizeCellRenderer(
        list: JList<out SolrConfigsetRow>,
        value: SolrConfigsetRow?,
        index: Int,
        selected: Boolean,
        hasFocus: Boolean,
    ) {
        val row = value ?: return
        append(row.name)
        append(
            "  " + SolrBundle.message(if (row.marked) "settings.configsets.marked" else "settings.configsets.detected"),
            SimpleTextAttributes.GRAYED_ITALIC_ATTRIBUTES,
        )
        append("  ${displayed(row.path)}", SimpleTextAttributes.GRAYED_ATTRIBUTES)
    }

    private fun displayed(path: String): String {
        val base = projectDir?.trimEnd('/') ?: return path
        return if (path.startsWith("$base/")) path.removePrefix("$base/") else path
    }
}
