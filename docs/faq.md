# How quick documentation works

Everything the quick documentation popup says about *Solr's own classes* is read mechanically from
Solr's published artifacts at build time, for each supported Solr line, and shipped inside the
plugin. What it says about *your schema* is resolved live from the files in front of it. One small
table of [field](glossary.md#field) properties is maintained by hand. Nothing is copied from the
Reference Guide; the popup links to it instead.

The reason throughout: text extracted from an artifact Solr published cannot drift out of sync with
it, and text copied by hand always can.

## Where each line of the popup comes from

**A `class` value.** Quick Documentation (<kbd>Ctrl-Q</kbd>; ⌃J on a Mac) on
`class="solr.StandardTokenizerFactory"` in a schema:

![Quick documentation on a class value, with five regions marked: the short name and kind, the
fully-qualified class name, the one-sentence Javadoc summary, the accepted attributes, and the
Reference Guide link](images/03-quick-doc-class-annotated-light.png#only-light)
![Quick documentation on a class value, with five regions marked: the short name and kind, the
fully-qualified class name, the one-sentence Javadoc summary, the accepted attributes, and the
Reference Guide link](images/03-quick-doc-class-annotated-dark.png#only-dark)

| | What you see | Where it came from |
|---|---|---|
| ① | `solr.StandardTokenizerFactory — tokenizer factory` | Build time. The short name comes from the class's simple name; the *kind* from which Lucene service file listed it. |
| ② | `org.apache.lucene.analysis.standard.StandardTokenizerFactory` | Build time, from the scanned class file. It is a **Lucene** class, despite the `solr.` spelling a configset uses. |
| ③ | `Factory for StandardTokenizer.` | Build time, from Solr's and Lucene's sources jars: the Javadoc's first sentence, with `{@link}` and `{@code}` rendered to their text. |
| ④ | [`luceneMatchVersion`](glossary.md#lucenematchversion), `maxTokenLength — a whole number` | Build time, from the factory's constructor bytecode: what it reads from its arguments, and the type of each value. |
| ⑤ | `Tokenizers in the Reference Guide ↗` | Constructed, never fetched. The line beneath it names where the version came from — here, the configset's own `<luceneMatchVersion>`. |

Everything above the link is a lookup into a file generated when the plugin was built.

**A field.** The same gesture on `name="category"` asks a different question, and gets a different
mix:

![Quick documentation on a field, with four regions marked: the field and type header, the match
summary, the Value and From columns, and the Accepts and Meaning columns](images/02-quick-doc-field-annotated-light.png#only-light)
![Quick documentation on a field, with four regions marked: the field and type header, the match
summary, the Value and From columns, and the Accepts and Meaning columns](images/02-quick-doc-field-annotated-dark.png#only-dark)

| | What you see | Where it came from |
|---|---|---|
| ① | `field category of type string` | The schema file under the caret, parsed live. |
| ② | `Matches: whole value, case-sensitive.` | Computed by walking the type's analyzer chain. The caveat beneath it is the plugin's own sentence, because a reader who stops at "whole value" would draw the wrong conclusion about wildcards. |
| ③ | **Value** and **From** | Resolved live across three places — the field, then its type, then Solr's default — with **From** naming which one won. The Reference Guide cannot have this column, because it is about *your* schema. |
| ④ | **Property**, **Accepts**, **Meaning** | Maintained by hand inside the plugin. |

## What is maintained by hand, and why

Only ④: about twenty field properties. Analysis factories number well over a hundred and change
between Solr versions, so they are generated. Field properties are few, have stayed stable across
Solr majors, and cannot be generated anyway: Solr reads them out of an argument map, with defaults
decided in branching code rather than in any structure a build could enumerate.

A hand-maintained table may be incomplete; it may not be confidently wrong. Where Solr's default
depends on the field type and the plugin cannot settle it — `default`, `omitTermFreqAndPositions`,
`omitPositions` — the value reads *depends on the field type* and **From** says *see the guide*. A
property leaves that list when something mechanical can settle it: [`omitNorms`](glossary.md#omitnorms)
is decided by the field type's class, which the plugin knows, so it reads **true**, *Solr default for
solr.StrField*.

## Why link to the Reference Guide rather than copy it

The popup answers a question the Reference Guide cannot: not what `omitNorms` means in general, but
what it is for *this field in this schema*, and where that value came from. For the general
explanation it links to the guide:

- **Copied prose goes stale.** Carrying the guide's text inside the plugin would mean maintaining a
  second body of documentation on its own schedule. A link is always current and raises no licensing
  question.
- **A dead link is worse than none.** The guide's anchors come from its headings and change between
  releases, so the plugin links to whole pages only, and offers no link where it is not confident of
  the page.

The same reasoning keeps the generated Javadoc to one sentence. The first sentence is the one Solr's
authors write to stand alone; the rest is written to be read in context, which a popup does not have.
And because the summary is read from the sources jar Solr published for that exact release, it
cannot drift from the class it describes.

## Why per line, not per project

The catalog is generated once per supported line, from the release pinned in the build, rather than
resolved for each project: a configset names only a Lucene version, which identifies a Solr major line
but not an exact release, and the editor never contacts a server or a repository. The project's own
dependencies cannot settle it either:

- **[SolrJ](glossary.md#solrj) is matched only to ask whether a client is present**, by artifact id,
  never by version.
- **SolrJ does not carry `solr-core` or the Lucene analysis modules**, where the classes a configset
  names live.
- **A client's version need not match the server** the configset is deployed to.
- **A bare configset repository has no build file** to read a version from.
- **A missing sources jar means no summary rather than a guess.** The class keeps everything read
  from its bytecode.
