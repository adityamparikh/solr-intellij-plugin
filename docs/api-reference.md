# API reference

The plugin's API reference is generated from its KDoc by Dokka, so every public class, function and
property is documented, and the build fails if one is not.

**[Open the API reference](../api/index.html){ .md-button .md-button--primary }**

## What is in it

The reference is organised by package, and each package has a short overview before its symbols:

| Area | What it holds |
|---|---|
| `org.apache.solr.ide.model` | What a field is (`schema`), what a configuration file may legally contain (`vocabulary`), and query structure (`query`). Imports no IntelliJ type. |
| `org.apache.solr.ide.configset` | The configuration-file side: `activation`, `reading`, `editing`, `navigation`, and the `schema` and `solrconfig` aspects with their parsing, inspections, completion, documentation and references. |
| `org.apache.solr.ide.server` | Talking to a Solr server: `connection`, `transport`, `reading`, the query screen and `drift`. |
| `org.apache.solr.ide.code` | Field names in Java and Kotlin that use SolrJ: completion, highlighting, inspection, navigation and run. |

Every symbol links to the exact line in the source on GitHub.

## Reading it alongside the guides

The [code organization](code-organization.md) page says where a change goes and what each package
boundary forbids; the reference says what each class does. To add an editor feature, start with
[adding an editor feature](how-to/add-an-editor-feature.md).
