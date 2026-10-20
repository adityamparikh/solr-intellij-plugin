package org.apache.solr.ide.server.topology

import org.apache.solr.ide.server.connection.SolrConnection
import org.apache.solr.ide.server.query.screen.SolrQueryScreenSpec
import org.apache.solr.ide.server.query.screen.SolrQueryScreenState
import org.apache.solr.ide.server.reading.SolrIndexField

/** What a query offered from the collections tree asks. */
enum class SolrTreeQueryKind {

  /** The first documents of a collection. */
  QUERY,

  /** The same, with Solr's explanation of why each document scored as it did. */
  EXPLAIN,

  /** The documents that have a value in one field, showing that value. */
  FIND_WITH_FIELD,

  /** How many documents hold each of a field's values. */
  COUNT_VALUES,
}

/**
 * One query the collections tree offers.
 *
 * @property kind what it asks
 * @property collection the collection or core it is addressed to
 * @property field the field it is about, or null for a query about the whole collection
 */
data class SolrTreeQuery(
  val kind: SolrTreeQueryKind,
  val collection: String,
  val field: String? = null,
)

/**
 * The queries a row of the collections tree offers, and what each one opens: an HTTP Client
 * request, or the query console's form filled in, as the *Open queries in* setting says.
 *
 * **A request to open, not a query to run.** Choosing one writes the request into a scratch file,
 * or fills the console's form, and stops. The user sees what will be sent, can change it, and runs
 * it with the HTTP Client's button or the console's Execute.
 *
 * **The server's real address is written out**, where the committed request templates write
 * `{{solrUrl}}`. The templates say that for a file a team shares; a scratch file is one developer's
 * and never committed, and a request that ran only after its variables were defined would not be
 * ready to run.
 *
 * **A password is never written, and never read.** A scratch file is a file on disk, and a secret
 * belongs in [com.intellij.ide.passwordSafe.PasswordSafe] alone. A connection that names a user
 * gets a comment naming the header to add instead. Only the connection is passed in, so the
 * password cannot reach the text by any path, and the password store is not read on the UI thread.
 *
 * Pure, so what is offered and what is written can be tested without a tree or an editor.
 */
object SolrTreeQueries {

  /**
   * The queries [row] offers.
   *
   * **A field offers only what its flags can answer.** Finding documents with a field needs it
   * indexed or with doc values; counting its values needs doc values, or an index Solr can uninvert
   * that is not tokenized — counting a tokenized field would count its words, which is not what the
   * item says it does. A field that can do neither offers nothing rather than a request that fails.
   *
   * @param row the row the user asked about
   * @param collection the collection or core that row sits under, or null where it sits under none
   * @return the queries to offer, in menu order; empty where the row offers none
   */
  fun offeredFor(row: SolrTopologyNode, collection: String?): List<SolrTreeQuery> {
    if (collection == null) return emptyList()
    return when (row.kind) {
      SolrTopologyNodeKind.COLLECTION,
      SolrTopologyNodeKind.CORE ->
        listOf(
          SolrTreeQuery(SolrTreeQueryKind.QUERY, collection),
          SolrTreeQuery(SolrTreeQueryKind.EXPLAIN, collection),
        )
      SolrTopologyNodeKind.FIELD -> row.field?.let { fieldQueries(it, collection) }.orEmpty()
      else -> emptyList()
    }
  }

  private fun fieldQueries(field: SolrIndexField, collection: String): List<SolrTreeQuery> =
    buildList {
      val flags = field.schemaProperties.toSet()
      if (INDEXED in flags || DOC_VALUES in flags) {
        add(SolrTreeQuery(SolrTreeQueryKind.FIND_WITH_FIELD, collection, field.name))
      }
      val uninvertible = INDEXED in flags && UNINVERTIBLE in flags && TOKENIZED !in flags
      if (DOC_VALUES in flags || uninvertible) {
        add(SolrTreeQuery(SolrTreeQueryKind.COUNT_VALUES, collection, field.name))
      }
    }

