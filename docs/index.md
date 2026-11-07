---
hide:
  - navigation
  - toc
---

# Solr Support for IntelliJ

IDE tooling for Apache Solr development in IntelliJ IDEA: language intelligence for configset XML,
a live view of a Solr server, and field-name checking in the Java and Kotlin that calls Solr.

## Start here

<div class="grid cards" markdown>

- **Trying it for the first time**

    [Quick start](quick-start.md) — ten minutes in the demo project. Or [Installing](installing.md) — what you need, how to build and install it, and why a silent IDE is the
    expected first surprise. Then [the user guide](user-guide.md).

- **Using it every day**

    [The user guide](user-guide.md) gives every capability, the gesture that reaches it, and what it
    should show. [The inspection catalog](inspection-catalog.md) says what each check reports and what
    it stays silent about.

- **Working on the plugin**

    [Contributing](contributing.md) covers setup and where work comes from;
    [code organization](code-organization.md) says where a change goes and what each package may import.

- **Looking something up**

    [The API reference](api-reference.md) (KDoc), [the extension points](extension-points.md),
    [the glossary](glossary.md), and [compatibility](compatibility.md).

</div>

!!! note "Availability"
    The plugin is **not on the JetBrains Marketplace yet and has no release ZIP**. Today you build it
    from source; [Installing](installing.md) is exact about it.

## What it does

| Surface | What you get |
|---|---|
| **Configuration files** | Twelve inspections with quick-fixes, completion over Solr's own vocabulary, cross-file navigation and rename, quick documentation, and inline hints saying what each field can match. |
| **A live server** | Connections kept in the IDE's password safe, a collections/shards/replicas tool window, queries through the HTTP Client or a query console, a drift view with upload and reload, and a test-document editor. |
| **Java and Kotlin using SolrJ** | Unknown field names flagged, completion offering only the fields a call can use, navigation to the declaration, coloured query strings, and a run-from-gutter action. |

The editor never contacts a server: every editor answer comes from the files in your project and
from Solr's own jars. Server features run only when you ask.

!!! tip "Light and dark"
    Use the sun/moon toggle in the header. Screenshots and clips switch with it, showing the IDE in
    the matching theme.

## See it in action

Short clips of the IDE in the theme you have selected, grouped by the three places you work in. Each one is a single gesture on the demo project, with a caption naming the step.

### Editor

Configuration files: `managed-schema.xml` and `solrconfig.xml`. No server is involved.

**Completion**

<video src="videos/completion-light.mp4#only-light" poster="videos/posters/completion-light.jpg" controls loop muted playsinline preload="none" aria-label="Completion: Ctrl-Space inside a field tag offers Solr's own vocabulary, each entry with a one-line summary."></video>
<video src="videos/completion-dark.mp4#only-dark" poster="videos/posters/completion-dark.jpg" controls loop muted playsinline preload="none" aria-label="Completion: Ctrl-Space inside a field tag offers Solr's own vocabulary, each entry with a one-line summary."></video>

Ctrl-Space inside a field tag offers Solr's own vocabulary, each entry with a one-line summary.

**Quick documentation**

<video src="videos/quick-documentation-light.mp4#only-light" poster="videos/posters/quick-documentation-light.jpg" controls loop muted playsinline preload="none" aria-label="Quick documentation: Hover a field, or press Ctrl-J, to see each property's value and where it came from."></video>
<video src="videos/quick-documentation-dark.mp4#only-dark" poster="videos/posters/quick-documentation-dark.jpg" controls loop muted playsinline preload="none" aria-label="Quick documentation: Hover a field, or press Ctrl-J, to see each property's value and where it came from."></video>

Hover a field, or press Ctrl-J, to see each property's value and where it came from.

**Inspection and quick-fix**

<video src="videos/quick-fix-light.mp4#only-light" poster="videos/posters/quick-fix-light.jpg" controls loop muted playsinline preload="none" aria-label="Inspection and quick-fix: Alt-Enter on a copyField that names a field the schema does not declare."></video>
<video src="videos/quick-fix-dark.mp4#only-dark" poster="videos/posters/quick-fix-dark.jpg" controls loop muted playsinline preload="none" aria-label="Inspection and quick-fix: Alt-Enter on a copyField that names a field the schema does not declare."></video>

Alt-Enter on a copyField that names a field the schema does not declare.

### Server

A live Solr, reached only when you ask.

**Connections**

