package org.apache.solr.ide.server.query

/**
 * The starting requests this plugin offers inside an `.http` file.
 *
 * **Every one addresses `{{solrUrl}}` rather than a host**, and that is the whole reason saved
 * queries are `.http` files at all. A query worth keeping is a query worth committing, and a
 * committed file naming `localhost:8983` is a file that does not work on a colleague's machine —
 * their Solr is not this one. The HTTP Client already solves that with environments:
 * `http-client.env.json` committed beside the requests, `http-client.private.env.json` git-ignored
 * beside that. A template that hardcoded a host would quietly opt every user out of the mechanism
 * that makes the file shareable.
 *
 * Plain data, separate from the extension point that publishes it, so what the templates say can be
 * checked without the IDE deciding when to offer them.
 */
object SolrRequestTemplates {

    /** The group these appear under in the HTTP Client's *Add Request* menu. */
    const val GROUP = "Solr"

    /** The environment variable a template expects to hold the Solr base URL. */
    const val URL_VARIABLE = "solrUrl"

    /** The environment variable a template expects to hold the collection or core name. */
    const val COLLECTION_VARIABLE = "collection"

    /**
     * One offered request.
     *
     * @property description what the menu entry says
     * @property template the `.http` text inserted when it is chosen
     */
    data class Template(val description: String, val template: String)

    /**
     * Every request offered, in the order a user meets them.
     *
     * Querying comes first because it is what the console is for; the two that read a collection's
     * shape come after, because a user reaches for them when a query returned something they did
     * not expect.
     */
    val all: List<Template> = listOf(
        Template(
            description = "Query a Solr collection",
            template = """
                ### Query a Solr collection
                # Define {{$URL_VARIABLE}} and {{$COLLECTION_VARIABLE}} in http-client.env.json so this
                # file works for everyone who clones the repository.
                GET {{$URL_VARIABLE}}/{{$COLLECTION_VARIABLE}}/select?q=*:*&rows=10
                Accept: application/json
            """.trimIndent(),
        ),
        Template(
            description = "Query with a field list and a sort",
            template = """
                ### Query with a field list and a sort
                GET {{$URL_VARIABLE}}/{{$COLLECTION_VARIABLE}}/select?q=*:*&fl=id,score&sort=score desc&rows=10
                Accept: application/json
            """.trimIndent(),
        ),
        Template(
            // `debugQuery=true` rather than `debug=results`, because the explanation is the point and
            // this is the parameter that carries it alongside the parsed query and the timings.
            description = "Explain why documents scored",
            template = """
                ### Explain why documents scored
                # debugQuery=true returns the parsed query and a per-document scoring explanation.
                GET {{$URL_VARIABLE}}/{{$COLLECTION_VARIABLE}}/select?q=*:*&rows=5&debugQuery=true
                Accept: application/json
            """.trimIndent(),
        ),
        Template(
            // The JSON Request API rather than URL parameters, because a body is where field
            // completion can help — a query written as a URL query string has nowhere for the
            // editor to offer anything.
            description = "Query with a JSON body",
            template = """
                ### Query with a JSON body
                # Solr's JSON Request API. Field names complete inside `fields`, `sort` and a
                # facet's `field`, from the configsets in this project.
                POST {{$URL_VARIABLE}}/{{$COLLECTION_VARIABLE}}/query
                Content-Type: application/json
                Accept: application/json

                {
                  "query": "*:*",
                  "fields": ["id"],
                  "limit": 10
                }
            """.trimIndent(),
        ),
        Template(
            description = "Read a collection's schema",
            template = """
                ### Read a collection's schema
                GET {{$URL_VARIABLE}}/{{$COLLECTION_VARIABLE}}/schema
                Accept: application/json
            """.trimIndent(),
        ),
        Template(
            // The Luke handler rather than the Schema API, because the question it answers is the
            // other one: what the index *has*, including every field a dynamic pattern created.
            description = "List the fields the index actually holds",
            template = """
                ### List the fields the index actually holds
                # The Luke handler reports fields a dynamic pattern created at index time, which the
                # schema cannot name.
                GET {{$URL_VARIABLE}}/{{$COLLECTION_VARIABLE}}/admin/luke
                Accept: application/json
            """.trimIndent(),
        ),
    )
}
