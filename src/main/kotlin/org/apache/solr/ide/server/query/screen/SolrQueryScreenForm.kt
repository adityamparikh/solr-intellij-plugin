package org.apache.solr.ide.server.query.screen

import com.intellij.openapi.Disposable
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.EditorTextField
import com.intellij.ui.TextFieldWithAutoCompletion
import com.intellij.ui.TitledSeparator
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.listCellRenderer.textListCellRenderer
import com.intellij.util.ui.FormBuilder
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.event.ItemEvent
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.SwingUtilities
import org.apache.solr.ide.SolrBundle

/**
 * The query console's form, drawn from its definition and written into its tab's state.
 *
 * **Every input writes the tab's state as it changes**, and a change that alters what is visible —
 * a parser chosen, a section opened — redraws the form. A change made anywhere else, by a second
 * editor of the same tab or by a fill from the collections tree, redraws it too; its own writes do
 * not, or every keystroke would rebuild the field being typed in.
 *
 * @param project the project, for field completion
 * @param file the tab whose state this edits
 * @param parent what the form lives as long as
 */
internal class SolrQueryScreenForm(
    private val project: Project,
    private val file: SolrQueryScreenFile,
    parent: Disposable,
) {

    /** The form, for an editor to place. */
    val component: JPanel = JPanel(BorderLayout())

    private val inputs = linkedMapOf<String, JComponent>()

    // Set while inputs are being built, so filling them in does not read as the user typing.
    private var building = false

    init {
        file.addListener(parent) { source -> if (source !== this) redraw() }
        redraw()
    }

    /** The names of the inputs shown, in form order, including section toggles. */
    val shownInputs: List<String> get() = inputs.keys.toList()

    /**
     * The input for [name], where it is shown.
     *
     * @param name a parameter or a section toggle
     * @return the input, or null where it is hidden
     */
    fun input(name: String): JComponent? = inputs[name]

    /** Draws the form again from the tab's state. */
    fun redraw() {
        building = true
        try {
            inputs.clear()
            val state = file.state
            val builder = FormBuilder.createFormBuilder()
            state.spec.sections.forEach { section ->
                builder.addComponent(TitledSeparator(section.label))
                section.toggle?.let { toggle ->
                    builder.addComponent(checkbox(toggle, section.label, state.isOn(toggle), tooltip = null))
                }
                section.fields.filter { state.isVisible(it) }.forEach { add(builder, it, state) }
            }
            component.removeAll()
            component.add(builder.panel.apply { border = JBUI.Borders.empty(8) }, BorderLayout.NORTH)
            component.revalidate()
            component.repaint()
        } finally {
            building = false
        }
    }

    private fun add(builder: FormBuilder, field: SolrQueryScreenField, state: SolrQueryScreenState) {
        when (field.kind) {
            SolrQueryScreenFieldKind.CHECKBOX ->
                builder.addComponent(checkbox(field.name, field.label, state.isOn(field.name), field.tooltip))
            SolrQueryScreenFieldKind.CHOICE -> builder.addLabeledComponent(field.label, choice(field, state))
            SolrQueryScreenFieldKind.REPEATED, SolrQueryScreenFieldKind.RAW ->
                builder.addLabeledComponent(field.label, lines(field, state), true)
            SolrQueryScreenFieldKind.HANDLER, SolrQueryScreenFieldKind.TEXT ->
                builder.addLabeledComponent(field.label, text(field, state))
        }
    }

    private fun write(next: SolrQueryScreenState, changesWhatShows: Boolean) {
        if (building) return
        file.update(next, source = this)
        // Later rather than now: the input whose listener is running is one the redraw removes.
        if (changesWhatShows) SwingUtilities.invokeLater { redraw() }
    }

    private fun checkbox(name: String, label: String, on: Boolean, tooltip: String?): JComponent =
        JBCheckBox(label, on).also { box ->
            box.toolTipText = tooltip
            box.addItemListener { write(file.state.with(name, box.isSelected.toString()), changesWhatShows = true) }
            inputs[name] = box
        }

    private fun choice(field: SolrQueryScreenField, state: SolrQueryScreenState): JComponent =
        ComboBox(field.choices.toTypedArray()).also { box ->
            box.selectedItem = state.value(field.name).takeIf { it in field.choices } ?: field.choices.first()
            box.renderer = textListCellRenderer { value: String? ->
                value.orEmpty().ifEmpty { SolrBundle.message("queryScreen.choice.default") }
            }
            box.toolTipText = field.tooltip
            box.addItemListener { event ->
                if (event.stateChange == ItemEvent.SELECTED) {
                    write(file.state.with(field.name, event.item as String), changesWhatShows = true)
                }
            }
            inputs[field.name] = box
        }

    private fun lines(field: SolrQueryScreenField, state: SolrQueryScreenState): JComponent {
        val area = JBTextArea(state.lines(field.name).joinToString("\n"), LINES, 0)
        area.toolTipText = field.tooltip
        area.document.addDocumentListener(object : DocumentAdapter() {
            override fun textChanged(e: javax.swing.event.DocumentEvent) {
                write(file.state.withLines(field.name, area.text.lines()), changesWhatShows = false)
            }
        })
        inputs[field.name] = area
        return JBScrollPane(area)
    }

    private fun text(field: SolrQueryScreenField, state: SolrQueryScreenState): JComponent {
        val value = state.value(field.name)
        val input: JComponent = if (field.completesFields) {
            completingText(field, value)
        } else {
            JBTextField(value).also { box ->
                box.document.addDocumentListener(object : DocumentAdapter() {
                    override fun textChanged(e: javax.swing.event.DocumentEvent) {
                        write(file.state.with(field.name, box.text), changesWhatShows = false)
                    }
                })
            }
        }
        input.toolTipText = field.tooltip
        inputs[field.name] = input
        return input
    }

    private fun completingText(field: SolrQueryScreenField, value: String): EditorTextField {
        val completion = SolrQueryScreenFieldCompletion(project, file, field.alsoComplete)
        return TextFieldWithAutoCompletion(project, completion, false, value).also { box ->
            box.addDocumentListener(object : DocumentListener {
                override fun documentChanged(event: DocumentEvent) {
                    write(file.state.with(field.name, box.text), changesWhatShows = false)
                }
            })
        }
    }

    private companion object {
        const val LINES = 3
    }
}