<video src="videos/connections-light.mp4#only-light" poster="videos/posters/connections-light.jpg" controls loop muted playsinline preload="none" aria-label="Connections: Settings → Tools → Solr Connections: the servers this project talks to, with passwords kept in the IDE's password safe."></video>
<video src="videos/connections-dark.mp4#only-dark" poster="videos/posters/connections-dark.jpg" controls loop muted playsinline preload="none" aria-label="Connections: Settings → Tools → Solr Connections: the servers this project talks to, with passwords kept in the IDE's password safe."></video>

Settings → Tools → Solr Connections: the servers this project talks to, with passwords kept in the IDE's password safe.

**Drift against a server**

<video src="videos/drift-light.mp4#only-light" poster="videos/posters/drift-light.jpg" controls loop muted playsinline preload="none" aria-label="Drift against a server: Compare a configset with a live collection, and see the request that would close each difference."></video>
<video src="videos/drift-dark.mp4#only-dark" poster="videos/posters/drift-dark.jpg" controls loop muted playsinline preload="none" aria-label="Drift against a server: Compare a configset with a live collection, and see the request that would close each difference."></video>

Compare a configset with a live collection, and see the request that would close each difference.

**Index a test document**

<video src="videos/test-document-light.mp4#only-light" poster="videos/posters/test-document-light.jpg" controls loop muted playsinline preload="none" aria-label="Index a test document: A starting document written from the collection's schema, checked before anything is sent."></video>
<video src="videos/test-document-dark.mp4#only-dark" poster="videos/posters/test-document-dark.jpg" controls loop muted playsinline preload="none" aria-label="Index a test document: A starting document written from the collection's schema, checked before anything is sent."></video>

A starting document written from the collection's schema, checked before anything is sent.

**Index a document and find it**

<video src="videos/index-document-light.mp4#only-light" poster="videos/posters/index-document-light.jpg" controls loop muted playsinline preload="none" aria-label="Index a document and find it: Send a test document, then search the console for it: one document, the one just indexed."></video>
<video src="videos/index-document-dark.mp4#only-dark" poster="videos/posters/index-document-dark.jpg" controls loop muted playsinline preload="none" aria-label="Index a document and find it: Send a test document, then search the console for it: one document, the one just indexed."></video>

Send a test document, then search the console for it: one document, the one just indexed.

**Run a query in the HTTP Client**

<video src="videos/query-light.mp4#only-light" poster="videos/posters/query-light.jpg" controls loop muted playsinline preload="none" aria-label="Run a query: Right-click a collection, run the generated request, and read the answer in the HTTP Client."></video>
<video src="videos/query-dark.mp4#only-dark" poster="videos/posters/query-dark.jpg" controls loop muted playsinline preload="none" aria-label="Run a query: Right-click a collection, run the generated request, and read the answer in the HTTP Client."></video>

Right-click a collection, run the generated request, and read the answer in the HTTP Client.

**Run a query in the console**

<video src="videos/query-console-light.mp4#only-light" poster="videos/posters/query-console-light.jpg" controls loop muted playsinline preload="none" aria-label="Run a query in the console: The query console is the Admin UI's form inside the IDE: edit q and fq, Execute with ⌘Enter, and read the results or Solr's raw response."></video>
<video src="videos/query-console-dark.mp4#only-dark" poster="videos/posters/query-console-dark.jpg" controls loop muted playsinline preload="none" aria-label="Run a query in the console: The query console is the Admin UI's form inside the IDE: edit q and fq, Execute with ⌘Enter, and read the results or Solr's raw response."></video>

The query console is the Admin UI's form inside the IDE: edit q and fq, Execute with ⌘Enter, and read the results or Solr's raw response.

### Code

Java and Kotlin that call SolrJ.

**Field names in Java**

<video src="videos/java-code-light.mp4#only-light" poster="videos/posters/java-code-light.jpg" controls loop muted playsinline preload="none" aria-label="Field names in Java: A field name no configset declares is flagged in a SolrJ call, and completion offers only the declared ones."></video>
<video src="videos/java-code-dark.mp4#only-dark" poster="videos/posters/java-code-dark.jpg" controls loop muted playsinline preload="none" aria-label="Field names in Java: A field name no configset declares is flagged in a SolrJ call, and completion offers only the declared ones."></video>

A field name no configset declares is flagged in a SolrJ call, and completion offers only the declared ones.

## Where to go next

New to Solr or IntelliJ terms? Start with the [glossary](glossary.md). Which IDE and Solr lines a
release supports is in [compatibility](compatibility.md).
