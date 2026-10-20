package org.apache.solr.ide.server.query.screen

import org.apache.solr.ide.server.query.SolrQueryResult
import org.apache.solr.ide.server.query.SolrQueryResultReader
import org.apache.solr.ide.server.transport.SolrHttpTransport
import org.apache.solr.ide.server.transport.SolrRawAnswer
import org.apache.solr.ide.server.transport.SolrResponse

/**
 * What one answer to the query console amounts to, which decides what its results show.
 *
 * **Every case that arrived keeps the answer as it arrived.** The console shows what Solr sent
 * whatever it concluded — an XML or CSV answer, the HTML page behind a 404 — so only a request that
 * got no answer at all has nothing raw to show.
 */
sealed interface SolrQueryScreenAnswer {

  /**
   * A JSON query result, which has a table.
   *
   * @property raw the answer as it arrived
   * @property result what it holds, in the shape a table needs
   * @property partial whether Solr said the answer is incomplete
   * @property partialDetail Solr's account of what was cut short, where it gave one
   */
  data class Table(
    val raw: SolrRawAnswer,
    val result: SolrQueryResult,
    val partial: Boolean = false,
    val partialDetail: String? = null,
  ) : SolrQueryScreenAnswer

  /**
   * A healthy answer that is not a JSON query result: another writer type, or another handler's.
   *
   * @property raw the answer as it arrived
   */
  data class Raw(val raw: SolrRawAnswer) : SolrQueryScreenAnswer

  /**
   * Solr refused.
   *
   * @property raw the answer as it arrived, often an error page
   * @property code the status it answered with
   * @property message Solr's own message, or null where it gave none
   */
  data class Refused(val raw: SolrRawAnswer, val code: Int, val message: String?) :
    SolrQueryScreenAnswer

  /**
   * No answer arrived.
   *
   * @property description what went wrong, in the transport's words
   */
  data class NotSent(val description: String) : SolrQueryScreenAnswer

  /** Reading an answer. */
  companion object {

    /**
     * What [response] amounts to.
     *
     * @param response what the transport's form POST returned
     * @return the case the results show
     */
    fun of(response: SolrResponse<SolrRawAnswer>): SolrQueryScreenAnswer =
      when (response) {
        is SolrResponse.Success -> of(response.value)
        is SolrResponse.Partial -> of(response.value)
        is SolrResponse.TransportFailure -> NotSent(response.description)
        // A form POST never answers with these, which are what classification makes; carried
        // through rather than thrown, in case that changes.
        is SolrResponse.SolrError ->
          NotSent(listOfNotNull(response.code.toString(), response.message).joinToString(" "))
        is SolrResponse.Unrecognized -> NotSent(response.description)
      }

    private fun of(raw: SolrRawAnswer): SolrQueryScreenAnswer =
      when (val classified = SolrHttpTransport.classify(raw)) {
        is SolrResponse.Success ->
          SolrQueryResultReader.read(classified.value)?.let { Table(raw, it) } ?: Raw(raw)
        is SolrResponse.Partial ->
          SolrQueryResultReader.read(classified.value)?.let {
            Table(raw, it, partial = true, partialDetail = classified.detail)
          } ?: Raw(raw)
        is SolrResponse.SolrError -> Refused(raw, classified.code, classified.message)
        // Not JSON: an XML or CSV answer is a healthy one the console shows as sent.
        is SolrResponse.Unrecognized -> Raw(raw)
        is SolrResponse.TransportFailure -> NotSent(classified.description)
      }

    /**
     * The file extension whose highlighting suits a body of [contentType].
     *
     * @param contentType the `Content-Type` the server declared, or null
     * @return `json`, `xml`, `html` or `txt`
     */
    fun extensionFor(contentType: String?): String {
      val type = contentType.orEmpty().substringBefore(';').trim().lowercase()
      return when {
        type.endsWith("json") -> "json"
        type.endsWith("xml") -> "xml"
        type == "text/html" -> "html"
        else -> "txt"
      }
    }
  }
}
