package org.apache.solr.ide.server.query

import com.intellij.httpClient.actions.AddRequestTemplateProvider

/**
 * Publishes this plugin's starting requests to the HTTP Client's *Add Request* menu.
 *
 * **A contribution to somebody else's editor rather than an editor of our own.** The IDE already
 * ships a tool whose entire purpose is authoring and running HTTP requests from files in a
 * repository, and a Solr query is an HTTP request. What this plugin adds is Solr's knowledge — where
 * the endpoints are, which parameter carries a scoring explanation — not execution, not history, not
 * a response viewer, and not a file format.
 *
 * What the templates say lives in [SolrRequestTemplates], so it can be checked without the IDE
 * deciding when to offer them.
 */
class SolrRequestTemplateProvider : AddRequestTemplateProvider {

    /**
     * The requests offered under the Solr group.
     *
     * @return one descriptor per starting request
     */
    override fun provideTemplates(): List<AddRequestTemplateProvider.TemplateDescriptor> =
        SolrRequestTemplates.all.map {
            AddRequestTemplateProvider.TemplateDescriptor(it.template, SolrRequestTemplates.GROUP, it.description)
        }
}
