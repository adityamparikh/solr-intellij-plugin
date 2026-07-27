package org.apache.solr.ide.configset.inspection

import com.intellij.codeInspection.ProblemHighlightType
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.psi.ElementManipulators
import com.intellij.psi.xml.XmlAttribute
import com.intellij.psi.xml.XmlAttributeValue
import org.apache.solr.ide.model.SolrFieldModel

/**
 * Shared ground rules for the configset inspections.
 *
 * **These are where the zero-false-positive requirement gets teeth.** A wrong warning on a correct
 * file is the thing that gets a plugin uninstalled, and Solr's configuration is full of syntax that
 * looks like a field name without being one. Every rule here exists because something legal would
 * otherwise be underlined in red.
 */
internal object SolrInspections {

    /**
     * Whether [name] is a field name the schema could be expected to declare.
     *
     * Excludes the names Solr answers for itself. `score` is computed per result and appears in
     * every second `fl`; `_version_` and `_root_` are managed by Solr; a name containing a wildcard
     * is a pattern rather than a reference, and a dynamic field may satisfy it in ways this cannot
     * confirm. Flagging any of them would be a warning on a correct file.
     */
    fun isCheckableFieldName(name: String): Boolean =
        name.isNotEmpty() && name !in SOLR_SUPPLIED_FIELDS && '*' !in name && !name.startsWith("[")

    /**
     * Whether [model] can resolve [name], through a declared field or a dynamic pattern.
     *
     * @param model the configset's model
     * @param name a concrete field name
     * @return true if something in the schema supplies it
     */
    fun resolves(model: SolrFieldModel, name: String): Boolean = model.resolve(name) != null

    /**
     * The value element of [attribute], or null when the attribute is absent or has no value.
     *
     * Inspections highlight the *value*, not the whole tag: underlining an entire `<copyField>` to
     * report one bad attribute makes the reader hunt for which half is wrong.
     *
     * @param attribute the attribute whose value is wanted
     * @return the value element, or null
     */
    fun valueElementOf(attribute: XmlAttribute?): XmlAttributeValue? = attribute?.valueElement

    /**
     * Reports [message] on the text *inside* an attribute's quotes.
     *
     * An attribute value element spans its quotes, so registering on the element underlines
     * `"manufacturer"` rather than `manufacturer`. The difference is small and reads as sloppiness
     * in the one place the plugin is asking to be believed.
     *
     * @param holder the collector to report to
     * @param value the attribute value element
     * @param message the user-facing message
     */
    fun reportOnValue(holder: ProblemsHolder, value: XmlAttributeValue, message: String) {
        holder.registerProblem(
            value,
            message,
            ProblemHighlightType.GENERIC_ERROR_OR_WARNING,
            ElementManipulators.getValueTextRange(value),
        )
    }

    /**
     * Names Solr supplies without the schema declaring them.
     *
     * `score` is the one that matters — it is in more `fl` parameters than not. The others are
     * Solr-managed fields that a schema may or may not spell out, and warning about them would be
     * wrong in the case where Solr adds them itself.
     */
    private val SOLR_SUPPLIED_FIELDS = setOf("score", "_version_", "_root_", "_text_", "_nest_path_")
}
