package com.example.demo;

import org.apache.solr.client.solrj.SolrClient;
import org.apache.solr.client.solrj.SolrQuery;
import org.apache.solr.client.solrj.response.QueryResponse;
import org.springframework.stereotype.Service;

/**
 * Contains TWO DELIBERATE DEFECTS. Do not fix them — they are the demo.
 *
 * <ul>
 *   <li>{@code categry} is a typo for {@code category}.
 *   <li>{@code price} is a field that has never existed in this schema.
 * </ul>
 *
 * <p>Both compile, and both fail silently at runtime: the filter query matches nothing and the
 * field list quietly returns no such value. Note also that the client arrives by injection, so
 * nothing in this file names a server — the normal case, and the reason endpoint discovery cannot
 * simply scan for URL literals.
 */
@Service
public class ProductSearch {

    private final SolrClient solr;

    ProductSearch(SolrClient solr) {
        this.solr = solr;
    }

    /**
     * The correct counterpart, and the one the code-track gestures are shown on.
     *
     * <p>Every name here is declared, so nothing is warned about — which is what makes it the useful
     * subject: the query's field names are coloured and navigable, and the gutter icon beside it
     * runs it. A defect would show the check instead, and {@link #findBooks()} already does that.
     */
    public QueryResponse findSolrBooks() throws Exception {
        SolrQuery q = new SolrQuery();
        q.setQuery("category:books AND name:solr");
        return solr.query("products", q);
    }

    public QueryResponse findBooks() throws Exception {
        SolrQuery q = new SolrQuery("*:*");
        q.addFilterQuery("categry:books");
        q.setFields("id,name,price");
        return solr.query("products", q);
    }
}
