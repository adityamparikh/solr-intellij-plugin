# Screenshot catalog

What each documentation image shows, the gesture that produces it, and what change makes it stale.
The gestures follow [the manual test suite](manual-test-suite.md), which is the authority on what a
feature should do; take captures during a manual pass, so an image is evidence the check passed.

## Conventions

**Light and dark, default font size.** Take every capture twice in the same session, named
`<name>-light.png` and `<name>-dark.png`, and embed the pair together:
`![alt](images/<name>-light.png#only-light)` then `![alt](images/<name>-dark.png#only-dark)`. The
site shows the one matching the reader's theme; GitHub shows both. Switch with **Switch…**
(<kbd>Ctrl-`</kbd>): *Theme* **Light with Light Header** with *Editor Color Scheme* **Light**, and
**Islands Dark** for both. The unsuffixed `<name>.png` files are older light-only captures.

**Crop to the feature.** Leave out the title bar, project name, branch chip and editor tabs that
name unrelated files: they date the image and leak whatever you happened to be working on. Prefer
gestures that do not show an absolute path, which is someone's home directory.

**Name the capability, not the gesture**: `hints-match-capability`, not `inlay-hover-demo`.
Kebab-case `.png` in `docs/images/`, with a two-digit prefix carrying the entry number below. A
before/after or annotated variant shares its entry's number.

**Annotate only where prose explains the markers.** Bake numbered markers into the pixels and put
every word of explanation in the Markdown beside the image, so a reshoot never invalidates the text.
An annotated image is a second file (`<name>-annotated`), never a replacement;
[how quick documentation works](faq.md) is the worked example.

**Every image carries alt text** that says what it shows, so a reader who cannot see it loses only
the illustration.

**[Dokka](glossary.md#dokka) needs absolute URLs.** `docs/Module.md` is a Dokka input, so images
there use `https://github.com/adityamparikh/solr-intellij-plugin/blob/main/docs/images/<name>.png`.

**Leave the demo clean.** The demo's defects are deliberate. Undo any typing, and after a gesture
that edits a file run `git checkout -- demo/` and confirm `git status --short demo/` is empty.

**Relaunch the sandbox after a catalog change.** A running `runIde` does not reload a regenerated
catalog, so it will reproduce the old popup exactly.

## The catalog

Start with `./gradlew runIde` and open [`demo/solr/conf/managed-schema.xml`](glossary.md#managed-schema).
Server entries need a running Solr (`docker compose -f demo/compose.yaml up -d`); code entries use
`demo/src/main/java/com/example/demo/ProductSearch.java`.

| # | Image | Shows, and how to get it | Redo when |
|---|---|---|---|
| 01 | `hints-match-capability` | The inline match and storage hints on the `<field>` block, no interaction. Frame all nine fields: `notes` carries only storage phrases and `legacy` none, and `legacy`'s underline belongs in frame. | Hint wording, a recognised capability, or the demo's fields change |
| 02 | `quick-doc-field` (+ `-annotated`) | Quick Documentation (<kbd>Ctrl-Q</kbd>; ⌃J on a Mac) in `name="category"`: each property's **Value** and **From**. Drag the popup wider and taller until every row is one line and `uninvertible` shows; it remembers the size. | The field property vocabulary or **From** wording changes |
| 03 | `quick-doc-class` (+ `-annotated`) | Quick Documentation on `solr.StandardTokenizerFactory`: kind, Lucene class, Javadoc summary, accepted attributes, Reference Guide link. The annotated file is framed wider for its margin markers. | The popup renders different catalog columns |
| 04 | `inspection-copyfield-quickfix` | <kbd>Alt-Enter</kbd> on the dangling `manufacturer` copyField: the closest-spelled names (the IDE lists them alphabetically). Expect `*_t`, `category`, `description`, `legacy`, `name`, `notes`: the nearest six; five names tie at distance 11 and the alphabetical tiebreak (`SolrInspections.replacementFixes`) keeps `*_t` and `description`. Only `manufacturer` and `legacy` may be underlined; any other underline is a false positive. | The fix ranking or the message changes |
| 05 | `completion-field-properties` | Caret after `stored="true"` on `category` and **before the `/`**, type a space: attribute completion without the attributes already present. A space after the `/` opens element completion instead. | The property vocabulary changes |
| 06 | `completion-factory-attributes` | A space before the `/` on `EdgeNGramFilterFactory`: `luceneMatchVersion` and `preserveOriginal`, read from constructor bytecode. | The popup renders different catalog columns |
| 07 | `find-usages-field-type` | <kbd>⌥F7</kbd> on the `text_general` declaration: `name`, `description`, `text` and `*_t`. | The demo's fields or the usage grouping change |
| 08 | `nav-solrconfig-field-reference` | In `solrconfig.xml`, Cmd-hover `name` in the `qf` parameter: it resolves to the schema field. | The demo's handler parameters or boost handling change |
| 09 | `nav-resource-file` | Quick Definition on `words="stopwords.txt"`. Not Cmd-hover, whose tooltip is an absolute path. | Resource attribute coverage changes |
| 10 | `completion-solrconfig-structure` | `<` on a blank line inside `<config>`: Solr's top-level elements, and not `cache`, `featureVectorCache` or `HashDocSet`. If those appear, the sandbox predates the catalog. | The element vocabulary or the demo's top-level children change |
| 11 | `dimmed-restated-default` | The field block untouched: `indexed="true"`/`stored="true"` dimmed, `stored="false"` at full strength. | The demo's attributes or the dimmed set change |
| 12 | `intention-companion-fields` | <kbd>Alt-Enter</kbd> on `description`: the exact-match and prefix companion intentions, optionally with the preview pane (<kbd>Ctrl-Q</kbd>; ⌃J on a Mac). Dismiss with Escape. | Intention wording changes, or `description` stops being tokenised |
| 13 | `completion-parameter-fields` | After `category` in the `qf` value, type a space, then <kbd>Ctrl-Space</kbd>: schema fields with their types, `*_t` included. | The demo's fields change |
| 14 | `rename-cross-file-before` / `-after` | <kbd>Shift-F6</kbd> on `category` to `product_category`, applied directly: the `qf` line in `solrconfig.xml` before and after. One <kbd>Cmd-Z</kbd> undoes both files. | The demo's `qf` or `category` changes |
| 15 | `completion-parameter-names` | A new `<str name="` in the `/select` defaults, type `sort`: Solr's parameter names with their declaring class. A parameter the list already sets is withheld. | The parameter catalog changes |
| 16 | `hover-schema-version` | Quick Documentation on `version` in `<schema version="1.6">`: what the attribute decides, then what `1.6` decides. On the legacy *Mac OS X* keymap F1 opens Help; use ⌃J. | The demo's version or the version-dependent properties change |
| 17 | `completion-query-nesting` | Type `<query></query>` inside `<config>`, `<` between the tags: what Solr reads there, without `dataDir`. Revert the file. | The element vocabulary changes |
| 18 | `completion-indexconfig-nesting` | As 17 with `<indexConfig>`: only `deletionPolicy`; discontinued `nrtMode` and `unlockOnStartup` are never offered. | Entry 17 is redone |
| 19 | `drift-three-states` | **Drift** tab, **Compare**: *Not deployed*, *Only on server* and *Differs* rows with the summary above. Select a *Differs* row and narrow the State, Kind and Name columns so both definition cells read. The demo's own configset cannot be deployed. | State labels, columns or the summary change |
| 20 | `drift-refused-payload` | A *Differs* row selected: the refusal reason above the `replace-field` request, and **Apply Additive Changes** disabled in the toolbar. | The refusal wording or the payload pane changes |
| 21 | `drift-apply-before` / `-after` | A *Not deployed* row with its `add-field` payload and Apply enabled; then, after confirming, the re-read comparison without it. | The summary wording or the apply flow changes |
| 22 | `connections-settings` | Settings → Tools → Solr Connections, editing a connection with a saved password: the empty field and the text saying a password is stored. | The field layout or stored-password wording changes |
| 23 | `collections-topology` | The **Solr** tool window on SolrCloud: a collection to its shards and replicas, leader marked, health dots, the connection selector. | Row detail, the toolbar, health colours or the icon change |
| 24 | `luke-fields` | A collection's **Fields** expanded after indexing an undeclared `author_s` and a `price_f`: the `← *_s` marker, and no document count on the point field. | The detail format or the pattern marker changes |
| 25 | `query-and-results` | An `.http` Solr request run from *Explain why documents scored*: the summary line (matches vs returned), the table, the start of the explanation. Use a query matching more than it returns. | The summary, table columns or explanation rendering change |
| 26 | `code-field-warning` | `findBooks`, no interaction: `categry` and `price` highlighted up to the colon and no further, `id` and `name` left alone. Crop tight enough to see where each highlight ends. | The severity, the demo's fields, `ProductSearch.java` or the warning style changes |
| 27 | `code-query-colour` | `setQuery("category:books AND name:solr")`: fields and `AND` coloured apart from the string. Keep the caret out of frame. | The query scan, its operators, or the default palette changes |
| 28 | `code-navigate-to-schema` | <kbd>Ctrl-click</kbd> `category` in `findSolrBooks`, then split right: Java left, the schema's `<field>` right, both tab rows in frame. | The demo's field block, layout or the reference changes |
| 29 | `code-run-from-gutter` | The gutter icon beside `setQuery` and the collection chooser it opens — at the editor's top-right, so frame both corners. | The popup's anchor or title, or the icon's call, changes |
| 30 | `completion-live-schema` | <kbd>Ctrl-Space</kbd> (not a letter) in an `.http` body's `"fields": [""]`: the collection's fields, each labelled with the collection and connection, such as `books · local`. Typing opens a configset-backed popup instead. | The source label or renderer changes |
| 31 | `index-test-document` | **Index a Test Document** on a collection: the dialog as it opens, every field filled from the schema except Solr's own fields, copy-field destinations and fields that keep nothing. Press **Cancel**. | The validation wording, commit-mode chooser or starting document changes |
| 32 | `code-completion-popup` | Type `de` after `AND ` in `findSolrBooks`: the popup opens by itself offering `description`, no dynamic patterns. Undo the typing. | The popup's contents or positions change |

**Known stale:** `23-collections-topology-light.png` shows the drift view rather than the collections
tree. The unsuffixed `23-collections-topology.png` still shows the shard's hash range, which has since
moved to the row's tooltip; slide 46 of the deck embeds it.

**Across the board:** a change to `ProductSearch.java` invalidates 26–29, since each is a crop of
it; a change to `supportedSolrLines` invalidates 02 and 03, whose Reference Guide link names the
version; an IntelliJ platform update that restyles popups, completion or inlays invalidates all of
them, with no plugin change at all.

## Not yet capturable

Images worth having that the catalog does not hold yet:

- *Mark Directory as Solr Configset Root*, and the Solr Configsets settings page with one marked and
  one detected row side by side ([ACT-3 to ACT-5](manual-test-suite.md#1-activation-act)).
- Quick documentation on a factory attribute, on a factory's complete configuration, and on an
  attribute's own name ([DOC-7, DOC-4b, DOC-8](manual-test-suite.md#5-quick-documentation-doc)).
- Attribute completion inside a cache tag such as `<filterCache>`
  ([STR-5](manual-test-suite.md#10-completion--solrconfigxmls-own-structure-str)).
- The analyzer-chain ordering, discontinued-element and misspelled-parameter inspections
  ([INSP-7, INSP-13, INSP-14](manual-test-suite.md#6-inspections-and-quick-fixes-insp)).
- Class navigation landing in a resolvable class — needs `solr-core` in the demo's dependencies
  ([NAV-9](manual-test-suite.md#4-navigation-and-find-usages-nav)).
- The tool window's failure banner when a server is down; the right-click queries on a collection
  or field; Upload and Reload's confirmation, which needs a deployable configset
  ([SRV-5, SRV-10a, SRV-22](manual-test-suite.md#13-connections-and-the-collections-tool-window-srv)).
- The spelling quick-fix in code, the *no connection* message, the Kotlin version of the demo, and
  silence in a module with no Solr client
  ([CODE-2, CODE-11, CODE-12, CODE-13](manual-test-suite.md#14-field-names-and-queries-in-java-and-kotlin-code)).
