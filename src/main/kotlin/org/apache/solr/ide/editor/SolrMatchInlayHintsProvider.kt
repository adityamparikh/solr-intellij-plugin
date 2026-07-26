package org.apache.solr.ide.editor

import com.intellij.codeInsight.hints.declarative.InlayHintsCollector
import com.intellij.codeInsight.hints.declarative.InlayHintsProvider
import com.intellij.codeInsight.hints.declarative.InlayTreeSink
import com.intellij.codeInsight.hints.declarative.InlineInlayPosition
import com.intellij.codeInsight.hints.declarative.SharedBypassCollector
import com.intellij.openapi.editor.Editor
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.xml.XmlTag
import org.apache.solr.ide.configset.SolrConfigsetDetector
import org.apache.solr.ide.repository.SolrConfigsetReader

/**
 * Shows what each field matches, inline beside its declaration.
 *
 * The plugin's most surprising output, and the reason it is an inlay rather than a tooltip: a user
 * who does not already suspect their field cannot match a prefix will never hover over it to find
 * out. The hint has to be present without being asked for.
 *
 * Nothing is shown where [org.apache.solr.ide.model.SolrMatchAnalysis] is not confident. An
 * unrecognized factory means the chain was not fully understood, and a wrong claim about what a
 * field matches is worse than no claim — this is the output most likely to be quoted back.
 */
class SolrMatchInlayHintsProvider : InlayHintsProvider {

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
            if (element !is XmlTag || element.name != "field") return
            val fieldName = element.getAttributeValue("name") ?: return
            val field = model.fields[fieldName]?.effective ?: return

            val text = SolrFieldPresentation.inlayText(field, model.typeOf(field)) ?: return
            sink.addPresentation(
                position = InlineInlayPosition(element.textRange.endOffset, relatedToPrevious = true),
                hasBackground = true,
            ) {
                text(text)
            }
        }
    }
}
