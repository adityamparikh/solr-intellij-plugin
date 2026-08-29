package org.apache.solr.ide.server.query

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import org.apache.solr.ide.configset.activation.SolrConfigset
import org.apache.solr.ide.configset.activation.SolrProjectConfigsets
import org.apache.solr.ide.configset.reading.SolrConfigsetReader

/**
 * One field a query could name, and where the plugin learned about it.
 *
 * @property name the field's name
 * @property type the field type it is declared as
 * @property configset the configset that declares it, shown so a user with several can tell which
 *   answer they are being given
 * @property dynamic whether this is a pattern like `*_s` rather than a field declared by name
 */
data class SolrCompletionField(
    val name: String,
    val type: String,
    val configset: String,
    val dynamic: Boolean = false,
)

/**
 * The fields a query written in this project could reasonably name.
 *
 * **Read from the project's configsets, not from a server, and that is a deliberate trade.** An
 * `.http` file sits anywhere in a repository and resolves to no configset of its own, so the field
 * names have to come from somewhere chosen rather than somewhere implied. The configsets win on the
 * property that matters most while typing: they are already parsed, already cached, and answering
 * from them costs nothing — the editor never waits on a network, which is this plugin's standing
 * rule everywhere else. The cost is that a field only the deployed server has will not be offered,
 * and a field the repository declares but the server has not seen will be.
 *
 * **Every offer names the configset it came from.** A project with several configsets has several
 * answers, and merging them into one list without saying which is which would let a user complete a
 * field that exists in a collection they are not querying.
 */
@Service(Service.Level.PROJECT)
class SolrProjectFields(private val project: Project) {

    /**
     * Every field and dynamic pattern the project's configsets declare.
     *
     * Empty during indexing, because the configsets it reads are — see
     * [SolrProjectConfigsets.all]. Completion that offers nothing is understood as "not ready";
     * completion that offers half is understood as the truth.
     *
     * @return the fields, in configset order then declaration order, without duplicates by name
     */
    fun all(): List<SolrCompletionField> =
        SolrProjectConfigsets.getInstance(project).all().flatMap { fieldsIn(it) }.distinctBy { it.name }

    private fun fieldsIn(configset: SolrConfigset): List<SolrCompletionField> {
        val model = SolrConfigsetReader.getInstance(project).modelFor(configset)
        val declared = model.fields.values.map { it.effective }.map { field ->
            SolrCompletionField(field.name, field.type, configset.name)
        }
        // Offered too, and marked, because a pattern is what a user names when they mean the field
        // it will create — `*_s` is a legitimate thing to type into a field list, and hiding it
        // would leave the most common way of naming a dynamic field unavailable.
        val patterns = model.dynamicFields.values.map { it.effective }.map { pattern ->
            SolrCompletionField(pattern.pattern, pattern.field.type, configset.name, dynamic = true)
        }
        return declared + patterns
    }

    /** Service lookup. */
    companion object {
        /**
         * The field source for [project].
         *
         * @param project the project whose configsets to read
         * @return the project-level service
         */
        fun getInstance(project: Project): SolrProjectFields = project.service()
    }
}
