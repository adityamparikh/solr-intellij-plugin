# Project orientation

The [specification](../specs/0002-solr-intellij-plugin.md) describes three surfaces built on one
shared model of what a Solr [field](glossary.md#field) is and what it can do:

- **Configuration** — editor intelligence over [configset](glossary.md#configset) XML: completion,
  navigation, [Find Usages](glossary.md#find-usages), [rename](glossary.md#rename-refactoring),
  [inspections](glossary.md#inspection), quick documentation, [inlay hints](glossary.md#inlay-hint),
  and [intentions](glossary.md#intention) that generate new configuration. Needs no running Solr.
- **Server** — a live connection: browsing collections, a query console, indexing test documents,
  uploading and reloading configsets, and the drift view that shows where a repository and a deployed
  server disagree. Needs a running Solr.
- **Code** — Java and Kotlin that use [SolrJ](glossary.md#solrj): checking and completing field
  names, colouring query strings, running a query from a gutter icon. Needs a Solr client on the
  module's classpath and a configset in the project; a connection only to run a query.

Once the field model exists the three are independent of each other, so one can move ahead of the
others without blocking them. [The plan](../specs/plans/0002-solr-intellij-plugin-plan.md) owns what
is built, track by track; its [build order](../specs/plans/0002-solr-intellij-plugin-plan.md#build-order)
shows the dependencies.

## Where to look next

| If you want to… | Start here |
|---|---|
| Use the plugin as a Solr developer | [User guide](user-guide.md) |
| Know exactly what is built and what isn't, step by step | [The implementation plan](../specs/plans/0002-solr-intellij-plugin-plan.md) |
| Know what the plugin is *for*, including what's still ahead | [The specification](../specs/0002-solr-intellij-plugin.md) |
| Reproduce a claim by hand, or see what's been verified and when | [Manual test suite](manual-test-suite.md) |
| See what a capability looks like without building it | [Screenshot catalog](screenshots.md) |
| Set up the build and find work worth doing | [Contributing](contributing.md) |
| Understand where a change belongs in the codebase | [Code organization](code-organization.md) |
| Learn the platform this plugin is built on, using it as the worked example | [Plugin development tutorial](modern-intellij-plugin-development.md) |
