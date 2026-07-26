package com.example.demo;

import org.apache.solr.client.solrj.beans.Field;

/**
 * Contains ONE DELIBERATE DEFECT. Do not fix it — it is the demo.
 *
 * <p>{@code prce} is a typo, and it sits in an annotation rather than in a string literal. That
 * distinction matters: a plugin that only inspects string arguments to SolrJ calls would miss it
 * entirely.
 */
public class Product {

    @Field("id")
    private String id;

    @Field("name")
    private String name;

    @Field("prce")
    private Double price;

    public String getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public Double getPrice() {
        return price;
    }
}
