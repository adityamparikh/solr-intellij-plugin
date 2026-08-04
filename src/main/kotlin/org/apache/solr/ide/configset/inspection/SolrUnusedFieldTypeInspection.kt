package org.apache.solr.ide.configset.inspection

import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.codeInspection.ProblemHighlightType
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.psi.PsiElementVisitor
import com.intellij.psi.PsiFile
import com.intellij.psi.XmlElementVisitor
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.xml.XmlTag
import org.apache.solr.ide.SolrBundle
import org.apache.solr.ide.configset.activation.SolrConfigsetFileKind
import org.apache.solr.ide.configset.activation.SolrSchemaTags
import org.apache.solr.ide.configset.parsing.SolrConfigsetReader
import org.apache.solr.ide.model.SolrFieldModel

/**
 * Reports a `fieldType` no field, dynamic field or other type in the configset names.
 *
 * The odd one out among these inspections: nothing here is *wrong*. Solr loads a schema full of
 * types nobody uses without a murmur, and the configset still behaves exactly as its author
 * intended. What an unused type costs is paid by the next reader, who has to work out which of the
 * forty declarations in front of them the index actually depends on — and by the author of the next
 * change, who edits an analyzer chain that has not affected a document in two years.
 *
 * **It is therefore greyed out rather than underlined.** [ProblemHighlightType.LIKE_UNUSED_SYMBOL]
 * is the platform's own vocabulary for a declaration nothing reads, and it is the honest
 * presentation here: a squiggle claims a defect, and the plugin would then be crying wolf on
 * Solr's own `_default` configset, which ships a deliberate palette of types for fields the user
 * has not written yet. The finding is still true and still worth surfacing — dead configuration is
 * how a schema becomes unreadable — but it is a fact about the file, not an accusation.
 *
 * **No quick-fix, for the same reason.** The repair is to delete the declaration, and whether that
 * is right depends on something the editor cannot see: whether the type is a leftover or a
 * provision. Offering deletion behind Alt-Enter would put the plugin's weight behind an answer it
 * does not have.
 *
 * Three things count as a use, and the third is the one that is easy to miss:
 *
 *  - a `field` naming the type;
 *  - a `dynamicField` naming it, whether or not any document ever matches the pattern — the
 *    declaration is the use, and a pattern with no documents behind it is not something a static
 *    reading of the configset can know;
 *  - another *field type* naming it through `subFieldType`, which is how `PointType` and
 *    `LatLonType` delegate each dimension to a real numeric type. Without this the stock spatial
 *    types would be reported as unused on a schema that uses them exactly as Solr documents.
 */
class SolrUnusedFieldTypeInspection : LocalInspectionTool() {

    /**
     * Runs while the project is still indexing.
     *
     * Nothing here consults an index — the model is parsed from the configset's own text — so the
     * platform's default of skipping this until indexing finishes would withhold a working feature
     * for no reason, exactly when a reader is most likely to be opening files for the first time.
     */
    override fun isDumbAware(): Boolean = true

    /**
     * @param holder collects the problems found
     * @param isOnTheFly whether this is an editor pass rather than a batch run
     * @return a visitor over type declarations, or an empty one where the question cannot be
     *   answered honestly
     */
    override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean): PsiElementVisitor {
        // Only the schema declares types. The kind is asked rather than the name compared, because
        // Solr has used three names for this file and two of them are still in the wild.
        if (SolrConfigsetFileKind.forFileName(holder.file.name)?.isSchema != true) {
            return PsiElementVisitor.EMPTY_VISITOR
        }
        val model = SolrConfigsetReader.getInstance(holder.project).modelFor(holder.file)
            ?: return PsiElementVisitor.EMPTY_VISITOR
        if (pullsDeclarationsInFromElsewhere(holder.file)) return PsiElementVisitor.EMPTY_VISITOR

        val used = usedTypeNames(model)
        return object : XmlElementVisitor() {
            override fun visitXmlTag(tag: XmlTag) {
                if (tag.name !in SolrSchemaTags.FIELD_TYPE) return
                val value = tag.getAttribute("name")?.valueElement ?: return
                val name = value.value
                // A type with no name is a different defect, and a half-typed declaration is
                // unused by definition — reporting it would mean greying out every type the
                // moment the user starts typing one.
                if (name.isEmpty()) return
                if (name in used) return
                SolrInspections.reportOnValue(
                    holder,
                    value,
                    SolrBundle.message("inspection.unusedFieldType.unused", name),
                    highlightType = ProblemHighlightType.LIKE_UNUSED_SYMBOL,
                )
            }
        }
    }

    private companion object {

        /**
         * Every type name something in the configset refers to.
         *
         * Both halves of each fact are read, not just [org.apache.solr.ide.model.SolrFact.effective].
         * A field the server holds and the configset does not is still a field, and the type it
         * names is still in use; taking the effective value would hide it behind the repository's
         * answer and report a type that a live collection depends on as dead. The server half is
         * empty until the server reader lands, so today this costs one extra empty list per fact —
         * but the alternative is a false positive that only appears once the feature it depends on
         * ships, which is the worst moment to discover it.
         */
        fun usedTypeNames(model: SolrFieldModel): Set<String> = buildSet {
            model.fields.values.forEach { fact ->
                listOfNotNull(fact.repository, fact.server).forEach { add(it.type) }
            }
            model.dynamicFields.values.forEach { fact ->
                listOfNotNull(fact.repository, fact.server).forEach { add(it.field.type) }
            }
            model.fieldTypes.values.forEach { fact ->
                listOfNotNull(fact.repository, fact.server)
                    .mapNotNull { it.attributes[SUB_FIELD_TYPE] }
                    .forEach { add(it) }
            }
        }

        /**
         * Whether the schema assembles itself out of other files.
         *
         * Solr allows a schema to `xi:include` its field declarations, and configsets shared across
         * collections use it to keep one list of fields beside several sets of types. The model's
         * parser reads the document as written and does not follow the include, so every type in
         * such a schema would look unused — a whole file greyed out, on a configset that is
         * entirely correct. The plugin has nothing useful to say here and says nothing.
         *
         * The search runs over the file's own PSI rather than over the root tag's children, so it
         * finds the `include` element whether or not the platform has resolved it away, and it is
         * namespace-agnostic because `xi` is a prefix a schema is free to spell differently.
         */
        fun pullsDeclarationsInFromElsewhere(file: PsiFile): Boolean =
            PsiTreeUtil.findChildrenOfType(file, XmlTag::class.java).any { it.localName == INCLUDE }

        /** How `PointType` and `LatLonType` name the type backing each dimension. */
        const val SUB_FIELD_TYPE = "subFieldType"

        /** The local name of an XInclude element, whatever prefix the schema binds it to. */
        const val INCLUDE = "include"
    }
}
