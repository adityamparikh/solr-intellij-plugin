# Manual test checklist

> **Who this is for.** Someone pressing every shipped feature in a sandbox IDE in one sitting, who
> wants one line per check and will report back by ID.
> **Read first:** [the manual test suite](manual-test-suite.md), which owns each check's full
> gesture, its reasoning, and the pass log. This page is its quick form, and when the two
> disagree the suite is right.

## Before you start

- **Launch the sandbox** with `./gradlew runIde`. It opens `demo/`.
- **License it**, or the server checks fail silently. Use Help → Register, or copy `idea.key` into
  the sandbox's config. Unlicensed, the HTTP Client inserts and highlights requests, but **Run does
  nothing** — no error, no dialog. The top bar reading *Unlock Ultimate* is the tell.
- **Function keys on a Mac.** The shortcuts below are IntelliJ's defaults, and a Mac sends F1–F12 as
  media keys unless **fn** is held. Hold fn, or use the action by name: <kbd>⇧⇧</kbd> (Search
  Everywhere) → *Quick Documentation*, *Find Usages*, *Rename*. Hovering also opens quick
  documentation.
- **The server checks need a Solr**, and the ones that write need a collection you can spare.
  `docker compose -f demo/compose.yaml up -d` starts the demo's, with a `products` collection that
  already drifts from `solr/conf` one row of each kind; *Index the sample products* in `queries.http`
  fills it. SRV-8, SRV-9 and SRV-23 want `docker run -p 8983:8983 solr:10.0.0 solr-precreate books`
  instead, with the demo's stopped. Never point the writing checks — SRV-22, 24, 26 and 28–31 — at a
  collection you care about.
- **Close the sandbox, never kill it.** A hard kill can disable the plugin at the next start.
- **Every edit ends in Undo**, so the demo is back at its baseline for the next check.

**Reporting:** the ID, pass or fail, and one line of what you saw when it failed. A screenshot
helps for anything visual.

## Editor track

No server needed.

### [Activation](manual-test-suite.md#1-activation-act)
- [ ] **ACT-1** — Opening `demo/` shows inlay hints in `solr/conf/managed-schema.xml`.
- [ ] **ACT-2** — Right after launch, while indexing is still running, hints and hovers already work.
- [ ] **ACT-3** — Settings → Languages & Frameworks → **Solr Configsets** reads
  `solr  detected  solr/conf`, with remove disabled.
- [ ] **ACT-4** — A folder holding a lone `schema.xml` outside any `conf/`: right-click → *Mark
  Directory as Solr Configset Root* lights the file up with no restart, and the page lists it as
  *marked*. *Unmark* returns it to plain XML, hints included.
- [ ] **ACT-5** — Untick *Recognise Solr configsets* and apply: everything goes quiet, marked
  folders included. Tick it again.

### [Zero false positives](manual-test-suite.md#2-zero-false-positive-baseline-base)
Count the Problems tool window's rows that begin `Solr:`, not the underlines.
- [ ] **BASE-1** — Untouched `managed-schema.xml`: exactly **2** — the `manufacturer` copyField and
  `legacy`'s `type="discontinued"`.
- [ ] **BASE-2** — Untouched `solrconfig.xml`: **0**.

### [Inlay hints](manual-test-suite.md#3-match-capability-inlay-hints-hint)
- [ ] **HINT-1** — `id`, `sku`, `category`: whole value, case-sensitive,
  `indexed, stored, doc values, single-valued`.
- [ ] **HINT-2** — `description`, `text`: tokenised, case-insensitive, no prefix support,
  `no doc values`; `description` adds `stored, single-valued`, `text` adds `not stored, multi-valued`.
- [ ] **HINT-3** — `name_prefix`: prefix-capable, `indexed, not stored, no doc values, single-valued`.
- [ ] **HINT-4** — Hints sit inline without hovering, and are readable.
- [ ] **HINT-5** — `notes` shows the storage phrases and no match claim; `legacy` shows no hint.