  /**
   * The HTTP Client request [query] opens, addressed to [connection]'s server.
   *
   * @param query what to ask
   * @param connection the server to ask; its username, where it names one, is written into a
   *   comment
   * @return the request, as the text of an `.http` file
   */
  fun requestText(query: SolrTreeQuery, connection: SolrConnection): String {
    val collection = query.collection
    val field = query.field.orEmpty()
    val (title, parameters) =
      when (query.kind) {
        SolrTreeQueryKind.QUERY -> "Query $collection" to "q=*:*&rows=10"
        SolrTreeQueryKind.EXPLAIN ->
          "Explain scoring in $collection" to "q=*:*&rows=5&debugQuery=true"
        SolrTreeQueryKind.FIND_WITH_FIELD ->
          "Find documents with $field in $collection" to
            "q=${encode(field)}:*&fl=id,${encode(field)}&rows=10"
        SolrTreeQueryKind.COUNT_VALUES ->
          "Count values of $field in $collection" to
            "q=*:*&rows=0&facet=true&facet.field=${encode(field)}&facet.limit=20"
      }
    return buildString {
      append("### $title on ${connection.displayName}\n")
      connection.username
        ?.takeIf { it.isNotBlank() }
        ?.let { user ->
          append("# This connection signs in as $user. ")
          append("Before running, add this header under the GET line:\n")
          append("# Authorization: Basic $user <password>\n")
        }
      append("GET ${connection.baseUrl.trimEnd('/')}/${encode(collection)}/select?$parameters\n")
      append("Accept: application/json\n")
    }
  }

  /**
   * What the query console's form holds for [query]: the same query [requestText] writes.
   *
   * **The same rules, so the setting changes where a query lands and nothing about what it asks.**
   * Counting values sends its facet parameters as raw ones, because the console has no facet
   * section yet; they arrive at Solr exactly as the `.http` request sends them.
   *
   * @param query what to ask
   * @param spec the console's form
   * @return the form filled in
   */
  fun consoleState(query: SolrTreeQuery, spec: SolrQueryScreenSpec): SolrQueryScreenState {
    val field = query.field.orEmpty()
    val blank = SolrQueryScreenState(spec)
    return when (query.kind) {
      SolrTreeQueryKind.QUERY -> blank.with("q", "*:*").with("rows", "10")
      SolrTreeQueryKind.EXPLAIN ->
        blank.with("q", "*:*").with("rows", "5").with("debugQuery", "true")
      SolrTreeQueryKind.FIND_WITH_FIELD ->
        blank.with("q", "$field:*").with("fl", "id,$field").with("rows", "10")
      SolrTreeQueryKind.COUNT_VALUES ->
        blank
          .with("q", "*:*")
          .with("rows", "0")
          .withLines("raw", listOf("facet=true", "facet.field=$field", "facet.limit=20"))
    }
  }

  /**
   * The scratch file [query] opens in.
   *
   * Named for the collection, because that is what a user looking through their scratches will
   * recognise. The platform adds a number where the name is taken.
   *
   * @param query the query being opened
   * @return the file's name
   */
  fun scratchFileName(query: SolrTreeQuery): String = "solr-${query.collection}.http"

  // Escapes what a request line cannot carry, and leaves the characters a Solr query is written
  // with — `:`, `*`, `,` — as they are, so the request reads as the query it is.
  private fun encode(value: String): String = buildString {
    value.toByteArray(Charsets.UTF_8).forEach { byte ->
      val char = byte.toInt().toChar()
      if (byte >= 0 && (char.isLetterOrDigit() || char in READABLE)) append(char)
      else append("%%%02X".format(byte.toInt() and 0xFF))
    }
  }

  private const val READABLE = "-._~:*,"

  // Solr's own words for these flags, from the legend its Luke handler returns with every answer.
  private const val INDEXED = "Indexed"
  private const val TOKENIZED = "Tokenized"
  private const val DOC_VALUES = "DocValues"
  private const val UNINVERTIBLE = "UnInvertible"
}
