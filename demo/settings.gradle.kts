// Deliberately a STANDALONE build, not a subproject of the plugin.
//
// The plugin's own `settings.gradle.kts` does not include this directory, and must not: the demo
// depends on Spring Boot and SolrJ, carries defects on purpose, and would otherwise be compiled by
// `./gradlew build`, counted by Kover's coverage floor, and scanned by Dokka's documentation gate.
// Keeping it standalone means the fixture can contain whatever the demo needs without negotiating
// with the plugin's build gates.
rootProject.name = "solr-plugin-demo"