### [Navigation and Find Usages](manual-test-suite.md#4-navigation-and-find-usages-nav)
- [ ] **NAV-1** — <kbd>⌘</kbd>-click `type="text_general"` lands on its `<fieldType>`.
- [ ] **NAV-2** — <kbd>⌘</kbd>-click a copyField's `source` and `dest` land on each field.
- [ ] **NAV-3** — *Find Usages* (<kbd>⌥F7</kbd>) on `type="text_general"`, and on the
  `<fieldType name="text_general">` declaration, list the same fields.
- [ ] **NAV-4** — In `solrconfig.xml`, <kbd>⌘</kbd>-click each name in `qf` lands in the schema, and
  *Find Usages* from the field lists the parameter.
- [ ] **NAV-5** — <kbd>⌘</kbd>-click `words="stopwords.txt"` opens the file.
- [ ] **NAV-6** — *Find Usages* on `<dynamicField name="*_t">` finds `body_t` in `pf`, highlighted
  at `body_t`.
- [ ] **NAV-7** — Find Usages headers read **Field type** and **Dynamic field**; groups read
  **Field declaring this type** and **Handler parameter in solrconfig.xml**. Never *Unclassified*.
- [ ] **NAV-8** — *Find Usages* on `stopwords.txt` groups under **Analyzer component reading this
  file**.
- [ ] **NAV-9** — <kbd>⌘</kbd>-click `class="solr.SearchHandler"` resolves nowhere, **and shows no
  warning**.
- [ ] **NAV-10** — *Quick Documentation* on that value during indexing still answers. *File →
  Invalidate Caches → Just Restart* gives an indexing window.

### [Rename](manual-test-suite.md#4a-rename-ren)
Undo after each.
- [ ] **REN-1** — *Rename* (<kbd>⇧F6</kbd>) on `category`: the dialog says *Rename **field**
  'category'*.
- [ ] **REN-2** — Rename it to `product_category`: the declaration and the `qf` line in
  `solrconfig.xml` both change.
- [ ] **REN-3** — Rename `text_general` from its declaration: every `type=` follows, `*_t`'s
  included.
- [ ] **REN-4** — Rename `*_t` to `*_txt`: `body_t` in `pf` is left exactly as written.
- [ ] **REN-5** — In that state `body_t` is underlined as unknown; undoing clears it.

### [Quick documentation](manual-test-suite.md#5-quick-documentation-doc)
*Quick Documentation* is <kbd>F1</kbd>, or hover.
- [ ] **DOC-1** — On a field's `type` value: the type, its analyzer chain, what it can match.
- [ ] **DOC-2** — The Reference Guide link names the targeted version, and opens.
- [ ] **DOC-3** — The tags `<schema> <field> <fieldType> <dynamicField> <copyField> <uniqueKey>`
  each answer with a sentence about this configset.
- [ ] **DOC-4** — `class="solr.WordDelimiterGraphFilterFactory"`: its kind, both spellings, its
  attributes, how the configset uses it, a guide link, a one-line Javadoc summary.
- [ ] **DOC-4b** — The EdgeNGram `filter` tag shows a **Configuration** table: written attributes
  bold, *on this filter*; `preserveOriginal` **false**, *Solr default*; `luceneMatchVersion` an em
  dash, *no default recorded*.
- [ ] **DOC-5** — At `version="1.6"`, `uninvertible` reads **true**, from the schema version's
  default.
- [ ] **DOC-6** — At `1.7` it reads **false**, and the origins say 1.7. Undo.
- [ ] **DOC-7** — `minGramSize`: owner, *a whole number*, required, a **Does** row;
  `preserveOriginal`: default `false`.
- [ ] **DOC-8** — Attribute *names* explain themselves: `name`/`type` on `<field>`, `name`/`class`
  on `<fieldType>`, `source`/`dest` on `<copyField>`.
- [ ] **DOC-9** — `version` on `<schema>`: two paragraphs, the second saying what `1.6` decides
  here; it changes at 1.7.
- [ ] **DOC-10** — An attribute the hand-written table does not cover: **no Does row**.

### [Inspections and quick-fixes](manual-test-suite.md#6-inspections-and-quick-fixes-insp)
Undo after each. A finding is **underlined**; the fix is behind <kbd>⌥↩</kbd>.
- [ ] **INSP-1** — A copyField `dest` naming no field: underlined; the fix offers fields, closest
  first.
