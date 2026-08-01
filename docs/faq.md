# FAQ

Questions that came up about quick documentation's design — why it works the way it does, and why
the alternatives that sound obvious were rejected. Each answer traces back to code and KDoc already
in the repository, or to the upstream documentation it names, rather than restating intent; follow
the references for the full reasoning.

**The approach, in one paragraph.** Everything the popup says about *Solr's own classes* is read
mechanically from Solr's published artifacts: attribute names, types, defaults, required markers and
the class hierarchy from bytecode, plus one summary sentence from the `-sources` jar — extracted at
build time against a pinned version per supported major line, and shipped inside the plugin. What it
says about *your schema* is resolved live from the files in front of it. One table of about twenty
field properties is hand-maintained, deliberately, for reasons argued below. Nothing is copied from
the Reference Guide; concept-level explanation is linked instead. One reason covers nearly all of
it: text extracted mechanically from an artifact Solr published cannot drift out of sync with it,
and text copied by hand always can. The next section shows the whole of it in two screenshots; the
rest of the page is the follow-up questions it provokes.

## Where does each line of the popup come from?

**A `class` value.** Hovering `class="solr.StandardTokenizerFactory"` in a schema:

![Quick documentation on a class value, with five regions marked: the short name and kind, the
fully-qualified class name, the one-sentence Javadoc summary, the accepted attributes, and the
Reference Guide link](images/quick-doc-class-annotated.png)

| | What you see | Where it came from |
|---|---|---|
| ① | `solr.StandardTokenizerFactory — tokenizer factory` | Build time. The short name is composed from the class's simple name; the *kind* is which Lucene SPI service file listed it. |
| ② | `org.apache.lucene.analysis.standard.StandardTokenizerFactory` | Build time, from the scanned `.class` file. Note it is a **Lucene** class, despite the `solr.` spelling a configset uses. |
| ③ | `Factory for StandardTokenizer.` | Build time, from the `-sources` jar. Lucene's source reads `Factory for {@link StandardTokenizer}.` — the first sentence survives, the `{@link}` is rendered to its text. |
| ④ | `luceneMatchVersion`, `maxTokenLength — a whole number` | Build time, from the factory's constructor bytecode: what it reads out of its argument map, and the type of each value. |
| ⑤ | `Tokenizers in the Reference Guide ↗` | Constructed here, never fetched. The line beneath it names where the version came from — here, the configset's own `<luceneMatchVersion>`. |

Everything above the link is a lookup into a file generated when the plugin was built. Nothing was
fetched, and no prose was copied from the guide the popup links to.

**A field.** Hovering `name="category"` asks a different question, and gets a different mix:

![Quick documentation on a field, with four regions marked: the field and type header, the match
summary, the Value and From columns, and the Accepts and Meaning columns](images/quick-doc-field-annotated.png)

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

The picture shows that table holding its own line where it does not know. `default`, `omitNorms` and
`omitTermFreqAndPositions` read "depends on the field type" and their **From** says *see the guide* —
because `omitNorms` defaults true for primitive types and false for text, and asserting one answer
where Solr has two is how a plugin gets distrusted. A hand-maintained table is allowed to be
incomplete; it is not allowed to be confidently wrong.

## Does the plugin pull sections out of the Solr Reference Guide?

No, and that is deliberate. `SolrConfigsetDocumentationProvider` (in
`org.apache.solr.ide.configset.documentation`) answers a narrower question the Reference Guide
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

No — it is this project's choice, not a platform rule. Spring's tooling takes the opposite approach:
`spring-boot-configuration-processor` reads the Javadoc on every `@ConfigurationProperties` field at
compile time and writes it into `META-INF/spring-configuration-metadata.json` under a
`"description"` key. Spring's own reference documentation states it plainly — "the Javadoc on fields
is used to populate the `description` attribute" — and the multi-sentence description its format
appendix shows for `spring.jpa.hibernate.ddl-auto` is the field's whole comment, not its first
sentence. The metadata format has no field for a link at all (`name`, `type`, `description`,
`sourceType`, `defaultValue`, `deprecation`), so a tool reading it has only the embedded text to
show.

