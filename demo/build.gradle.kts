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

dependencies {
    implementation("org.springframework.boot:spring-boot-starter")
    // Plain SolrJ, wired by Spring. NOT Spring Data Solr, which is unmaintained upstream and which
    // the plugin does not support — see docs/demo/README.md.
    implementation("org.apache.solr:solr-solrj:9.10.0")
}