- [ ] **INSP-2** — A bogus field `type`: underlined; the fix offers the declared types.
- [ ] **INSP-3** — Deleting the `name` field flags its copy rule at once.
- [ ] **INSP-4** — `qf` or `df` naming an undeclared field: underlined.
- [ ] **INSP-5** — A made-up attribute on `<field>`: underlined, naming the element.
- [ ] **INSP-6** — `indexed="yes"`: underlined.
- [ ] **INSP-7** — In `text_prefix`'s index chain: WordDelimiterGraph with `splitOnCaseChange="1"`
  below LowerCase is flagged; moved above, it clears; FlattenGraph above it is flagged.
- [ ] **INSP-8** — `name_prefix` switched to `text_general`: the `text_prefix` declaration goes
  **dim**, not underlined.
- [ ] **INSP-10** — `<str name="sort">text asc</str>`: flagged, saying what sorting needs.
- [ ] **INSP-11** — `facet.field` → `category`: not flagged at 1.6 or 1.7. With
  `docValues="false"` on `category`, flagged.
- [ ] **INSP-12** — In that state, `qf` → `category` is clean while `facet.field` is flagged.
- [ ] **INSP-13** — `<indexConfig><nrtMode>`: Solr's own discontinued sentence. `<indexDefaults>`
  names `<indexConfig>`. Inside `<acmeThing>`: nothing.
- [ ] **INSP-13b** — `<nrtMode>` directly under `<config>`: **no** warning.
- [ ] **INSP-14** — `rwos`: flagged, the fix offers `rows`; `pf2` and `pf3`: not flagged.
- [ ] **INSP-15** — `name` `indexed="false"`: only `name` is underlined in `qf`, and
  `solrconfig.xml` has exactly 1 problem.
- [ ] **INSP-9** — Everything undone: the counts are back to 2 and 0.

### [Completion — the schema's vocabulary](manual-test-suite.md#7-completion--the-schemas-own-vocabulary-comp)
- [ ] **COMP-1** — `<` inside `<schema>` offers only the elements legal there.
- [ ] **COMP-2** — Attributes on `<field ` come with summaries, omitting those already written.
- [ ] **COMP-3** — `<fieldType>`'s general properties complete, documented.
- [ ] **COMP-4** — The default boolean is marked: `indexed` → true, `multiValued` → false.
- [ ] **COMP-5** — `type=` offers the declared types; copyField ends offer fields;
  `<analyzer type=` offers `index` and `query`.
- [ ] **COMP-6** — `uninvertible=` marks **true** at 1.6 and **false** at 1.7. Undo.

### [Completion — the catalog](manual-test-suite.md#8-completion--catalog-backed-cat)
- [ ] **CAT-1** — `class=` offers field-type classes on `<fieldType>`, factories on tokenizers and
  filters.
- [ ] **CAT-2** — On a WordDelimiterGraph filter: `generateWordParts`, `catenateAll`,
  `splitOnCaseChange`.
- [ ] **CAT-3** — `<requestHandler class=` offers `solr.SearchHandler` and
  `solr.UpdateRequestHandler`, no codecs or field types.
- [ ] **CAT-4** — *Quick Documentation* on `solr.SearchHandler` says *request handler*, and its
  guide link lands on a real page.

### [Completion — `solrconfig.xml` parameters](manual-test-suite.md#9-completion--field-names-inside-solrconfigxml-parameters-prm)
- [ ] **PRM-1** — Inside `qf`: the schema's fields.
- [ ] **PRM-2** — Inside `rows`: no field names.
- [ ] **PRM-3** — Right after the `^` in `name^3`: no field.
- [ ] **PRM-10** — *Quick Documentation* inside `^3` explains the boost and `name`'s searchability.
- [ ] **PRM-11** — Inside `bf`'s `^2.5`: an additive function boost, naming no field.
- [ ] **PRM-4** — In `sort`: fields at a clause's start, nothing after `text `.
- [ ] **PRM-6** — A `<str name="` inside `defaults` offers request parameters, each described.
- [ ] **PRM-7** — A parameter already set is not offered again; `defType` is.
- [ ] **PRM-8** — `defType` offers `edismax`, `dismax`, `lucene`, `func`, and no class names.
- [ ] **PRM-9** — *Quick Documentation* on `qf` names `DisMaxParams`; on `edismax`,
  `ExtendedDismaxQParserPlugin`; on `my.own.param`, nothing.
