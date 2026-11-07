# User guide

Every capability the plugin has, organised by what you are trying to do: the gesture that reaches
it, and what you get. It covers the three places you work — the **configuration files** you edit,
the **server** you talk to, and the **code** that names fields, where SolrJ is what is recognised.
Solr terms are linked to the [glossary](glossary.md).

To press each gesture against a real project, start with the [quick start](quick-start.md), which
uses the committed demo. If nothing happens at all, see
[why nothing is happening](installing.md#why-nothing-is-happening). Short clips of most capabilities
are on [the home page](index.md#see-it-in-action).

## What the plugin can do

Each row is a capability, the gesture that reaches it, and the checks in
[the manual test suite](manual-test-suite.md) that verify it by hand.

| Capability | Gesture | Verified by |
|---|---|---|
| [Activation](installing.md#why-nothing-is-happening) | Open a configset file | `ACT`, `BASE` |
| [Which configsets are recognised](installing.md#why-nothing-is-happening) | Settings → Languages & Frameworks → Solr Configsets; *Mark Directory as Solr Configset Root* | `ACT-3`–`ACT-5` |
| [What a field can match](#what-can-this-field-actually-match) | Inlay hint beside a field | `HINT` |
| [Quick documentation](#getting-documentation-without-leaving-the-file) | Hover, or <kbd>Ctrl-Q</kbd> | `DOC` |
| [Completion: schema vocabulary](#the-schemas-own-vocabulary) | <kbd>Ctrl-Space</kbd> in `managed-schema.xml` | `COMP` |
| [Completion: catalog-backed classes](#catalog-backed-classes-and-factory-attributes) | <kbd>Ctrl-Space</kbd> in a `class` attribute | `CAT` |
| [Completion: `solrconfig.xml` structure](#solrconfigxmls-own-structure) | <kbd>Ctrl-Space</kbd> in `solrconfig.xml` | `STR` |
| [Completion: fields in parameters](#field-names-inside-solrconfigxml-parameters) | <kbd>Ctrl-Space</kbd> inside `fl`, `qf`, `pf` | `PRM` |
| [Navigation and Find Usages](#navigating-and-finding-usages) | <kbd>Ctrl-click</kbd>, <kbd>Alt-F7</kbd> | `NAV` |
| [Rename](#renaming-safely-across-a-configset) | <kbd>Shift-F6</kbd> on a field | `REN` |
| [Inspections and quick-fixes](#catching-mistakes-before-solr-does) | Editing anything wrong; <kbd>Alt-Enter</kbd> | `INSP` |
| [Restated defaults](#seeing-whats-already-redundant) | An attribute set to Solr's own default | `DIM` |
| [Companion-field intentions](#fixing-a-missing-capability-automatically) | <kbd>Alt-Enter</kbd> on a field | `INT` |
| [Connections](#pointing-the-plugin-at-a-solr-server) | Settings → Tools → Solr Connections | `SRV-1`–`SRV-3` |
| [Browsing a server](#browsing-what-a-server-holds) | The **Solr** tool window | `SRV-4`–`SRV-10` |
| [Running a query](#running-a-query) | An `.http` file, *Add Request* → Solr; or right-click a row in the **Solr** tool window | `SRV-10a`, `SRV-11`–`SRV-18` |
| [Drift, upload and reload](#closing-a-difference-between-the-repository-and-a-server) | The **Drift** tab | `SRV-19`–`SRV-27` |
| [Indexing a test document](#indexing-a-test-document) | *Index a Test Document* | `SRV-28`–`SRV-31` |
| [Field names checked in code](#catching-a-field-name-typo-in-java-or-kotlin) | Editing Java or Kotlin | — |
| [Field names completed in code](#completing-a-field-name-in-java-or-kotlin) | Typing a field name in a Solr call | — |
| [Navigating from code to the schema](#from-a-field-name-in-code-to-its-declaration) | <kbd>Ctrl-click</kbd> a field name in code | — |
| [Query structure in code](#seeing-the-shape-of-a-query-written-in-code) | Editing a Solr query in a string | — |
| [Running a query from code](#running-a-query-without-leaving-the-file) | The gutter icon beside a query | — |

Nothing on the editing path contacts a server; the server capabilities move data only when you ask.

### Function keys on a Mac

macOS sends media keys from the top row unless you hold fn (or turn on *Use F1, F2, etc. keys as
standard function keys*), so Find Usages is fn-⌥F7 and Rename fn-⇧F6. On the legacy *Mac OS X* keymap
F1 opens Help, so use ⌃J or hover for Quick Documentation; Find Action (⇧⌘A) reaches any of them by name.

---

## What can this field actually match?

**Gesture:** none — open a schema; the hints render beside every [field](glossary.md#field).

**You get:** what a search against the field can do — whole-value or tokenised, case-sensitive or
not, prefix-capable or not — and its storage shape: [indexed](glossary.md#indexed),
[stored](glossary.md#stored), doc-valued, single- or [multi-valued](glossary.md#multivalued). It is
read from the field's own [analyzer chain](glossary.md#analyzer-chain), in order, not from the type's
class name: a `TextField` with `KeywordTokenizerFactory` reads whole-value, and a
`WordDelimiterFilterFactory` after that tokenizer makes it tokenised again, with the hint naming the
filter responsible.

**Silent when:** the chain names a factory the plugin does not recognise (storage shape only, no match
claim), or the field's `type` names nothing the schema declares (no hint at all).

![Nine field declarations in managed-schema.xml, each followed by an inline hint naming what it matches and how it is stored](images/01-hints-match-capability-light.png#only-light)
![Nine field declarations in managed-schema.xml, each followed by an inline hint naming what it matches and how it is stored](images/01-hints-match-capability-dark.png#only-dark)

---

## Getting documentation without leaving the file

**Gesture:** hover, or Quick Documentation (<kbd>Ctrl-Q</kbd>; ⌃J on a Mac). What it answers depends
on where the caret sits:

| Caret on | You get |
|---|---|
| A field's `name` | Every resolved property, its value, **where it came from** — the field, its [field type](glossary.md#field-type), or Solr's default at the schema's version — and a **Meaning** column stating the consequence |
| A `class="solr.…"` value | Short name and kind, fully-qualified name, a one-sentence summary from Solr's Javadoc, accepted attributes, and a Reference Guide link at the configset's version — read at build time, never fetched ([why link, not copy](faq.md)) |
| A schema element's tag (`<field>`, [`<copyField>`](glossary.md#copyfield), [`<uniqueKey>`](glossary.md#uniquekey)…) | What the element is, plus a configset-specific sentence where one is knowable: which fields a `<copyField>` joins, which field is the unique key, how many fields use a type |
| An attribute's name (`type`, `source`, `dest`…) | What that attribute means in general, as distinct from what this configset did with its value |
| An attribute on a `<filter>`, `<tokenizer>` or `<charFilter>` | The owning class, the value type, whether it is required, and a **Does** row for commonly hovered attributes |
| A factory's tag (the word `filter`, not its `class`) | Every attribute the class accepts: written ones bold, unwritten ones with Solr's recorded default, and an em dash where none is recorded ([`luceneMatchVersion`](glossary.md#lucenematchversion)) |
| `version` on `<schema>` | What the [schema version](glossary.md#schema-version) decides, then what this configset's value decides — at `1.6`, [`docValues`](glossary.md#docvalues) off and [`uninvertible`](glossary.md#uninvertible) on, both of which flip at 1.7 |
| A `solrconfig.xml` [request parameter](glossary.md#request-parameter) name, or a `defType` value | What the parameter is for and the Java constant that declares it; for `defType`, the query parser it selects |

An attribute, class or parameter the generated catalog does not know — your own plugin's — answers
with nothing rather than a guess.

![Quick documentation for the field category: a properties table giving each property's value, where that value came from, what it accepts, and what it means](images/02-quick-doc-field-light.png#only-light)
![Quick documentation for the field category: a properties table giving each property's value, where that value came from, what it accepts, and what it means](images/02-quick-doc-field-dark.png#only-dark)

![Quick documentation for solr.StandardTokenizerFactory: short name and kind, fully-qualified class name, one-sentence summary, accepted attributes, and a Reference Guide link](images/03-quick-doc-class-light.png#only-light)
![Quick documentation for solr.StandardTokenizerFactory: short name and kind, fully-qualified class name, one-sentence summary, accepted attributes, and a Reference Guide link](images/03-quick-doc-class-dark.png#only-dark)

![Quick documentation on the schema's version attribute: the general rule, then what this configset's declared 1.6 decides — docValues off, uninvertible on, autoGeneratePhraseQueries off](images/16-hover-schema-version-light.png#only-light)
![Quick documentation on the schema's version attribute: the general rule, then what this configset's declared 1.6 decides — docValues off, uninvertible on, autoGeneratePhraseQueries off](images/16-hover-schema-version-dark.png#only-dark)

---

## Discovering what you're allowed to write

### The schema's own vocabulary

**Gesture:** type `<` inside a schema element, or a space inside an opening tag, then
<kbd>Ctrl-Space</kbd>.

**You get:** only the elements legal at that position; the attributes the element accepts, each with
a one-line summary and its values, **omitting** any the tag already carries. A boolean's default is
marked where it is knowable, and left unmarked where it depends on the field's type.

![Attribute completion inside a field tag, listing docValues, sortMissingLast, default, large, multiValued, omitNorms and more, each with a one-line summary and the values it accepts](images/05-completion-field-properties-light.png#only-light)
![Attribute completion inside a field tag, listing docValues, sortMissingLast, default, large, multiValued, omitNorms and more, each with a one-line summary and the values it accepts](images/05-completion-field-properties-dark.png#only-dark)

### Catalog-backed classes and factory attributes

**Gesture:** <kbd>Ctrl-Space</kbd> in a `class=` value, or a space inside a factory tag.

**You get:** `solr.*` field type classes on a `<fieldType>`, analysis factories on a `<tokenizer>` or
`<filter>`, each set following the Solr line the configset declares. Inside a factory tag, that
factory's own attributes, read from its constructor bytecode when the plugin was built, each labelled
with the factory it belongs to.

![Attribute completion inside a filter tag declaring solr.EdgeNGramFilterFactory, offering luceneMatchVersion and preserveOriginal, each labelled with the factory it comes from](images/06-completion-factory-attributes-light.png#only-light)
![Attribute completion inside a filter tag declaring solr.EdgeNGramFilterFactory, offering luceneMatchVersion and preserveOriginal, each labelled with the factory it comes from](images/06-completion-factory-attributes-dark.png#only-dark)

### `solrconfig.xml`'s own structure

**Gesture:** type `<` or a space inside `solrconfig.xml`, then <kbd>Ctrl-Space</kbd>.

**You get:** elements and attributes from the same generated vocabulary the documentation and
[inspections](glossary.md#inspection) read, respecting nesting — what completes inside `<query>` is
not what completes inside `<config>`. An element Solr no longer accepts (`nrtMode`, `mainIndex` and
others) is never offered.

![Element completion inside the config element in solrconfig.xml offering Solr's own top-level vocabulary — query, requestHandler, directoryFactory, luceneMatchVersion — not an echo of sibling tags](images/10-completion-solrconfig-structure-light.png#only-light)
![Element completion inside the config element in solrconfig.xml offering Solr's own top-level vocabulary — query, requestHandler, directoryFactory, luceneMatchVersion — not an echo of sibling tags](images/10-completion-solrconfig-structure-dark.png#only-dark)

### Field names inside `solrconfig.xml` parameters

**Gesture:** <kbd>Ctrl-Space</kbd> inside a [request-handler](glossary.md#request-handler) parameter
that holds field names — `qf`, `pf`, `fl`, `sort` among them — after a space if the caret is against
existing text. Or inside the `name` of a `<str>` in `<lst name="defaults">`.

**You get:** inside a value, the schema's fields, with a dynamic pattern such as `*_t` in italics.
Nothing is offered in a parameter that holds no field names (`rows`, `defType`), right after a boost
(`name^`), or at a `sort` clause's direction. Inside a `name`, Solr's own parameter names, each
labelled with its declaring class; one already set in the same list is withheld.

![Field-name completion inside a solrconfig.xml qf parameter, offering the schema's fields with their types, including the *_t dynamic pattern](images/13-completion-parameter-fields-light.png#only-light)
![Field-name completion inside a solrconfig.xml qf parameter, offering the schema's fields with their types, including the *_t dynamic pattern](images/13-completion-parameter-fields-dark.png#only-dark)

![solrconfig.xml parameter-name completion offering sort, expand.sort, facet.sort, group.sort and terms.sort, each labelled with its declaring Params class](images/15-completion-parameter-names-light.png#only-light)
![solrconfig.xml parameter-name completion offering sort, expand.sort, facet.sort, group.sort and terms.sort, each labelled with its declaring Params class](images/15-completion-parameter-names-dark.png#only-dark)

---

## Navigating and finding usages

**Gesture:** <kbd>Ctrl-click</kbd> (⌘-click) to jump to a declaration; [Find Usages](glossary.md#find-usages)
(<kbd>Alt-F7</kbd>) on a declaration.

**You get:** a real [reference](glossary.md#reference) from four kinds of string — a field's `type`, a
`copyField`'s `source` and `dest`, a `solrconfig.xml` handler parameter naming a field, and a filter's
resource attribute (`words=`, `synonyms=`, `protected=`, a char filter's `mapping=`), which opens the
file. Find Usages crosses from the schema into `solrconfig.xml`, labels results in Solr's vocabulary
(*Field type*, *Field declaring this type*), and on a `<dynamicField>` reports the names its pattern
supplies — `body_t` for `*_t` — not only literal spellings of the pattern.

**Silent when:** a `class` names something outside the project's classpath — `solr.SearchHandler`
lives in `solr-core` — which is ordinary and draws no warning; documentation still answers from the
catalog.

![Find Usages on the text_general fieldType declaration, showing four fields grouped under 'Field declaring this type'](images/07-find-usages-field-type-light.png#only-light)
![Find Usages on the text_general fieldType declaration, showing four fields grouped under 'Field declaring this type'](images/07-find-usages-field-type-dark.png#only-dark)

![Navigation tooltip from a solrconfig.xml qf parameter into the schema field it names](images/08-nav-solrconfig-field-reference-light.png#only-light)
![Navigation tooltip from a solrconfig.xml qf parameter into the schema field it names](images/08-nav-solrconfig-field-reference-dark.png#only-dark)

---

## Renaming safely across a configset

**Gesture:** <kbd>Shift-F6</kbd> on a field, dynamic field or field type declaration.

**You get:** a [rename](glossary.md#rename-refactoring) everywhere a reference resolves to it,
including `solrconfig.xml`, through the same references Find Usages reads, in a dialog labelled in
Solr's terms. One undo reverts every file.

**Deliberately partial:** renaming a dynamic field's pattern updates literal spellings of the pattern
but not names it only supplies (`body_t` for `*_t`), since rewriting those would put a glob where a
field name belongs; the unknown-field inspection then flags each orphaned name.

![The qf parameter in solrconfig.xml immediately after renaming category to product_category from the schema declaration — updated with no separate edit](images/14-rename-cross-file-after-light.png#only-light)
![The qf parameter in solrconfig.xml immediately after renaming category to product_category from the schema declaration — updated with no separate edit](images/14-rename-cross-file-after-dark.png#only-dark)

---

## Catching mistakes before Solr does

**Gesture:** none — edit the file; <kbd>Alt-Enter</kbd> on a finding for its quick-fix.

**You get:** twelve inspections for the ways a configset fails silently — at [core](glossary.md#core)
reload, at query time, or never. Four, for a name that resolves to nothing, are errors drawn as a red
wave in every scheme; the rest are warnings, which some colour schemes draw as a tinted background
with no line. [The inspection catalog](inspection-catalog.md) owns what each one reports, its
severity, and what it stays silent about.

| Group | Catches |
|---|---|
| [Schema](inspection-catalog.md#schema-checks) | A `copyField` naming an undeclared field (Alt-Enter offers the closest declared names); a field naming an undeclared type; a type nothing uses; an [analyzer chain](inspection-catalog.md#analyzer-chain-ordered-so-a-component-cannot-work--solranalyzerchainorder) ordered so a filter can do nothing |
| [`solrconfig.xml`](inspection-catalog.md#solrconfigxml-checks) | A handler parameter naming an undeclared field, or one that cannot serve it (`qf` on a non-indexed field, `facet.field` or `sort` with neither `indexed` nor `docValues`); an element Solr no longer accepts; a parameter name that is almost one Solr reads |
| [Catalog-backed attributes](inspection-catalog.md#catalog-backed-attribute-checks) | An unknown attribute, or a value outside a closed set (`indexed="yes"`) |
| [Code](inspection-catalog.md#code-checks) | A field name in Java or Kotlin no configset declares — see [below](#catching-a-field-name-typo-in-java-or-kotlin) |

An unused field type is drawn dimmed, with no quick-fix, because it is
[an observation rather than a defect](inspection-catalog.md#the-one-finding-that-is-not-a-defect).

![A copyField whose source names manufacturer, highlighted in the editor, with the Alt-Enter menu open above it offering to change the name to *_t, category, description, legacy, name or notes](images/04-inspection-copyfield-quickfix-light.png#only-light)
![A copyField whose source names manufacturer, highlighted in the editor, with the Alt-Enter menu open above it offering to change the name to *_t, category, description, legacy, name or notes](images/04-inspection-copyfield-quickfix-dark.png#only-dark)

---

## Seeing what's already redundant

**Gesture:** none; <kbd>Alt-Enter</kbd> on a dimmed attribute to remove it.

**You get:** an attribute whose value equals what Solr would supply anyway rendered dimmed, at
information severity, never in the Problems view. The [intention](glossary.md#intention) removes it,
leaving identical resolved properties. It covers field properties, resolved through field, field
type and Solr's default, and factory attributes, against the default read from the factory's
bytecode. Where the default cannot be known with confidence — a custom field type class — nothing
dims.

![managed-schema.xml field block with indexed=true and stored=true rendered dimmed as restated defaults, while stored=false on name_prefix stays normal](images/11-dimmed-restated-default-light.png#only-light)
![managed-schema.xml field block with indexed=true and stored=true rendered dimmed as restated defaults, while stored=false on name_prefix stays normal](images/11-dimmed-restated-default-dark.png#only-dark)

---

## Fixing a missing capability automatically

**Gesture:** <kbd>Alt-Enter</kbd> on a field.

**You get:** **Add exact-match companion field** (a `StrField`-backed `_exact` field, for sorting and
faceting a tokenised field's content) and **Add prefix-capable companion field** (an EdgeNGram-backed
`_prefix` field). Each writes the `<field>`, its `<fieldType>` if missing, and the `<copyField>`
joining them, directly with no confirmation, as
[every write in this plugin does](code-organization.md#rules-that-hold-across-every-package). Neither
is offered once the companion exists, and `_exact` is not offered on a field that is already
whole-value.

![Alt-Enter menu on a tokenized text field offering 'Add exact-match companion field' and 'Add prefix-capable companion field' intentions, with the exact-match explanation shown alongside](images/12-intention-companion-fields-light.png#only-light)
![Alt-Enter menu on a tokenized text field offering 'Add exact-match companion field' and 'Add prefix-capable companion field' intentions, with the exact-match explanation shown alongside](images/12-intention-companion-fields-dark.png#only-dark)

---

## Pointing the plugin at a Solr server

*Everything below needs a connection; nothing above does.*

**Gesture:** Settings → Tools → Solr Connections, or `+` in the **Solr** tool window.

**You get:** a connection of name, URL and optional username. The password goes to the IDE's password
safe, never a project file; the field opens empty even when one is stored, says so underneath, and
blank keeps it. The URL need only be an `http` or `https` address — no `/solr` suffix is required.

![Settings, Tools, Solr Connections, with a connection open for editing: its password field empty and the text under it saying a password is stored](images/22-connections-settings-light.png#only-light)
![Settings, Tools, Solr Connections, with a connection open for editing: its password field empty and the text under it saying a password is stored](images/22-connections-settings-dark.png#only-dark)

## Browsing what a server holds

**Gesture:** the **Solr** tool window, **Collections** tab.

**You get:** collections, shards and replicas for SolrCloud, or cores for standalone — chosen from the
mode the server reports, never guessed. A collection's or shard's health is a coloured dot, with the
word on hover; an unrecognised health word is written out instead. Each collection or core has a
**Fields** row that asks the server only when expanded, and shows what the index holds — including
fields a dynamic pattern created, such as `author_s` from `*_s` — rather than the schema. A point
field shows no document count because Solr reports none for it. Nothing refreshes on a timer.

![The Solr tool window on a SolrCloud server: the shows collection and its shard, each with a green health dot, and the shard's replica marked as leader, with the connection selector above and the Solr icon on the stripe](images/23-collections-topology-light.png#only-light)
![The Solr tool window on a SolrCloud server: the shows collection and its shard, each with a green health dot, and the shard's replica marked as leader, with the connection selector above and the Solr icon on the stripe](images/23-collections-topology-dark.png#only-dark)

![A collection's Fields row expanded: 7 fields, 2 from dynamic patterns, 4 documents; author_s marked as coming from *_s, and price_f from *_f with no document count](images/24-luke-fields-light.png#only-light)
![A collection's Fields row expanded: 7 fields, 2 from dynamic patterns, 4 documents; author_s marked as coming from *_s, and price_f from *_f with no document count](images/24-luke-fields-dark.png#only-dark)

## Running a query

**Gesture:** in any `.http` file, **Add Request** → **Solr**; or right-click a row in the **Solr**
tool window. Choose where the tool window opens queries under Settings → Tools → Solr Connections →
*Open queries from the Solr tool window in* — the HTTP Client (the default) or
[the query console](#the-query-console). The choice is stored with your connections, not the project.

**You get:**

- **Templates** in the HTTP Client: query a collection, query with a field list and sort, explain
  scoring, read the schema, list what the index holds, and a POST using Solr's JSON Request API. Each
  addresses `{{solrUrl}}`, so a committed `.http` file works for anyone with an
  `http-client.env.json`.
- **From the tool window:** *Query* and *Explain Scoring* on a collection or core; *Find Documents
  with* and, where countable, *Count Values of* on a field (a tokenized text field gets only the
  first). The request opens in a scratch `.http` file and runs nothing until you press run; it never
  holds a password, only a comment naming the header to add.
- **A summary above the raw JSON**: matches and returned rows stated separately, and with
  `debugQuery=true` the scoring explanation with Solr's indentation intact.
- **Field-name completion in a JSON body** (`fields`, `sort`, a facet's `field`) from the collection
  on the request line, on the connection selected in the tool window, each entry labelled
  `books · local`. Only <kbd>Ctrl-Space</kbd> reads the server, once per collection until a refresh
  or a connection edit. Where the collection cannot be known — no connection, an unreadable server,
  or a `{{collection}}` variable whose environments disagree — the project's configsets answer, and
  the popup says why.

![An explain request run through the local environment: 8 documents matched in 39 ms, showing 1 to 3, a table of three TV shows, the parsed query, and the start of each document's scoring explanation](images/25-query-and-results-light.png#only-light)
![An explain request run through the local environment: 8 documents matched in 39 ms, showing 1 to 3, a table of three TV shows, the parsed query, and the start of each document's scoring explanation](images/25-query-and-results-dark.png#only-dark)

![Completion inside a JSON request body's fields array, offering the books collection's fields each labelled books · local, with the dynamic patterns in italics](images/30-completion-live-schema-light.png#only-light)
![Completion inside a JSON request body's fields array, offering the books collection's fields each labelled books · local, with the dynamic patterns in italics](images/30-completion-live-schema-dark.png#only-dark)

### The query console

**Gesture:** right-click a collection or field in the **Solr** tool window, with the query console
chosen; <kbd>Ctrl+Enter</kbd> (<kbd>⌘Enter</kbd>) to Execute.

**You get:** a `Query: <collection>` tab, one per connection and collection, holding Solr Admin's query
form — handler, `q`, `q.op`, `fq` (one per line), `sort`, `start`, `rows`, `fl`, `df`, paramsets,
`wt`, `indent`, `debugQuery`, dismax and edismax parameters for those parsers, and raw `name=value`
lines — so no value needs escaping. Execute shows the request as a GET URL, the same summary as the
HTTP Client, and the **Response** exactly as Solr sent it; a second Execute cancels one running. A
parameter left at its default or hidden is not sent, and the handler must be a path (`qt=dismax` is
refused). `fl`, `sort`, `df`, `qf`, `pf`, `pf2` and `pf3` complete field names on the same terms as an
`.http` file. **Open as .http** writes the query to a scratch file with `{{solrUrl}}` and
`{{collection}}`, never a password.

## Indexing a test document

**Gesture:** select a collection, or any row under one, in the **Solr** tool window and press **Index
a Test Document**.

**You get:** a starting document written from the collection's schema — unique key, required fields,
then every other declared field — with a placeholder of the right JSON shape for each, which sends as
it stands. Left out: Solr's own fields (`_version_`, anything starting and ending with `_`),
copy-field destinations, and fields neither indexed, stored nor docValues. Field names complete from
that collection's schema, dynamic patterns in italics.

**Caught before sending**, because Solr accepts both with `status: 0`: a field the schema cannot place
(Solr may add it to the deployed schema) and a missing unique key (Solr generates one). A name a
dynamic pattern matches is fine; one of Solr's own fields warns without blocking. **When it becomes
findable** is chosen in the dialog — `commitWithin` by default, immediate one click away.

![The Index a test document dialog for shows on solr-mcp-demo, opening on every field the collection declares with a sample value of each field's type, and saying the document matches the collection's schema](images/31-index-test-document-light.png#only-light)
![The Index a test document dialog for shows on solr-mcp-demo, opening on every field the collection declares with a sample value of each field's type, and saying the document matches the collection's schema](images/31-index-test-document-dark.png#only-dark)

## Closing a difference between the repository and a server

**Gesture:** the **Solr** tool window's **Drift** tab: choose a configset and a collection, press
**Compare**; select a row; **Apply Additive Changes**, or **Upload and Reload**.

**You get:** what the two disagree about, in three kinds:

| The row says | It means | Offered? |
|---|---|---|
| **Not deployed** | The configset declares it; the server does not have it | **Yes** — an addition |
| **Only on server** | Added through the Schema API and never committed | No |
| **Differs** | Both have it, defined differently | **No, deliberately** |

Selecting any row shows the exact Schema API request that would close it; for the two not offered,
under the reason why. A changed field is never applied because Solr accepts a type change and
reports success while indexed documents keep their old encoding, so queries on the field can fail
until a reindex ([the server specification](../specs/0002-solr-server-integration.md) records the
experiment). Applying re-reads the collection's schema and compares again, rather than trusting the
`2xx` ([every write re-reads](platform-mechanisms.md#every-read-is-on-request-and-every-write-re-reads)).
**Upload and Reload** instead overwrites the server's configset of that name with the whole configset
and reloads the collection, then compares again; it is refused against a standalone Solr. Both
confirm first, naming the collection and server.

Also: the collection chooser lists what the server holds and the configset each was built from (an
alias can be typed); a factory's spelling — `lowercase`, `solr.LowerCaseFilterFactory`, its Lucene
class — is not a difference; and the summary names the Solr line the comparison resolved against and
where that came from. On a *Differs* row whose two columns look identical, widen them — the
difference is in the truncated part. The demo's `compose.yaml` sets up one row of each kind; see
[the quick start](quick-start.md#7-talk-to-a-server-optional).

![The drift view comparing the solr configset with the shows collection on SolrCloud 9.10: fields only on the server, category not deployed, and description differing, selected, with the reason the plugin will not send its replace-field request shown above the request](images/19-drift-three-states-light.png#only-light)
![The drift view comparing the solr configset with the shows collection on SolrCloud 9.10: fields only on the server, category not deployed, and description differing, selected, with the reason the plugin will not send its replace-field request shown above the request](images/19-drift-three-states-dark.png#only-dark)

![The drift view before applying: one Not deployed row for subtitle, its add-field request in the pane below](images/21-drift-apply-before-light.png#only-light)
![The drift view before applying: one Not deployed row for subtitle, its add-field request in the pane below](images/21-drift-apply-before-dark.png#only-dark)

![The same comparison after applying: no rows, and the summary saying books and books agree across 22 declarations](images/21-drift-apply-after-light.png#only-light)
![The same comparison after applying: no rows, and the summary saying books and books agree across 22 declarations](images/21-drift-apply-after-dark.png#only-dark)

---

## Catching a field name typo in Java or Kotlin

**Gesture:** none — edit Java or Kotlin that calls [SolrJ](glossary.md#solrj).

**You get:** a field name no configset in the project declares is marked — the name only, up to the
colon. Solr answers a query on a missing field with zero results rather than an error, so this is
otherwise found in production. Java and Kotlin share one implementation. Four places are read:

| Written as | Example |
|---|---|
| A query-builder call | `q.addFilterQuery("category:books")` |
| A field list in a raw parameter string | `q.setFields("id,name,score")` |
| A document being built | `doc.addField("price", 9.99)` |
| A bean binding | `@Field("price") BigDecimal price;` (a bare `@Field` names its property) |

**Silent when:** the module has no Solr client on its classpath; the name is not spelled out in the
source (a variable, interpolation); or the project has no configset at all.
[The catalog entry](inspection-catalog.md#code-names-a-field-no-configset-declares--solrunknowncodefield)
has the full rules.

![Two Java lines in ProductSearch.java: categry inside addFilterQuery and price inside setFields are each marked, while id and name in the same string are left alone](images/26-code-field-warning-light.png#only-light)
![Two Java lines in ProductSearch.java: categry inside addFilterQuery and price inside setFields are each marked, while id and name in the same string are left alone](images/26-code-field-warning-dark.png#only-dark)

## Completing a field name in Java or Kotlin

**Gesture:** type a letter where a field name goes — `addFilterQuery("c` — or after a comma in a
field list; the list opens by itself. <kbd>Ctrl-Space</kbd> works too.

**You get:** declared fields only, each with its type and configset, and only at a field-name
position: before each clause's colon in a query, not in a value, phrase, range or local-parameter
block; only the first argument of `setSort`. No dynamic patterns (inserted, `*_s` is a wildcard Solr
rejects), no word or path suggestions, and only fields that can do what the call asks — searchable
for a query, facetable for a facet, single-valued and sortable for a sort — by the same rules as the
`solrconfig.xml` inspections. A field of a type the plugin cannot classify is offered. It needs
SolrJ's own method and the module gate; another library's `@Field` offers nothing.

![A setQuery string with the caret after AND and the letters de typed: the completion popup has opened on its own, offering description from the solr configset with its type, text_general](images/32-code-completion-popup-light.png#only-light)
![A setQuery string with the caret after AND and the letters de typed: the completion popup has opened on its own, offering description from the solr configset with its type, text_general](images/32-code-completion-popup-dark.png#only-dark)

## From a field name in code to its declaration

**Gesture:** <kbd>Ctrl-click</kbd> a Solr field name in Java or Kotlin.

**You get:** the `<field>` that declares it, from any of the four places a name is written. With
several configsets, the first that declares the name. Silent wherever the check is silent.

![ProductSearch.java and managed-schema.xml open side by side, the caret landed on the field element declaring category](images/28-code-navigate-to-schema-light.png#only-light)
![ProductSearch.java and managed-schema.xml open side by side, the caret landed on the field element declaring category](images/28-code-navigate-to-schema-dark.png#only-dark)

## Seeing the shape of a query written in code

**Gesture:** none — write a Solr query in a string.

**You get:** field names and boolean operators coloured apart from the string — colour, not a parser,
from the same scan the check uses. Operators only in the spellings Solr treats as operators (a
lowercase `and` is a term); nothing inside a phrase or a local-parameter block. Colours follow your
scheme's instance-field and keyword colours; change them under *Settings → Editor → Color Scheme →
Solr Query*.

![A setQuery call in which category and name are in the field colour and AND in the operator colour, picked out from books and solr, which keep the ordinary string colour](images/27-code-query-colour-light.png#only-light)
![A setQuery call in which category and name are in the field colour and AND in the operator colour, picked out from books and solr, which keep the ordinary string colour](images/27-code-query-colour-dark.png#only-dark)

## Running a query without leaving the file

**Gesture:** click the gutter icon beside a call that sets the main query `q`, such as `setQuery`,
then choose a collection.

**You get:** the query run against the selected connection, answered in place with the same summary as
the HTTP Client and [the query console](#the-query-console). The collection list is what the server
holds; an unreachable or refusing server says so rather than listing nothing. Filter queries carry no
icon, and only `q` is sent — no rows, field list or sort your code did not ask for.

![The run icon in the gutter beside a setQuery line, with a popup asking which collection to run against and listing products](images/29-code-run-from-gutter-light.png#only-light)
![The run icon in the gutter beside a setQuery line, with a popup asking which collection to run against and listing products](images/29-code-run-from-gutter-dark.png#only-dark)

---

What is built, and what is next, is owned by
[the implementation plan](../specs/plans/0002-solr-intellij-plugin-plan.md).
