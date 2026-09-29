plugins {
    java
    id("org.springframework.boot") version "3.5.6"
    id("io.spring.dependency-management") version "1.1.7"
}

group = "com.example"
version = "0.0.1-SNAPSHOT"

java {
    // Matches the plugin's own toolchain floor, which comes from Solr 10 requiring Java 21.
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

repositories {
    mavenCentral()
}

// SolrJ 9 is built against Jetty 10, and Spring Boot 3.5's dependency management would otherwise
// raise every Jetty artifact to 12, which removed the `org.eclipse.jetty.client.api` package
// Http2SolrClient needs: the app compiles and then fails to create its SolrClient. Keep this equal
// to the Jetty version the SolrJ below declares.
//
// Not `extra["jetty.version"]`: Boot's BOM also imports Jetty 12's ee10 BOM through that property,
// which has no 10.x release, so the override fails the whole import. A later import wins instead.
dependencyManagement {
    imports {
        mavenBom("org.eclipse.jetty:jetty-bom:10.0.26")
    }
}

dependencies {
    implementation("org.springframework.boot:spring-boot-starter")
    // Plain SolrJ, wired by Spring. NOT Spring Data Solr, which is unmaintained upstream and which
    // the plugin does not support — see docs/demo/README.md.
    implementation("org.apache.solr:solr-solrj:9.10.0")
}