- [ ] **PRM-5** — *Quick Documentation* on a field name inside `qf` shows the field's own doc.

### [Completion — `solrconfig.xml` structure](manual-test-suite.md#10-completion--solrconfigxmls-own-structure-str)
- [ ] **STR-1** — `<` directly inside `<config>`: Solr's top-level vocabulary, not a sibling echo.
- [ ] **STR-2** — Inside a `<query>` you type: `filterCache` offered, `dataDir` not.
- [ ] **STR-3** — `nrtMode` is never offered.
- [ ] **STR-4** — Inside `<acmeThing>`: nothing offered, nothing flagged.
- [ ] **STR-5** — `<filterCache>` attributes: `autowarmCount class enabled initialSize maxRamMB
  regenerator size`; a made-up one is not flagged.
- [ ] **STR-6** — `<requestHandler>` attribute completion offers nothing, correctly.

### [Restated defaults](manual-test-suite.md#11-an-attribute-that-restates-its-default-dim)
- [ ] **DIM-1** — `indexed="true"` and `stored="true"` are **greyed, not underlined**, and absent
  from the Problems view; `stored="false"` is not greyed.
- [ ] **DIM-2** — <kbd>⌥↩</kbd> on one removes it; the field's documentation is unchanged. Undo.
- [ ] **DIM-3** — `indexed="false"` is not greyed. Undo.
- [ ] **DIM-4** — `enableGraphQueries="true"` on a `<field>`: not greyed; on a `<fieldType>`:
  greyed. Undo.

### [Intentions](manual-test-suite.md#12-intentions--companion-fields-int)
- [ ] **INT-1** — <kbd>⌥↩</kbd> on a tokenised field adds an exact-match companion field and type;
  the file still parses.
- [ ] **INT-2** — The same field no longer offers it, and a `string` field never did. Undo.

## Server track