So Spring's model is "mechanically extract Javadoc once, embed the extracted text in full," while
this plugin's is "mechanically extract Javadoc once, keep one sentence, link for the rest." Both are
mechanical extraction; they draw the line at a different point.

**Two things put Spring's line out of reach here.** The mechanism is not available: the processor
"can only populate the `description` attribute when the type is available as source code that is
being compiled," and this plugin never compiles Solr — it reads artifacts someone else published.
And the prose is not its own: Spring embeds Javadoc it wrote, where staleness and licensing are
its own problem to have, while everything this plugin would embed belongs to Apache Solr — which is
what makes "link, never copy" the cheaper answer here and not there.

## Why does the generated catalog keep only a one-sentence summary, not the full Javadoc?

Two constraints shape that column, both visible in
`buildSrc/src/main/kotlin/org/apache/solr/ide/build/GenerateSolrCatalogTask.kt`. The first decides
that the text is machine-read at all; the second decides how much of it survives.

**It is the one fact that cannot come from bytecode.** Everything else in the catalog — attribute
names, types, defaults, required-ness, class hierarchy — is read from compiled `.class` files via
ASM. Javadoc is not retained in bytecode at all, so this column has to come from outside the
compiled artifact: the optional `-sources` jars, which may not even resolve for a given Solr line.
`JavadocSummaries`' KDoc ties that choice to the same principle as `SolrReferenceGuide`:

> The plugin's own reasoning for linking to the Reference Guide rather than copying it is why this
> stays a machine-read summary rather than hand-copied Reference Guide prose — a second body of
> documentation drifts out of sync on its own schedule, and a summary read mechanically from the
> artifact Solr itself published for this exact release cannot.

That settles where the text comes from — Solr's own artifact, re-read on every build, so it cannot
drift the way a hand-copied paragraph would — but not how much of it to keep.

**One sentence is what "summary" already means.** `summarizeJavadocComment`'s own comment defines the
cut as the `javadoc` tool does for its overview tables — "prose only — block tags and worked `<pre>`
examples cut off, not merely truncated — reduced to what precedes the first `". "`". That is a
convention Solr's authors were already writing to, not a limit this build imposes on them: the first
sentence of a class comment is the one they wrote to stand alone. Taking more means taking prose
written to be read in context, in a popup that has none.

The pass is more than a truncation, which is what makes the result readable: `{@link}` and `{@code}`
are rendered to their text, inline tags it cannot render correctly are dropped rather than shown as
raw braces, HTML is stripped, and whitespace is collapsed. A comment with no sentence-ending period
at all is kept whole, since "Creates new instances of X" is exactly the case worth having.

Collapsing whitespace is also what keeps the catalog's row format intact — each entry is a
tab-separated line, `kind`, `class`, `short name`, `attributes`, `documentation` — since a newline
would end the row early. The summary occupies the last column, so its length is not what the format
constrains; `appendCatalogRow` guards the one character that would still corrupt a row, a literal
tab, rather than trusting it to stay out.

## Why not resolve the exact `-sources` jar for the project's Solr version?

Because the plugin never has an exact server-side version to resolve against, and even if it did,
runtime resolution costs more than it buys. From `SolrVersionSelection.fromLuceneMatchVersion` (in
`SolrReferenceGuide.kt`) and its KDoc:

- **Only a major line is knowable today.** `luceneMatchVersion` names a *Lucene* version, not a
  Solr one — Solr 10.0 pairs with Lucene 10.3, Solr 9.10 with Lucene 9.12 — and deriving a full Solr
  version from a Lucene one needs a table that would want updating on every release. So the most
  precise thing derivable from a configset is a major line (`9`, `10`, ...), never a coordinate like
  `solr-core-9.7.1`. A version range would resolve — Maven's `[9,10)` and Gradle's `9.+` both do —
  but only to whatever 9.x was published most recently, which is a guess about what the user runs
  rather than an answer to it, and a network round-trip to make the guess.
