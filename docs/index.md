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
Short clips of the IDE in the theme you have selected. Each one is a single gesture on the demo project.
<div class="grid cards" markdown>

- **Quick documentation**

    <video src="videos/quick-documentation-light.mp4#only-light" poster="videos/posters/quick-documentation-light.jpg" controls loop muted playsinline preload="none" aria-label="Quick documentation: Hover a field, or press Ctrl-J, to see each property's value and where it came from."></video>
    <video src="videos/quick-documentation-dark.mp4#only-dark" poster="videos/posters/quick-documentation-dark.jpg" controls loop muted playsinline preload="none" aria-label="Quick documentation: Hover a field, or press Ctrl-J, to see each property's value and where it came from."></video>

    Hover a field, or press Ctrl-J, to see each property's value and where it came from.

- **Completion**

    <video src="videos/completion-light.mp4#only-light" poster="videos/posters/completion-light.jpg" controls loop muted playsinline preload="none" aria-label="Completion: Ctrl-Space inside a field tag offers Solr's own vocabulary, each entry with a one-line summary."></video>
    <video src="videos/completion-dark.mp4#only-dark" poster="videos/posters/completion-dark.jpg" controls loop muted playsinline preload="none" aria-label="Completion: Ctrl-Space inside a field tag offers Solr's own vocabulary, each entry with a one-line summary."></video>

    Ctrl-Space inside a field tag offers Solr's own vocabulary, each entry with a one-line summary.

- **Inspection and quick-fix**

    <video src="videos/quick-fix-light.mp4#only-light" poster="videos/posters/quick-fix-light.jpg" controls loop muted playsinline preload="none" aria-label="Inspection and quick-fix: Alt-Enter on a copyField that names a field the schema does not declare."></video>
    <video src="videos/quick-fix-dark.mp4#only-dark" poster="videos/posters/quick-fix-dark.jpg" controls loop muted playsinline preload="none" aria-label="Inspection and quick-fix: Alt-Enter on a copyField that names a field the schema does not declare."></video>

    Alt-Enter on a copyField that names a field the schema does not declare.

- **Drift against a server**

    <video src="videos/drift-light.mp4#only-light" poster="videos/posters/drift-light.jpg" controls loop muted playsinline preload="none" aria-label="Drift against a server: Compare a configset with a live collection, and see why a change is refused."></video>
    <video src="videos/drift-dark.mp4#only-dark" poster="videos/posters/drift-dark.jpg" controls loop muted playsinline preload="none" aria-label="Drift against a server: Compare a configset with a live collection, and see why a change is refused."></video>

    Compare a configset with a live collection, and see why a change is refused.

- **Connections**

    <video src="videos/connections-light.mp4#only-light" poster="videos/posters/connections-light.jpg" controls loop muted playsinline preload="none" aria-label="Connections: Settings → Tools → Solr Connections: the servers this project talks to, with passwords kept in the IDE's password safe."></video>
    <video src="videos/connections-dark.mp4#only-dark" poster="videos/posters/connections-dark.jpg" controls loop muted playsinline preload="none" aria-label="Connections: Settings → Tools → Solr Connections: the servers this project talks to, with passwords kept in the IDE's password safe."></video>

    Settings → Tools → Solr Connections: the servers this project talks to, with passwords kept in the IDE's password safe.

- **Index a test document**

    <video src="videos/test-document-light.mp4#only-light" poster="videos/posters/test-document-light.jpg" controls loop muted playsinline preload="none" aria-label="Index a test document: A starting document written from the collection's schema, checked before it is sent."></video>
    <video src="videos/test-document-dark.mp4#only-dark" poster="videos/posters/test-document-dark.jpg" controls loop muted playsinline preload="none" aria-label="Index a test document: A starting document written from the collection's schema, checked before it is sent."></video>

    A starting document written from the collection's schema, checked before it is sent.

- **Run a query**

    <video src="videos/query-light.mp4#only-light" poster="videos/posters/query-light.jpg" controls loop muted playsinline preload="none" aria-label="Run a query: Right-click a collection, run the generated request, and read the answer in the HTTP Client."></video>
    <video src="videos/query-dark.mp4#only-dark" poster="videos/posters/query-dark.jpg" controls loop muted playsinline preload="none" aria-label="Run a query: Right-click a collection, run the generated request, and read the answer in the HTTP Client."></video>

    Right-click a collection, run the generated request, and read the answer in the HTTP Client.

</div>

## Where to go next

New to Solr or IntelliJ terms? Start with the [glossary](glossary.md). Which IDE and Solr lines a
release supports is in [compatibility](compatibility.md).
