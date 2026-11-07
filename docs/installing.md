# Installing

## What you need

**IntelliJ IDEA 2026.2 or later** — IDEA specifically, not the other JetBrains IDEs; see
[compatibility](compatibility.md) for why, and for which Solr lines a version understands.

You do **not** need a running Solr for the editor features: they read the files in your project and
never contact a server. Only the server features do, and only when you ask.

## Building and installing it

The plugin is not on the JetBrains Marketplace and has no published release yet, so you build it.
That needs **JDK 21 or later**, the floor Solr 10 sets.

```bash
git clone https://github.com/adityamparikh/solr-intellij-plugin.git
cd solr-intellij-plugin
./gradlew buildPlugin
```

The ZIP lands in `build/distributions/`. The first build is slow: it resolves Solr artifacts for
every supported line to generate the class catalog.

Then, in IntelliJ IDEA:

<kbd>Settings</kbd> → <kbd>Plugins</kbd> → <kbd>⚙️</kbd> → <kbd>Install Plugin from Disk…</kbd> →
pick the ZIP → restart when prompted.

## Why nothing is happening

The plugin stays silent outside a Solr project, on purpose. Check, in order:

**1. Does your project depend on a Solr client?** The plugin wakes up only in a project whose
dependencies include `solr-solrj` or a library carrying it — Spring Data Solr, its Boot starter, or
Camel's Solr component — matched by artifact id, never by version. **A repository of bare configsets
with no build file never activates on its own**, and that is the most common reason for silence.

**2. Is the file one the plugin recognises?** It works on a configset's own files —
`managed-schema.xml`, `schema.xml`, `solrconfig.xml`, and the resource files an analyzer chain names.
An arbitrary XML file is not a configset file.

**If a configset does not light up, mark it by hand.** Right-click its directory in the Project view
and choose *Mark Directory as Solr Configset Root*; this is also the way in for a bare configset
repository, which the first gate turns away. **Settings → Languages & Frameworks → Solr Configsets**
lists every configset the plugin recognises, says whether detection found it or someone marked it,
and holds the switch that turns recognition off for the project. Marked roots are stored in
`.idea/solr.xml`, so they reach your teammates with the project, and can be removed on the same page.

## Checking it works

Open a `managed-schema.xml` in a project that passes both gates. You should see:

- **An inline hint beside each `<field>`** saying what that field can match — whole-value or
  tokenised, case-sensitive or not.
- **<kbd>Ctrl-J</kbd> on a field name** showing its resolved properties and where each value came
  from: the field, its type, or Solr's own default at your schema's declared version.
- **<kbd>Ctrl</kbd>-click on a `type` attribute** landing on the `<fieldType>` that declares it.

If those three work, the rest of [the user guide](user-guide.md) is available too.

## Uninstalling

<kbd>Settings</kbd> → <kbd>Plugins</kbd> → find **Solr Support** → <kbd>⚙️</kbd> →
<kbd>Uninstall</kbd>. What the plugin saved stays behind in the project's `.idea` directory: marked
configset roots in `solr.xml`, server connections in the workspace file. Their passwords stay in the
IDE's password safe.
