package com.example.demo;

import org.apache.solr.client.solrj.SolrClient;
import org.apache.solr.client.solrj.impl.Http2SolrClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Plain SolrJ, wired by Spring — deliberately <em>not</em> Spring Data Solr, which is unmaintained
 * upstream and out of scope for the plugin.
 *
 * <p>The URL is a property reference rather than a literal, which is the whole point: finding the
 * server means following {@code ${app.solr.url}} back to the profile that supplies it, not scanning
 * for URL literals.
 */
@Configuration
public class SolrConfig {

    @Bean
    SolrClient solrClient(@Value("${app.solr.url}") String url) {
        return new Http2SolrClient.Builder(url).build();
    }
}
