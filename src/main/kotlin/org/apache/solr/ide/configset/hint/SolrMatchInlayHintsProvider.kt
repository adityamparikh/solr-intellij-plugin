package org.apache.solr.ide.configset.hint

import com.intellij.codeInsight.hints.declarative.InlayHintsCollector
import com.intellij.codeInsight.hints.declarative.InlayHintsProvider
import com.intellij.openapi.project.DumbAware
import com.intellij.codeInsight.hints.declarative.HintFormat
import com.intellij.codeInsight.hints.declarative.InlayTreeSink
import com.intellij.codeInsight.hints.declarative.InlineInlayPosition
import com.intellij.codeInsight.hints.declarative.SharedBypassCollector
import com.intellij.openapi.editor.Editor
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.xml.XmlTag
import org.apache.solr.ide.configset.activation.SolrConfigsetDetector
import org.apache.solr.ide.configset.activation.SolrSchemaTags
import org.apache.solr.ide.model.schema.SolrField
import org.apache.solr.ide.model.SolrFieldModel
import org.apache.solr.ide.model.schema.SolrFieldProperties
import org.apache.solr.ide.model.schema.SolrFieldType
import org.apache.solr.ide.model.schema.SolrMatchAnalysis
import org.apache.solr.ide.configset.parsing.SolrConfigsetReader

/**
 * Shows what each field matches, inline beside its declaration.
 *
 * The plugin's most surprising output, and the reason it is an inlay rather than a tooltip: a user
 * who does not already suspect their field cannot match a prefix will never hover over it to find
 * out. The hint has to be present without being asked for.
 *
 * Nothing is shown where the field's type is undeclared: property resolution is three-tier, and a
 * missing type removes the middle tier without removing the fall-through, so every default would be
 * attributed to Solr when the type that might have overridden it does not exist.
 *
 * Where [org.apache.solr.ide.model.schema.SolrMatchAnalysis] is not confident the match half is dropped and
 * the storage shape stands alone. An unrecognised factory means the chain was not fully understood,
 * and a wrong claim about what a field matches is worse than no claim — but it says nothing about
 * `stored` or `multiValued`, and withholding those was withholding a fact the plugin is certain of.
 */
class SolrMatchInlayHintsProvider : InlayHintsProvider, DumbAware {

    /**
     * A collector for [file], or null when the file is not part of a Solr configset.
     *
     * @param file the file being displayed
     * @param editor the editor displaying it
     * @return the collector, or null to contribute no hints to this file
     */
    override fun createCollector(file: PsiFile, editor: Editor): InlayHintsCollector? {
        if (!SolrConfigsetDetector.isConfigsetFile(file)) return null
        return Collector(file)
    }

    private class Collector(file: PsiFile) : SharedBypassCollector {

        /**
         * Resolved once per collector rather than per element.
         *
         * The collector is rebuilt when the file changes, and the reader caches on the modification
         * stamps of the files it read, so this stays on the right side of the editor-path budget.
         */
        private val model = SolrConfigsetDetector.configsetFor(file)
            ?.let { SolrConfigsetReader.getInstance(file.project).modelFor(it) }

        override fun collectFromElement(element: PsiElement, sink: InlayTreeSink) {
            val model = model ?: return
            if (element !is XmlTag || element.name !in SolrSchemaTags.FIELD) return
            val fieldName = element.getAttributeValue("name") ?: return
            // A dynamic field is keyed by its pattern and matches through it, but what it matches is
            // decided by its type in exactly the same way, so it earns the same hint.
            val field = model.fields[fieldName]?.effective
                ?: model.dynamicFields[fieldName]?.effective?.field
                ?: return

            val parts = hintFor(field, model.typeOf(field), model)?.takeIf { it.isNotEmpty() } ?: return
            sink.addPresentation(
                position = InlineInlayPosition(element.textRange.endOffset, relatedToPrevious = true),
                payloads = null,
                tooltip = null,
                hintFormat = HintFormat.default,
            ) {
                // One segment per part: the renderer truncates any single segment past 30
                // characters, and the full summary is over that budget on a tokenised chain.
                parts.forEachIndexed { index, part ->
                    text(if (index < parts.lastIndex) "$part, " else part)
                }
            }
        }

        /**
         * The hint for a field as its summary parts, or null when nothing should be said.
         *
         * Null only where the field type is undeclared. Property resolution is three-tier — field,
         * then field type, then Solr's default — and an undeclared type removes the middle tier
         * without removing the fall-through, so every default would be attributed to Solr when the
         * type that might have overridden it simply does not exist. That is an inspection's finding,
         * not a hint's.
         *
         * An unrecognised factory is a different case and no longer silences anything but the match
         * half: property values are read from attributes and from version and class defaults, and
         * never depended on the analyser chain.
         */
        private fun hintFor(field: SolrField, fieldType: SolrFieldType?, model: SolrFieldModel): List<String>? {
            if (fieldType == null) return null
            val capability = SolrMatchAnalysis.of(fieldType)
            val match = if (capability.confident) capability.summaryParts else emptyList()
            return match + storageShape(field, fieldType, model)
        }

        /**
         * The four storage-shape phrases, in the order the Reference Guide lists the properties.
         *
         * A property with no answer contributes nothing rather than a guess — the catalog not
         * carrying a type's class is exactly where asserting a `docValues` default would be
         * inventing one. That silence is per property, because that is the granularity the
         * underlying facts have.
         */
        private fun storageShape(field: SolrField, fieldType: SolrFieldType, model: SolrFieldModel): List<String> {
            val traits = model.traitsOf(fieldType)
            return SolrFieldProperties.FOR_FIELD.mapNotNull { property ->
                val meaning = property.meaning ?: return@mapNotNull null
                if (meaning.inlineWhenTrue == null) return@mapNotNull null
                when (SolrFieldProperties.resolve(property, field, fieldType, model.schemaVersion, traits).value) {
                    "true" -> meaning.inlineWhenTrue
                    "false" -> meaning.inlineWhenFalse
                    else -> null
                }
            }
        }

    }
}
