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

    public QueryResponse findBooks() throws Exception {
        SolrQuery q = new SolrQuery("*:*");
        q.addFilterQuery("categry:books");
        q.setFields("id,name,price");
        return solr.query("products", q);
    }
}
