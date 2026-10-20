package org.apache.solr.ide.server.query.screen

import com.intellij.codeInsight.completion.CompletionParameters
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.openapi.progress.runBlockingCancellable
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.ui.TextFieldWithAutoCompletionListProvider
import org.apache.solr.ide.SolrBundle
import org.apache.solr.ide.configset.reading.SolrCompletionField
import org.apache.solr.ide.configset.reading.SolrProjectFields
import org.apache.solr.ide.server.connection.SolrConnectionSettings
import org.apache.solr.ide.server.query.SolrCollectionFields
import org.apache.solr.ide.server.transport.SolrResponse

/**
 * Field names in the query console's `fl`, `sort`, `df`, `qf`, `pf`, `pf2` and `pf3`.
 *
 * **The tab's own collection answers, and only an explicit Ctrl-Space reads it from the server.**
 * The rule is the one `.http` completion keeps: a popup that opened while typing answers from what
 * was already read, or from the project's configsets, and never opens a socket on a keystroke. The
 * read is remembered by [SolrCollectionFields], so the tab and an `.http` file share it.
 *
 * **What a field list also takes is offered beside the fields** — `score`, `*`, `[docid]` — and the
 * console reports nothing it does not recognise, since `fl` holds functions and transformers no
 * list of fields could contain.
 *
 * @param project the project
 * @param file the tab whose connection and collection answer
 * @param also what to offer beside the field names
 */
internal class SolrQueryScreenFieldCompletion(
  private val project: Project,
  private val file: SolrQueryScreenFile,
  private val also: List<String>,
) : TextFieldWithAutoCompletionListProvider<SolrCompletionField>(emptyList()) {

  override fun getItems(
    prefix: String?,
    cached: Boolean,
    parameters: CompletionParameters?,
  ): Collection<SolrCompletionField> {
    val syntax = also.map {
      SolrCompletionField(it, "", SolrBundle.message("queryScreen.completion.syntax"))
    }
    if (DumbService.isDumb(project)) return syntax
    val explicit = parameters != null && !parameters.isAutoPopup
    val fields = if (explicit) runBlockingCancellable { read() } else known()
    return syntax + fields
  }

  override fun getLookupString(item: SolrCompletionField): String = item.name

  override fun createLookupBuilder(item: SolrCompletionField): LookupElementBuilder =
    item.asLookupElement()

  override fun getPrefix(text: String, offset: Int): String = prefixOf(text, offset)

  /**
   * What is offered without asking the server: the collection's fields as last read, or the
   * project's configsets'.
   *
   * @return the fields
   */
  fun known(): List<SolrCompletionField> {
    val connection = connection() ?: return configsetFields()
    return SolrCollectionFields.getInstance(project).cached(connection, file.collection)
      ?: configsetFields()
  }

  /**
   * The collection's fields, read from the server where they have not been already.
   *
   * A read that fails answers with the configsets', as a popup that opened by itself would.
   *
   * @return the fields
   */
  suspend fun read(): List<SolrCompletionField> {
    val connection = connection() ?: return configsetFields()
    val fields = SolrCollectionFields.getInstance(project)
    fields.cached(connection, file.collection)?.let {
      return it
    }
    val read = fields.fetch(connection, file.collection)
    return (read as? SolrResponse.Success)?.value ?: configsetFields()
  }

  private fun connection() =
    SolrConnectionSettings.getInstance(project).connections.firstOrNull {
      it.id == file.connectionId
    }

  private fun configsetFields() = SolrProjectFields.getInstance(project).all()

  /** The prefix rule, kept pure. */
  companion object {

    private val SEPARATORS = charArrayOf(',', ' ', '\t', '(', ')')

    /**
     * The name being typed at [offset] in [text]: what follows the last separator before it.
     *
     * A field list is comma-separated, a sort is `field dir, field dir`, a `qf` is space separated
     * with boosts, and a function wraps its arguments in parentheses — so each of those begins a
     * new name.
     *
     * @param text the input's text
     * @param offset the caret
     * @return the prefix, possibly empty
     */
    fun prefixOf(text: String, offset: Int): String {
      val before = text.substring(0, offset.coerceIn(0, text.length))
      return before.substring(before.lastIndexOfAny(SEPARATORS) + 1)
    }
  }
}
