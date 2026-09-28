# Installing

> **Who this is for.** Someone who wants the plugin working in their own IntelliJ IDEA, rather than
> someone developing it.
> **Read first:** nothing. If it goes quiet after installing, jump to
> [Why nothing is happening](#why-nothing-is-happening) — that is the expected first surprise.

## What you need

**IntelliJ IDEA 2026.2 or later.** IDEA specifically, not the other JetBrains IDEs;
[the compatibility matrix](compatibility.md) explains why that is a decision rather than an
oversight, and which Solr lines a given version understands.

You do **not** need a running Solr. The plugin reads the files in your project and never opens a
socket — that is the design of this release rather than a limitation of it.

## Which route to use

| Route | Available |
|---|---|
| JetBrains Marketplace | **Not yet** — nothing has been published; see [releasing](how-to/releasing.md) for what is still missing |
| ZIP from a GitHub release | **Not yet** — no release has been published |
| Build it yourself | **Today** |

So there is one route right now, and it is the third.

## Building and installing it yourself

Requires **JDK 21 or later** — Solr 10 needs Java 21, and that sets the floor.

```bash
git clone https://github.com/adityamparikh/solr-intellij-plugin.git
cd solr-intellij-plugin
./gradlew buildPlugin
```

The ZIP lands in `build/distributions/`. **The first build is slow** — it resolves Solr artifacts for
every supported line to generate the class catalog. That is expected and happens once.

Then, in IntelliJ IDEA:

<kbd>Settings</kbd> → <kbd>Plugins</kbd> → <kbd>⚙️</kbd> → <kbd>Install Plugin from Disk…</kbd> →
pick the ZIP → restart when prompted.

### Installing from a release ZIP, once one exists

Identical, minus the build: download the ZIP from the release page and install it from disk. Nothing
about the gesture changes.

## Why nothing is happening

**This is the expected first surprise, and it is deliberate.** The plugin stays completely silent
outside a Solr project, and there is currently no button anywhere that overrides that. In order:

**1. Does your project depend on a Solr client?** The plugin only wakes up in a project whose
dependencies include `solr-solrj`, or a wrapper carrying it — matched by artifact id, never by
version. **A repository of bare configsets with no build file will never activate**, and that is the
single most common reason for silence.

> In Java terms: the same way a Spring auto-configuration only activates when a matching class is on
> the classpath.

**2. Is the file one the plugin recognises?** It works on a configset's own files —
`managed-schema.xml`, `schema.xml`, `solrconfig.xml`, and the resource files an analyzer chain names.
An arbitrary XML file is not a configset file.

**If a configset does not light up, mark it by hand.** Right-click its directory in the Project view
and choose *Mark Directory as Solr Configset Root* — the way in for a bare configset repository with
no Solr client on its build, which the first gate turns away. **Settings → Languages & Frameworks →
Solr Configsets** lists every configset the plugin recognises, each saying whether detection found it
or someone marked it, and holds the switch that turns recognition off for the project. A marked root
is stored in `.idea/solr.xml`, so it reaches your teammates with the project — and a teammate who
receives one can remove it on that same page.

## Checking it actually works

Open a `managed-schema.xml` in a project that passes both gates. You should see, without doing
anything:

- **An inline hint beside each `<field>`** saying what that field can actually match — whole-value or
  tokenised, case-sensitive or not — read from its real analyzer chain rather than guessed from the
  type name.
- **F1 on a field name** explaining its resolved properties and where each value came from: the
  field, its type, or Solr's own default at your schema's declared version.
- **Ctrl-click on a `type` attribute** landing on the `<fieldType>` that declares it.

If those three work, everything else in [the user guide](user-guide.md) is working too.

## Uninstalling

<kbd>Settings</kbd> → <kbd>Plugins</kbd> → find **Solr Support** → <kbd>⚙️</kbd> →
<kbd>Uninstall</kbd>. It stores nothing outside the IDE's own configuration and touches no file in
your project unless you invoke a quick-fix or a rename.
