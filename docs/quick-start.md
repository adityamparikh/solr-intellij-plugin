# Quick start

Ten minutes, one project, and every claim here is something you can see for yourself. It uses the
repository's own demo project, which is built with deliberate mistakes so the plugin has something
true to say. Do not fix them.

!!! note "You build it first"
    The plugin is not on the Marketplace and has no release ZIP yet, so this starts from source. You
    need **JDK 21 or later** and nothing else; the IDE it launches is downloaded by the build.

## 1. Open the demo in a sandbox IDE

```bash
git clone https://github.com/adityamparikh/solr-intellij-plugin.git
cd solr-intellij-plugin
./gradlew runIde
```

The first run is slow, because it resolves Solr's artifacts to generate the class catalog. It opens
the `demo/` project in a sandbox IntelliJ IDEA with the plugin already installed, so there is no
separate install step. To put the plugin in your own IDE instead, see [Installing](installing.md).

Open `demo/solr/conf/managed-schema.xml` and give the IDE a minute to import the Gradle project.
If nothing below appears, [why nothing is happening](installing.md#why-nothing-is-happening)
explains the two gates.

## 2. See what a field can match

Look at the `<field>` lines. Beside each one the plugin shows, inline, what the field can actually
match and how it is stored, for example *whole value, case-sensitive* for `category`. It reads the
field's real analyzer chain; it does not guess from the type's name.

## 3. Ask where a value came from

Put the caret in `name="category"` on line 70 and press <kbd>Ctrl-J</kbd> (<kbd>F1</kbd> on some
keymaps). The popup lists every property with its value and **From** column: set on this field, set
by its type, or Solr's own default. This is the one thing the Reference Guide cannot tell you.

## 4. Get a typo caught

Line 92 is `<copyField source="manufacturer" …>`, and no field called `manufacturer` exists. It is
underlined. Press <kbd>Alt-Enter</kbd> on it for the quick-fix menu, which offers the fields that do
exist. A `copyField` like this fails only when a core reloads, so catching it in the editor moves the
discovery to the moment the file is open.

## 5. Complete from Solr's own vocabulary

On line 70, put the caret after `stored="true"` and before the `/`, type a space, then press
<kbd>Ctrl-Space</kbd>. The popup offers the field properties Solr reads, each with a one-line summary.
Undo the space afterwards.

## 6. Catch the same mistake in Java

Open `demo/src/main/java/com/example/demo/ProductSearch.java`. Line 45 filters on `categry:books`, a
typo for `category`. It compiles and returns nothing at runtime; the plugin flags the field name
because no configset in the project declares it.

## 7. Talk to a server (optional)

The editor never contacts a server. The server features do, and only when you ask. To try them:

```bash
docker compose -f demo/compose.yaml up -d      # Solr at http://localhost:8983
```

In the sandbox, open the **Solr** tool window, add a connection to `http://localhost:8983/solr`, and
expand `products`. Then open the **Drift** tab and press **Compare**: the demo's server configset
differs from the project's on purpose, so you see one row of each kind. Stop it with
`docker compose -f demo/compose.yaml down -v`.

## Where next

| You want to | Read |
|---|---|
| Every capability and the gesture that reaches it | [User guide](user-guide.md) |
| What each inspection reports, and what it ignores | [Inspection catalog](inspection-catalog.md) |
| Why something is silent | [Installing](installing.md#why-nothing-is-happening) |
| Change the plugin | [Contributing](contributing.md) |
