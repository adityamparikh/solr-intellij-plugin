package org.apache.solr.ide.configset.inspection

import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.psi.PsiElementVisitor
import com.intellij.psi.XmlElementVisitor
import com.intellij.psi.xml.XmlTag
import org.apache.solr.ide.SolrBundle
import org.apache.solr.ide.configset.parsing.SolrConfigsetReader
import org.apache.solr.ide.model.vocabulary.SolrAttributeVocabulary
import org.apache.solr.ide.model.schema.SolrValueType
import org.apache.solr.ide.model.SolrVersionSelection

/**
 * Reports an attribute value that cannot be what the attribute accepts.
 *
 * `indexed="yes"`, `positionIncrementGap="foo"` and `minGramSize="2.5"` are all rejected by Solr at
 * core-load time, and the editor accepted every one of them silently. The claim made here is
 * narrow on purpose: *that is not an integer*, never *that is not a sensible integer*. A
 * `positionIncrementGap` of `-1` parses and is left alone, because the moment this starts arguing
 * about whether a legal value is a good one it acquires opinions a configset author may
 * legitimately disagree with.
 *
 * **Silence is the default everywhere the type is not positively known.** A value holding `${...}`
 * is expanded by Solr's resource loader, possibly from a system property set outside the
 * repository, so it is never judged — that rule lives in [SolrValueType] rather than here so no
 * caller can forget it.
 */
class SolrInvalidAttributeValueInspection : LocalInspectionTool() {

    /**
     * Runs while the project is still indexing.
     *
     * The value types come from the configset's own text and a generated resource, never from an
     * index, so withholding this until indexing finishes would disable a working check during
     * exactly the minutes a reader is first opening files.
     */
    override fun isDumbAware(): Boolean = true

    /**
     * @param holder collects the problems found
     * @param isOnTheFly whether this is an editor pass rather than a batch run
     * @return a visitor over schema elements, or an empty one outside a configset
     */
    override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean): PsiElementVisitor {
        val model = SolrConfigsetReader.getInstance(holder.project).modelFor(holder.file)
            ?: return PsiElementVisitor.EMPTY_VISITOR
        val version = model.luceneMatchVersion?.let { SolrVersionSelection.fromLuceneMatchVersion(it) }
            ?: SolrVersionSelection.DEFAULT
        return object : XmlElementVisitor() {
            override fun visitXmlTag(tag: XmlTag) {
                val className = tag.getAttributeValue("class")
                for (attribute in tag.attributes) {
                    val value = attribute.valueElement ?: continue
                    val accepted = SolrAttributeVocabulary
                        .typeOf(tag.name, attribute.name, className, version)
                        ?: continue
                    if (accepted.accepts(value.value)) continue
                    SolrInspections.reportOnValue(
                        holder,
                        value,
                        SolrBundle.message(
                            "inspection.attributeValue.invalid",
                            attribute.name,
                            describe(accepted.valueType, accepted.members),
                        ),
                        SolrInspections.replacementFixes(
                            value.value,
                            accepted.members.ifEmpty { offersFor(accepted.valueType) },
                            SolrBundle.message("quickfix.attributeValue.family"),
                        ),
                    )
                }
            }
        }
    }

    /** The values worth offering as a fix, empty where there is nothing to guess. */
    private fun offersFor(type: SolrValueType): List<String> =
        if (type == SolrValueType.BOOLEAN) listOf("true", "false") else emptyList()

    /** What the attribute accepts, phrased for the message. */
    private fun describe(type: SolrValueType, members: List<String>): String = when (type) {
        SolrValueType.BOOLEAN -> SolrBundle.message("valueType.boolean")
        SolrValueType.INTEGER -> SolrBundle.message("valueType.integer")
        SolrValueType.FLOAT -> SolrBundle.message("valueType.float")
        SolrValueType.ENUM -> members.joinToString(", ")
        SolrValueType.FREE -> ""
    }
}
