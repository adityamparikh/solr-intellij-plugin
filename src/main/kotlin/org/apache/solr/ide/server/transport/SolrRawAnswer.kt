package org.apache.solr.ide.server.transport

/**
 * One HTTP answer, exactly as it arrived.
 *
 * **What a console shows, before anything is concluded from it.** The plugin's own reads want an
 * outcome — a schema, or why there is none — and get one from [SolrHttpTransport.classify]. A query
 * someone ran wants that too, and also what the server actually sent: an XML or CSV answer, or the
 * error page behind a failure, none of which survives being parsed as JSON.
 *
 * @property status the HTTP status
 * @property contentType the `Content-Type` the server declared, or null where it declared none
 * @property body the body, decoded as text. A binary answer (`wt=javabin`) arrives mangled rather
 *   than refused, since showing it is all a console can do with it
 */
data class SolrRawAnswer(val status: Int, val contentType: String?, val body: String)
