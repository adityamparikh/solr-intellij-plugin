# FAQ

> **Who this is for.** A reader who has already used quick documentation (F1 / hover) in this plugin
> and wants to know why it is built the way it is — what is generated, what is hand-maintained, and
> why it links to the Reference Guide instead of copying it.
> **Read first:** [Glossary](glossary.md) if Solr terms are new · [User guide](user-guide.md)

Questions that came up about quick documentation's design — why it works the way it does, and why
the alternatives that sound obvious were rejected. Each answer traces back to code and KDoc already
in the repository, or to the upstream documentation it names, rather than restating intent; follow
the references for the full reasoning.

**The approach, in one paragraph.** Everything the popup says about *Solr's own classes* is read
mechanically from Solr's published artifacts: attribute names, types, defaults, required markers and
the class hierarchy from bytecode, plus one summary sentence from the `-sources` jar — extracted at
build time against a pinned version per supported major line, and shipped inside the plugin. What it
says about *your schema* is resolved live from the files in front of it. One table of about twenty
[field](glossary.md#field) properties is hand-maintained, deliberately, for reasons argued below.
Nothing is copied from
the Reference Guide; concept-level explanation is linked instead. One reason covers nearly all of
it: text extracted mechanically from an artifact Solr published cannot drift out of sync with it,
and text copied by hand always can. The next section shows the whole of it in two screenshots; the
rest of the page is the follow-up questions it provokes.

## Where does each line of the popup come from?

**A `class` value.** Hovering `class="solr.StandardTokenizerFactory"` in a schema:

![Quick documentation on a class value, with five regions marked: the short name and kind, the
fully-qualified class name, the one-sentence Javadoc summary, the accepted attributes, and the
Reference Guide link](images/03-quick-doc-class-annotated.png)

| | What you see | Where it came from |
|---|---|---|
| ① | `solr.StandardTokenizerFactory — tokenizer factory` | Build time. The short name is composed from the class's simple name; the *kind* is which Lucene SPI service file listed it. |
| ② | `org.apache.lucene.analysis.standard.StandardTokenizerFactory` | Build time, from the scanned `.class` file. Note it is a **Lucene** class, despite the `solr.` spelling a configset uses. |
| ③ | `Factory for StandardTokenizer.` | Build time, from the `-sources` jar. Lucene's source reads `Factory for {@link StandardTokenizer}.` — the first sentence survives, the `{@link}` is rendered to its text. |
| ④ | [`luceneMatchVersion`](glossary.md#lucenematchversion), `maxTokenLength — a whole number` | Build time, from the factory's constructor bytecode: what it reads out of its argument map, and the type of each value. |
| ⑤ | `Tokenizers in the Reference Guide ↗` | Constructed here, never fetched. The line beneath it names where the version came from — here, the configset's own `<luceneMatchVersion>`. |

Everything above the link is a lookup into a file generated when the plugin was built. Nothing was
fetched, and no prose was copied from the guide the popup links to.

**A field.** Hovering `name="category"` asks a different question, and gets a different mix:

![Quick documentation on a field, with four regions marked: the field and type header, the match
summary, the Value and From columns, and the Accepts and Meaning columns](images/02-quick-doc-field-annotated.png)

| | What you see | Where it came from |
|---|---|---|
| ① | `field category of type string` | The schema file under the caret, parsed live. |
| ② | `Matches: whole value, case-sensitive.` | Computed here, by walking the type's analyzer chain. The caveat beneath it is the plugin's own sentence, because a reader who stops at "whole value" would draw the wrong conclusion about wildcards. |
| ③ | **Value** and **From** | Resolved live across three places — the field, then its type, then Solr's default — with **From** naming which one won. This is the column the Reference Guide cannot have, because it is about *your* schema. |
| ④ | **Property**, **Accepts**, **Meaning** | Hand-maintained inside the plugin. The one deliberate exception to everything above. |

### So something *is* hand-maintained?

Yes — ④, and only ④. `SolrFieldProperties`' KDoc argues the exception rather than hiding it:

> **Hand-maintained, and that is a deliberate exception** to the rule that Solr's vocabulary is
> generated at build time. […] the roughly 130 analysis factories are too many to hand-maintain and
> change with Solr versions. This set is neither. It is about twenty entries, it has been stable
> across Solr majors, and — like match analysis — it *defines* the semantics rather than enumerating
> what happens to exist.

It is also not reachable by the machinery that generates the rest: the catalog reflects over factory
classes, while these properties are read by `SchemaField` and `FieldType` out of an argument map,
with defaults living in branching code rather than in any enumerable structure.

The picture shows that table holding its own line where it does not know. `default`,
`omitTermFreqAndPositions` and `omitPositions` read "depends on the field type" and their **From**
says *see the guide*, because asserting one answer where Solr has two is how a plugin gets
distrusted. A hand-maintained table is allowed to be incomplete; it is not allowed to be confidently
wrong.

[`omitNorms`](glossary.md#omitnorms) used to sit in that list and no longer does: it reads **true**, *Solr default for
solr.StrField*. Its two answers — true for primitive types, false for text — turned out to be
decided by the field type's *class*, which the plugin already knows, so resolution reaches the answer
rather than declining to guess. That is the shape of the exception shrinking: a row leaves the
"depends" list when something mechanical can settle it, not when someone picks the likelier value.

## Does the plugin pull sections out of the Solr Reference Guide?

No, and that is deliberate. `SolrConfigsetDocumentationProvider` (in
`org.apache.solr.ide.configset.schema.documentation`) answers a narrower question the Reference Guide
cannot — not "what does `omitNorms` mean" in general, but what it is *for this field in this
schema*, and whether that value came from the field, its type, or Solr's default. For the general
explanation, it **links** to the guide instead of embedding any of its prose.

`SolrReferenceGuide` (in `org.apache.solr.ide.model`) builds those links. Its own KDoc states the
reasoning directly:

> **Links, never copies.** The Reference Guide explains concepts with examples and context that
> javadoc does not; carrying that prose inside the plugin would mean maintaining a second body of
> documentation that goes stale on its own schedule. A link costs no licensing question and is
> always current.

> **A dead link is worse than no link.** Anchors in the guide are generated from headings and drift
> between releases, so this returns page-level links only, and returns null rather than guessing
> when there is no page it is confident about.

The platform has a seam for exactly this shape: `com.intellij.lang.documentationProvider`
(`AbstractDocumentationProvider`) separates the popup body from `getUrlFor()`, the external link the
IDE offers alongside it. Rendering one and delegating the other is what that pair is for.

## Is "link, never copy" universal across IntelliJ plugins? What about Spring?

No — it is this project's choice, and Spring makes the opposite one.
`spring-boot-configuration-processor` reads the Javadoc on every `@ConfigurationProperties` field at
compile time and writes it into `META-INF/spring-configuration-metadata.json`: "the Javadoc on
fields is used to populate the `description` attribute." That format has no field for a link at all
(`name`, `type`, `description`, `sourceType`, `defaultValue`, `deprecation`), so a tool reading it
has only the embedded text — and the description it shows for `spring.jpa.hibernate.ddl-auto` is the
field's whole comment, not its first sentence.

Both are mechanical extraction, cutting at different points, and two things put Spring's cut out of
reach here. The processor "can only populate the `description` attribute when the type is available
as source code that is being compiled," and this plugin never compiles Solr. And Spring embeds
Javadoc it wrote, where staleness and licensing are its own problem to have; everything this plugin
would embed belongs to Apache Solr.

## Why does the generated catalog keep only a one-sentence summary, not the full Javadoc?

Two constraints, both in
`buildSrc/src/main/kotlin/org/apache/solr/ide/build/GenerateSolrCatalogTask.kt`.

**It is the one fact bytecode cannot carry.** Attribute names, types, defaults, required-ness and
the class hierarchy all come from `.class` files via ASM; Javadoc is not retained in bytecode, so
this column comes from the optional `-sources` jars, which may not resolve for a given Solr line at
all. `JavadocSummaries`' KDoc keeps it machine-read for the reason the plugin links rather than
copies: "a second body of documentation drifts out of sync on its own schedule, and a summary read
mechanically from the artifact Solr itself published for this exact release cannot."

**One sentence is what "summary" already means.** `summarizeJavadocComment` cuts where the `javadoc`
tool does for its overview tables — "prose only — block tags and worked `<pre>` examples cut off,
not merely truncated — reduced to what precedes the first `". "`". That is the convention Solr's
authors were already writing to: the first sentence is the one meant to stand alone. Taking more
takes prose written to be read in context, into a popup that has none.

The pass is more than a truncation, which is what makes it readable: `{@link}` and `{@code}` render
to their text, inline tags it cannot render are dropped rather than shown as raw braces, HTML is
stripped, whitespace is collapsed, and a comment with no sentence-ending period is kept whole —
"Creates new instances of X" is exactly the case worth having. Collapsing whitespace also keeps the
catalog's tab-separated rows intact, and `appendCatalogRow` guards the one character that would
still corrupt one: a literal tab.

## Why not resolve the exact `-sources` jar for the project's Solr version?

There is no exact version to resolve against, and resolving at runtime would cost more than it buys.
From `SolrVersionSelection.fromLuceneMatchVersion` (in `SolrReferenceGuide.kt`):

- **Only a major line is knowable.** `luceneMatchVersion` names a *Lucene* version — Solr 10.0 pairs
  with Lucene 10.3, Solr 9.10 with Lucene 9.12 — so a configset yields `9` or `10`, never a
  coordinate like `solr-core-9.7.1`. A range resolves (Maven's `[9,10)`, Gradle's `9.+`) but only to
  the most recently published 9.x, which is a guess about what the user runs, bought with a network
  trip.
- **There is no live source to fetch from.** Even a connected server reports a version string, not
  its `-sources` artifact, so turning one into the other is still a trip to Maven Central or a
  mirror. `docs/Module.md` promises Phase 1 works "offline with no Solr connection."
- **It is a dependency graph, not one jar.** The factory data spans several independently versioned
  Lucene analysis modules. Which module a factory lives in is what Gradle's resolver computes once
  at build time against a pinned classpath, and is not derivable from a version number.
- **A sources jar can be missing, and then the summary is.** `SolrLine.sources` "may be empty," and
  the classes it would have covered "degrade to no documentation" rather than to a guess — a gap the
  bytecode half of the catalog never has. Solr's own dependencies are not that case: Jetty and
  ZooKeeper publish sources, and the generator drops them by filename, scanning `solr-*` and
  `lucene-*` alone because nothing else carries a class a configset can name.

**Not from the [SolrJ](glossary.md#solrj) dependency either.** `SolrProjectDetector` matches
`SOLR_CLIENT_COORDINATES`
"as substrings of the library name so that every version matches and no version is named here" — a
gate asking *whether* a Solr client is present, never *which*. Reading that version would not help
anyway: SolrJ depends on `solr-api`, Jetty and Jackson, not on `solr-core` or any Lucene analysis
module — `StandardTokenizerFactory`, the most-used tokenizer there is, lives in `lucene-core` — a
project's client version need not match the server it deploys to, and a bare configset repository
has no build file to read one from.

**The trade-off taken.** Pre-compute once per supported line, from the pinned version in
`supportedSolrLines` (`build.gradle.kts`): close enough for the line rather than exact for a patch
release. Class-level Javadoc rarely changes within a major line, and the accuracy given up is given
up at build time, where a mistake is visible, rather than at runtime where it is a failed fetch in
front of a user.