The sandbox must be licensed; see [Before you start](#before-you-start).

### [Connections and the tool window](manual-test-suite.md#13-connections-and-the-collections-tool-window-srv)
- [ ] **SRV-1** — Settings → Tools → **Solr Connections**: `+` opens a form, saving adds a row.
- [ ] **SRV-2** — A URL with no scheme is refused, naming the field; a path other than `/solr` is
  accepted.
- [ ] **SRV-3** — Reopening a connection with a stored password: the field is empty and says one
  is stored; after a rename, it still is.
- [ ] **SRV-4** — The **Solr** tool window lists what the server holds: cores, or collections →
  shards → replicas.
- [ ] **SRV-5** — Solr stopped, Refresh: one inline error, the tree empties, no popup.
- [ ] **SRV-6** — Solr started, Refresh: it repopulates, and never refetches on its own.
- [ ] **SRV-7** — With two connections, switching re-reads, and the choice survives a reopen.
- [ ] **SRV-8** — A document carrying `author_s` and `price_f`: the **Fields** row shows
  `author_s ← *_s`.
- [ ] **SRV-9** — `price_f` has no document count, `author_s` has one; `_root_` reads
  `(unstored field)`.
- [ ] **SRV-10** — Collapsing and expanding **Fields** does not reach the server; only Refresh does.
- [ ] **SRV-10a** — Right-clicking a collection or field offers queries it can answer; choosing one
  opens a scratch `.http` request that runs only when you press run, and holds no password.

### [Queries in the HTTP Client](manual-test-suite.md#13-connections-and-the-collections-tool-window-srv)
- [ ] **SRV-11** — In an `.http` file, *Add Request* → **Solr** offers five requests with proper
  labels.
- [ ] **SRV-12** — With `http-client.env.json`'s `local` environment chosen, a request runs, and
  the `.http` file names no host.
- [ ] **SRV-13** — The response shows matches, time and an aligned table; `_version_` is left out,
  and the table says so.
- [ ] **SRV-14** — *Explain why documents scored* keeps Solr's indentation.
- [ ] **SRV-15** — A request to something that is not Solr shows nothing from this plugin.
- [ ] **SRV-16** — With **no connections**, completion in `"fields"` offers configset fields with
  their type and configset.
- [ ] **SRV-16b** — With a connection, <kbd>⌃Space</kbd> offers the collection's own fields,
  reading `<collection> · <connection>`; at once the second time.
- [ ] **SRV-16c** — Typing a letter opens the popup with configset fields, and the server sees no
  `/schema` request.
- [ ] **SRV-17** — Fields are offered in `"sort"` and a facet's `"field"`; not in `"limit"` or
  `"query"`.
- [ ] **SRV-18** — Another JSON file offers no Solr fields.

### [Drift, upload and apply](manual-test-suite.md#13-connections-and-the-collections-tool-window-srv)
The writing checks go to a collection you can spare.
- [ ] **SRV-19a** — Restarted with the Drift tab open, its configset list fills once indexing ends.
- [ ] **SRV-19** — Compare shows *Not deployed*, *Only on server* and *Differs* with **both**
  definitions, and the summary names the Solr line, e.g. *Solr 10.0*.
- [ ] **SRV-20** — Against a collection made from the configset: an empty table, *agree across N
  declarations*.
- [ ] **SRV-21** — Solr stopped, Compare: an error, and **no** *Not deployed* rows.
- [ ] **SRV-22** — **Upload and Reload** confirms, naming the configset, collection **and server**;
  Cancel sends nothing; confirming re-reads and clears the drift.
- [ ] **SRV-23** — Against a standalone Solr, upload refuses and sends nothing.
- [ ] **SRV-24** — With `_version_` deleted from the configset, the upload succeeds and drift
  **still shows**.
- [ ] **SRV-25** — A *Differs* row: the reason first, then `replace-field`; **Apply Additive
  Changes** disabled.
- [ ] **SRV-26** — A *Not deployed* row: Apply confirms with the count, collection and server; the
  row goes, and the summary counts agreement.
- [ ] **SRV-27** — With only *Differs* rows, Apply is disabled.

### [Indexing a test document](manual-test-suite.md#13-connections-and-the-collections-tool-window-srv)
- [ ] **SRV-28** — **Index a Test Document** names the collection and server in its title, and
  opens on a document that reports no problems.
- [ ] **SRV-29** — A misspelt field is reported, and OK is refused.
- [ ] **SRV-30** — Without `id`: refused. `"author_s": "x"`: accepted. `"_version_": 1`: a warning,
  and OK still works.
- [ ] **SRV-31** — With the default commit, the document appears a second later; with *findable
  immediately*, at once.

## Code track

The subject is `demo/src/main/java/com/example/demo/ProductSearch.java`.

### [Field names and queries in Java and Kotlin](manual-test-suite.md#14-field-names-and-queries-in-java-and-kotlin-code)
- [ ] **CODE-1** — In `findBooks`, `categry` in `addFilterQuery("categry:books")` is underlined,
  **the name only**.
- [ ] **CODE-2** — Its fix offers `category`, leaving the rest of the string alone. Undo.
- [ ] **CODE-3** — `price` in `setFields("id,name,price")` is flagged.
- [ ] **CODE-4** — Nothing in `findSolrBooks` is flagged.
- [ ] **CODE-5** — There, `category` and `name` are coloured as fields and `AND` as an operator;
  lowercase `and` loses the colour.
- [ ] **CODE-6** — Completion inside `addFilterQuery("` offers the configset's fields, with type and
  configset.
- [ ] **CODE-7** — <kbd>⌘</kbd>-click `category` in `findSolrBooks` lands on
  `<field name="category">`.
- [ ] **CODE-8** — <kbd>⌘</kbd>-click `categry` resolves nowhere.
- [ ] **CODE-9** — A gutter icon beside `setQuery` in `findSolrBooks`, and beside nothing else.
- [ ] **CODE-10** — With a connection, clicking it lists collections, runs against the chosen one,
  and renders the answer; the chooser opens at the editor's top-right.
- [ ] **CODE-11** — With no connection, it says so and sends nothing.
- [ ] **CODE-12** — A Kotlin file using SolrJ behaves the same.
- [ ] **CODE-13** — A Java file in a module without a Solr client: no colour, icon, warning or
  completion.
