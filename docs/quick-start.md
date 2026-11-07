# Quick start

Ten minutes in the repository's demo project, which contains deliberate mistakes so the plugin has
something to report. Do not fix them.

!!! note "You build it first"
    The plugin is not on the Marketplace and has no release ZIP yet, so this starts from source. You
    need **JDK 21 or later** and nothing else; the build downloads the IDE it launches.

## 1. Open the demo in a sandbox IDE

```bash
git clone https://github.com/adityamparikh/solr-intellij-plugin.git
cd solr-intellij-plugin
./gradlew runIde
```

The first run is slow while it generates the class catalog from Solr's artifacts. It opens the
`demo/` project in a sandbox IntelliJ IDEA with the plugin installed.

Open `demo/solr/conf/managed-schema.xml` and give the IDE a minute to import the Gradle project. If
nothing below appears, see [why nothing is happening](installing.md#why-nothing-is-happening).

## 2. See what a field can match

Look at the `<field>` lines. Beside each one the plugin shows what the field can match and how it is
stored, for example *whole value, case-sensitive* for `category`, read from the field's analyzer
chain.

## 3. Ask where a value came from

Put the caret in `name="category"` and press <kbd>Ctrl-J</kbd> (Control on a Mac too). The popup
lists every property with its value and a **From** column: set on this field, set by its type, or
Solr's own default.

## 4. Get a typo caught

`<copyField source="manufacturer" …>` names a field that does not exist, and it is underlined. Press
<kbd>Alt-Enter</kbd> (<kbd>⌥⏎</kbd> on a Mac) on it: the quick-fix menu offers fields that do exist.
Solr would report this only when a core reloads.

## 5. Complete from Solr's own vocabulary

On the `category` field, put the caret after `stored="true"` and before the `/`, type a space, then
press <kbd>Ctrl-Space</kbd> (Control on a Mac too; macOS may claim it for switching input sources).
The popup offers the field properties Solr reads, each with a one-line summary. Undo the space.

## 6. Catch the same mistake in Java

Open `demo/src/main/java/com/example/demo/ProductSearch.java`. `findBooks` filters on
`categry:books`, a typo for `category`: it compiles and returns nothing at runtime. The plugin flags
the field name because no configset in the project declares it.

## 7. Compare against a server (optional)

The editor never contacts a server; the server features do, only when you ask. Start one:

```bash
docker compose -f demo/compose.yaml up -d      # Solr at http://localhost:8983
```

In the sandbox, open the **Solr** tool window, add a connection to `http://localhost:8983/solr`, and
expand `products`. Open the **Drift** tab, choose the `solr` configset and the `products` collection,
and press **Compare**. The server's configset differs from the project's on purpose, so you see one
row of each kind.

Press **Apply Additive Changes**. After you confirm, the plugin sends the one addition the comparison
found, the *Not deployed* `sku` field, through the Schema API, then reads the collection back and
compares again: the `sku` row is gone. The other rows stay, because Apply only ever adds; Solr would
accept a change to an existing field while documents already indexed keep their old encoding.

Stop the server with `docker compose -f demo/compose.yaml down -v`, which also discards the change.

Next: [the user guide](user-guide.md) covers every capability and the gesture that reaches it.