- **There is no live source to fetch from.** Even a connected server (planned as
  `SolrVersionSource.SERVER`) reports a version string, not its own `-sources` artifact. Turning that
  string into a jar still means a network round-trip to Maven Central or wherever the user's repos
  are configured — with all the firewall, mirror, and credential complications that implies.
  `docs/Module.md` states Phase 1 works "offline with no Solr connection," and quick documentation
  depending on runtime artifact resolution would break that guarantee.
- **It is a dependency graph, not one jar.** The catalog's tokenizer/filter/char-filter data spans
  several independently versioned Lucene analysis modules, not just `solr-core`. Knowing which
  module a given factory lives in — and which sources artifact to pull — is exactly what Gradle's
  resolver already computes once at build time against a pinned classpath (`solrArtifacts$line` /
  `sources` in `build.gradle.kts`); it is not derivable from a version number alone.
- **Not every module ships sources at all.** `SolrLine.sources`' KDoc notes it "may be empty — a
  module that publishes no sources (Jetty, ZooKeeper)... degrades to no documentation for the
  classes it would have covered." Depending solely on a sources jar would leave gaps the bytecode-
  based catalog never has, since kind/attributes/defaults always come from the `.class` files.

### What about detecting the SolrJ version the project depends on?

`SolrProjectDetector` (`org.apache.solr.ide.configset.activation`) does read library names off the
project's dependencies, and a Gradle-resolved `solr-solrj` library name typically does carry an
exact version. The detector deliberately does not look at it: `SOLR_CLIENT_COORDINATES` is "matched
as substrings of the library name so that every version matches and no version is named here," which
is the shape of a gate that asks *whether* a Solr client is present and never *which*. Three reasons
that version would not be worth reading even if the gate kept it:

- **It is the wrong artifact.** SolrJ's published POM depends on `solr-api`, Jetty's HTTP/2 client
  and Jackson — not on `solr-core`, `lucene-core`, or any analysis module. The classes the catalog
  documents are on the other side of that line: `FieldType` is `solr-core`, while
  `TokenizerFactory`, `TokenFilterFactory` and `CharFilterFactory` are Lucene's own
  (`org.apache.lucene.analysis`), with their implementations spread across `lucene-core` and the
  `lucene-analysis-*` modules that `solr-core` and `solr-analysis-extras` pull in transitively.
  `StandardTokenizerFactory`, the most-used tokenizer there is, lives in `lucene-core`. A project can
  depend on SolrJ and have none of them on its classpath.
- **Client and server versions can legitimately differ.** Nothing requires the SolrJ a project
  compiles against to match the Solr the configset is deployed to, and nothing in the build would
  notice if they diverged — so the client dependency's version is not a statement about the server.
- **Not every configset sits in a project with a SolrJ dependency at all.** A bare configset
  repository with no build file — the case the manual configset-root override in
  `SolrConfigsetSettings` exists for — has nothing to read a version from either way.

### What about downloading from Maven Central once a version is known?

Grant a trustworthy exact version and the two bullets above still stand: it is a graph rather than a
coordinate, so the IDE would need a dependency resolver running against whatever repositories the
user's project happens to have configured, and doing that on the editor path is what the offline
guarantee rules out.

What is left is the trade-off actually taken. Pre-computing the summary once per supported line,
from the pinned version in `supportedSolrLines` (`build.gradle.kts`), makes it "close enough" for the
line rather than exact for a patch release. Class-level Javadoc rarely changes within a major line,
and `SolrReferenceGuide` already prefers coarse-but-reliable to exact-but-fragile — so the accuracy
given up is small, and it is given up at build time where a mistake is visible, not at runtime where
it would be a failed fetch in front of a user.
