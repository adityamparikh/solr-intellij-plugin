# Inspection catalog

> **Who this is for.** Anyone deciding whether to trust, keep or disable one of these checks — a Solr
> developer meeting a warning for the first time, or a reviewer asking what the plugin claims before
> installing it. It is a reference: every registered inspection, once, with what it will and will not
> say.
> **Read first:** [Glossary](glossary.md) if Solr terms are new ·
> [User guide](user-guide.md) for the same checks in the order you meet them

The plugin registers **eleven** inspections. All of them appear in the IDE under
**Settings → Editor → Inspections → Solr**, and each has a description page there — the same text
this document is assembled from.

**The description files are the canonical wording.** `src/main/resources/inspectionDescriptions/`
holds one HTML page per inspection, the IDE shows it, and the Marketplace listing publishes it. What
follows is derived from those pages and from the registrations in `plugin.xml`; sentences are
shortened for a reader going through all eleven at once, and nothing here claims more than the page
it came from. Where a page and the code disagree, this document says so rather than choosing.

## How to read an entry

- **Reports** — the finding, in the file where the caret is.
- **Finding** — *defect* means the file is wrong: Solr refuses it, or accepts it and quietly does
  something other than what was written. *Observation* means the file is correct and the finding is a
  true fact about it. Ten of the eleven are defects; one is an observation, and it is
  [called out below](#the-one-finding-that-is-not-a-defect).
- **Severity** — as registered, which is the severity before any user change. **All eleven are
  registered `level="WARNING"` and `enabledByDefault="true"`**, so severity does not distinguish them
  and is not repeated per entry. Note that severity and *presentation* are separate: the unused
  field type is registered at `WARNING` and still renders greyed rather than underlined, because the
  highlight type decides the drawing.
- **Silent on** — what the rule deliberately declines to report. This is the part the IDE's own
  description page buries in prose and no settings screen shows at all, and it is where most of the
  design of these rules went: Solr configuration is full of syntax that resembles a field name, and a
  warning on a correct file is what gets a plugin uninstalled.

## The twelve at a glance

| Inspection (as the IDE names it) | Short name | File | Finding |
|---|---|---|---|
| Dangling copyField reference | `SolrDanglingCopyField` | schema | Defect |
| Unknown field type | `SolrUnknownFieldType` | schema | Defect |
| Unused field type | `SolrUnusedFieldType` | schema | **Observation** |
| Analyzer chain ordered so a component cannot work | `SolrAnalyzerChainOrder` | schema | Defect |
| Handler parameter names an undeclared field | `SolrUnknownFieldReference` | `solrconfig.xml` | Defect |
| Relevance parameter names a non-indexed field | `SolrNonIndexedRelevanceField` | `solrconfig.xml` | Defect |
| Faceting or sorting parameter names a field that cannot serve it | `SolrUnsupportedFieldOperation` | `solrconfig.xml` | Defect |
| Element Solr no longer accepts | `SolrDiscontinuedElement` | `solrconfig.xml` | Defect |
| Request parameter name is almost one Solr reads | `SolrMisspelledParameter` | `solrconfig.xml` | Defect |
| Unknown attribute | `SolrUnknownAttribute` | either, where the vocabulary is closed | Defect |
| Attribute value of the wrong kind | `SolrInvalidAttributeValue` | either, where the kind is known | Defect |
| Code names a field no configset declares | `SolrUnknownCodeField` | Java and Kotlin | Defect |

The last of those is the only one that fires outside a Solr file at all, and it gets a group of its
own below for the same reason: what it reads is application source, and what it checks against is
every configset in the project rather than the one file it sits in.

The groups below follow the same split
[code organization](code-organization.md) uses — which file the caret is in when the check fires —
with one honest exception. The two attribute checks live in the schema aspect's package and read the
generated class catalog rather than the schema, and neither gates on the file's kind: what decides
whether they speak is whether the catalog can prove the element's vocabulary closed. They get their
own group for that reason.

---

## Schema checks

Four rules, all firing with the caret in `managed-schema.xml` (or `schema.xml`, or the legacy
`managed-schema` — the plugin asks the file's kind rather than its name).

### Dangling copyField reference — `SolrDanglingCopyField`

**Reports** a `copyField` whose `source` or `dest` names a field the configset does not declare.

**Finding: defect.** Solr refuses to load a core with a dangling copy rule, so the failure arrives at
deploy time rather than while the file is being edited. Until then nothing in the editor can see it:
the reference is a bare string in an attribute, connected to the field it names by nothing but
convention.

**Silent on** names containing `*` — a glob source is a pattern over dynamic fields, and whether
anything matches it is not a question the schema alone can answer — and on the names Solr supplies
without the schema declaring them (`score`, `_version_`, `_root_`) and on document transformers
written in brackets. That last set is shared with the `solrconfig.xml` field checks below, which is
where it earns its keep.

**Quick-fix:** the declared fields closest in spelling, dynamic-field patterns included as
candidates.

### Unknown field type — `SolrUnknownFieldType`

**Reports** a `field` or `dynamicField` whose `type` names a field type the configset does not
declare.

**Finding: defect.** Solr refuses to load a core with an unknown field type. The mistake is easy to
make while editing, because generic word completion will offer any word already present in the file —
including attribute names such as `stored`.

**Silent on** a `type` attribute that is absent or empty. A missing type is a different defect with a
different message, and reporting it here would put `unknown type ''` in front of the user.

**Quick-fix:** the declared types closest in spelling.

### Unused field type — `SolrUnusedFieldType`

**Reports** a `fieldType` that no `field`, `dynamicField` or other field type in the configset names.

**Finding: observation — the only one here.** Nothing is wrong: Solr loads a schema full of unused
types without complaint. The cost is paid by the next reader, who has to work out which of the
declarations in front of them the index actually depends on, and by the next author, who tunes an
analyzer chain no document has passed through in years. See
[the one finding that is not a defect](#the-one-finding-that-is-not-a-defect) for what that means for
its presentation.

**Silent on** a type declaration with no `name` yet — a half-typed declaration is unused by
definition, and reporting it would grey out every type the moment the user starts typing one — and
**silent on the whole file** for a schema that pulls its declarations in with `<xi:include>`, because
the fields naming these types are then in a file this reading never sees. A `dynamicField` counts as
a use whether or not any document matches its pattern, and so does another type's `subFieldType`, the
attribute through which `PointType` and `LatLonType` delegate each dimension to a numeric type.

**No quick-fix.** Whether an unused type is a leftover or a provision for fields not written yet is a
judgement the editor cannot make.

### Analyzer chain ordered so a component cannot work — `SolrAnalyzerChainOrder`

**Reports** an analyzer component placed where the components above it have already made its work
impossible. Two orderings, both provable from the order alone:

- a **graph flattener above every filter that produces a graph** — `FlattenGraphFilterFactory`
  flattens the token graph a `SynonymGraphFilterFactory` or `WordDelimiterGraphFilterFactory` emits,
  and a pipeline stage cannot flatten output produced after it runs;
- **`splitOnCaseChange` below a filter that folds case** — the option splits `iPhone` at the case
  transition, and a `LowerCaseFilterFactory` above it has already turned that into `iphone`. The
  tokenizer counts too: `LowerCaseTokenizerFactory` folds as it splits.

**Finding: defect.** It is the hardest kind of schema defect to see: nothing is misspelled, every
class exists, every attribute is legal, and Solr starts without complaint. The filter simply never
does anything, and the only evidence is a search that quietly fails to match. Both the index-time and
query-time chains are checked.

**Silent on** a great deal, and every exclusion is load-bearing:

- a chain that legitimately flattens **twice**, once after each producer — the second flattener has a
  producer above it;
- any chain whose **tokenizer emits a graph of its own** — `JapaneseTokenizerFactory` and
  `KoreanTokenizerFactory` segment compounds into a graph and run above every filter, so a flattener
  below one has work to do wherever it sits;
- a flattener in a chain with **no graph filter at all**, which is equally inert and cannot be proved
  so from ordering;
- a **defaulted** `splitOnCaseChange`. The rule fires only on the attribute *written* in the file:
  Solr leaves the option on unless set, so most chains with a lowercase filter above a word-delimiter
  filter are technically in this state and their authors never asked for case splitting;
- a `splitOnCaseChange` value that is **not an integer**, `true` included — Solr reads it with
  `Integer.parseInt` and refuses the core, which is
  [the invalid-value rule's](#attribute-value-of-the-wrong-kind--solrinvalidattributevalue) finding
  and a different repair;
- an ordering that is **merely unusual**. Analyzer chains are where expert users deliberately do
  surprising things, and an inspection with opinions about style would fire constantly on chains
  working exactly as intended.

**No quick-fix.** The repair is to move a filter, and which one to move is a question about intent.

---

## `solrconfig.xml` checks

Five rules, all declining outright unless the file's kind is `solrconfig.xml` and the project has a
configset the plugin was told to treat as Solr's.

### Handler parameter names an undeclared field — `SolrUnknownFieldReference`

**Reports** a request-handler parameter naming a field the schema does not declare.

**Finding: defect.** This crosses the boundary between the two files, and it is the quietest failure
in a Solr configuration. A `qf` naming a field that was renamed in the schema is not an error to
Solr — the parameter is just a string. Queries return fewer results, or none, and nothing reports
anything.

**Reads eighteen parameters and no others**: `qf`, `pf`, `pf2`, `pf3`, `bf`, `boost`, `sort`,
`group.sort`, `df`, `fl`, `facet.field`, `facet.pivot`, `group.field`, `hl.fl`, `stats.field`,
`uniqueKey`, `terms.fl` and `mlt.fl`. Solr has hundreds, and guessing which hold field names from
their values would produce false references — worse than missing ones, because a false reference
becomes a false "no such field" warning on a parameter that was never a field name at all.

**Each is split the way Solr splits it, which is not one rule.** `fl` and its kin accept commas or any
whitespace; `mlt.fl` accepts a comma or a single space; and `terms.fl` is not split at all — Solr reads
every *occurrence* of that parameter and looks up each value whole, so a comma in one is part of the
field name. Reading them all alike would report two healthy fields where Solr sees one missing one.

**Silent on** everything in those parameters that is not a bare name: function queries, globs, field
aliases, parameter references, numeric constants such as the `1.5` a flat `boost` is written as, and
document transformers such as `[docid]`, `[explain]` and `[child]`. Also on the names Solr supplies
itself — `score` above all, which is in more `fl` parameters than not.

**Quick-fix:** the declared fields closest in spelling.

### Relevance parameter names a non-indexed field — `SolrNonIndexedRelevanceField`

**Reports** a query-field parameter naming a field the schema declares but which no query can search.
The field is there, spelled correctly, and the parameter still does nothing: no clause in the query
and no boost to the score.

**Finding: defect.** Solr does not complain, the handler still answers, and results are ranked by
everything except the field the author meant to emphasise.

**Searchable is a disjunction, not a single attribute.** A field is searchable when it is `indexed`
*or* when it has `docValues` — Solr turns an exact match on a doc-values-only field into a range
query over the doc values rather than refusing it. So a field written
`indexed="false" docValues="true"` is **not** reported: the parameter works. What is reported is a
field with neither. The registered display name still says "non-indexed", which names the common half
rather than the rule.

**Reads four parameters**: `qf`, `pf`, `pf2` and `pf3` — the ones whose values become term and phrase
queries.

**Silent on** `bf` and `boost`, deliberately: their values are function queries, read per document
rather than from the index, so boosting on a non-indexed field there is correct and common. Silent
too wherever the property it needs is **undetermined** — a field type naming a class this build has
never seen — because only a definite *no* is reported and asserting a default for a custom type is
how an inspection starts inventing one. A dynamic field is checked like any other, since a `qf` token
resolves here by Solr's own longest-literal rule.

**No quick-fix.** The two repairs are to index the field or to name a different one; the first edits
another file and forces a reindex, the second changes the query. Neither is a typo correction.

### Faceting or sorting parameter names a field that cannot serve it — `SolrUnsupportedFieldOperation`

**Reports** a faceting or sorting parameter naming a field with neither doc values nor an
un-invertible index — and, for sorting, a field with more than one value per document.

**Finding: defect, and the one rule here that guards against silence rather than against a false
positive.** This is not a subtle inefficiency: it is a request Solr refuses, so the handler carrying
it answers every query with an error, and nothing in the configset says so. Faceting and sorting read
a field's values per document rather than its terms; doc values serve them directly, an inverted
index serves them only by being un-inverted into memory first, and `uninvertible` — which governs
that — defaults *false* from schema version 1.7. A field that is perfectly searchable can therefore
be unfacetable, and the same field may deserve a warning in a `facet.field` and none in a `qf`.

**Reads five parameters**: `facet.field` and `facet.pivot` for faceting, `sort`, `group.sort` and
`group.field` for sorting.

**Two different warnings**, because the requirements differ: faceting names doc values or an
un-invertible index; sorting names those *and* a single value per document. One shared sentence told
a reader that faceting needs a single value, which it does not — multiValued fields are exactly what
one facets on.

**Silent on** an undeclared field, which is
[the unknown-reference rule's](#handler-parameter-names-an-undeclared-field--solrunknownfieldreference)
finding and saying it twice on one underline is worse than saying it once; and silent wherever a
property is undetermined, as above.

**No quick-fix.** The repairs are to add doc values, to make the field un-invertible, or to facet on
something else — none of them a correction of the text under the caret.

### Element Solr no longer accepts — `SolrDiscontinuedElement`

**Reports** an element in `solrconfig.xml` that the targeted Solr no longer accepts, **in Solr's own
sentence** rather than a paraphrase, because that sentence names the replacement — the notice
retiring `<indexDefaults>` is what tells a reader to use `<indexConfig>` instead.

**Finding: defect, and for four of the five, fatal.** `<indexDefaults>` and `<mainIndex>` make Solr
raise `SolrException` and the core does not start; `<nrtMode>` and `<unlockOnStartup>` fail the same
way one level down. `<jmx>` is the exception Solr merely logs about, pointing at `solr.xml` instead;
it is reported the same way because the message is Solr's own either way.

**Exactly five elements are known retired**, on both supported Solr lines: `indexDefaults`,
`mainIndex` and `jmx` at the top level, `nrtMode` and `unlockOnStartup` under `<indexConfig>`.

**Silent on** everything else: nothing is inferred. An element is reported only where the generated
vocabulary carries a retirement notice for it, which is a literal Solr ships beside the code that
rejects it — and only **in the position Solr reads it**, so the same name inside a custom component
is left alone. The underline sits on the start-tag name rather than the whole element, so a
discontinued `<indexDefaults>` does not bury the configuration a reader still has to move.

### Request parameter name is almost one Solr reads — `SolrMisspelledParameter`

**Reports** a request parameter whose name is *almost* one Solr reads.

**Finding: defect.** A parameter Solr does not recognise is silently ignored. A handler configured
with `rwos` instead of `rows` starts cleanly, serves queries, and returns the default ten results
forever — there is no error anywhere, and the only symptom is a number nobody chose.

**Silent on** an unrecognised name on its own, which is the ordinary case: `solrconfig.xml` accepts
components from outside Solr that read parameters of their author's choosing, and flagging absence
would warn on every project with a custom component. **Never fires on a name Solr ships**, however
close it sits to another — `pf2` and `pf3` are genuinely different parameters one edit apart, and a
rule comparing every name against every other would report each as a misspelling of the other in a
file that is entirely correct. Knownness is checked first and decides the matter. Silent too on a
name the catalog knows members *below*: Solr's convention is that `X` switches a component on while
`X.*` configures it, so `<str name="spellcheck">on</str>` — the idiom all four shipped configsets
use — is a family root and not a typo of `spellcheck.q`.

**Quick-fix:** the parameter Solr reads.

---

## Catalog-backed attribute checks

Two rules that ask the generated class catalog what an element accepts. Both are silent on a class
the catalog does not know, which is how a custom plugin stays unflagged without anything having to
recognise it as custom.

### Unknown attribute — `SolrUnknownAttribute`

**Reports** an attribute the element cannot accept — `indexd="true"`, `maxGramSiz="15"`. The
underline sits on the attribute *name*, not its value, since the value may well be correct.

**Finding: defect.** Solr reports these only when the core loads — the schema throws
`Invalid field property`, the factory `Unknown parameters` — and a core load may be a production
reload long after the edit.

**Fires only where the set of legal attributes is genuinely complete**: on `<field>` and
`<dynamicField>`, and on an analysis component whose class the generated catalog knows.

**Silent on** a `<fieldType>`, always: it delegates to classes its own configuration names — a
`providerClass` selects the provider that reads `currencyConfig` — so its attribute list is open by
construction. Silent on a component naming a class outside Solr, and silent on a known class from
which no attributes were recovered, since every analysis class Solr ships recovers at least
`luceneMatchVersion` and an empty list means the extraction failed rather than that the class is
bare. The rule this deliberately does not follow is *validation by absence*.

**Quick-fix:** the accepted attributes closest in spelling.

### Attribute value of the wrong kind — `SolrInvalidAttributeValue`

**Reports** an attribute whose value cannot be what the attribute accepts — `indexed="yes"`,
`positionIncrementGap="foo"`, `minGramSize="2.5"`.

**Finding: defect.** Solr rejects these when the core loads.

**Where the kinds come from.** A field's and a field type's properties are modelled directly; an
analysis component's attributes are recovered from its constructor bytecode when the catalog is
generated, which is also where the kind comes from — the method a factory used to read the value says
what the value is.

**Silent on** any legal value that is merely a poor choice: the claim is that a value is *not of the
right kind*, never that it is unwise, so `positionIncrementGap="-1"` is an integer and is left alone.
Silent wherever the kind is not positively known, and **never judges a value containing `${...}`**,
which Solr's resource loader substitutes, possibly from a system property set outside the repository.
Booleans are matched ignoring case, so `indexed="TRUE"` is a field that works and is not reported.

**Quick-fix:** the accepted members of a closed set, or `true`/`false` for a boolean.

---

## The one finding that is not a defect

`SolrUnusedFieldType` is the odd one out, and the difference is worth stating plainly: **a field type
nothing uses is dead weight, not a mistake.** Solr loads such a schema without a murmur, and Solr's
own `_default` configset ships a deliberate palette of language and spatial types for fields the
copier has not written yet. Every one of them is correctly reported as unused, and every report is
true.

So its presentation differs from the other ten. The declaration is **greyed out rather than
underlined** — the platform's own vocabulary for a declaration nothing reads — and no quick-fix is
offered. It is enabled by default all the same: the grey-out costs a reader nothing, and dead
configuration is how a schema becomes unreadable.

**Its presentation is a recorded open question, not a settled decision and not something being
changed.** The complaint against it is volume: on the configsets Apache Solr itself ships it produces
dozens of Problems-view entries about files that are entirely correct, which is why it is the one
rule the plugin's zero-false-positive gate holds out by name — with a separate test asserting it does
still fire, so silencing it is not a way to pass. Lowering the severity to `INFORMATION` was measured
and does not work: the platform drops `INFORMATION` inspections from the daemon and the grey-out goes
with them. The shape that would answer it is an annotator, the same mechanism
[the restated-default dim](user-guide.md) already uses.
[The plan](../specs/plans/0002-solr-intellij-plugin-plan.md) records this beside that feature.
Recorded, not decided.

---

## How this document is kept true

Every count above was checked against the repository rather than counted by eye:

- **Eleven inspections.** `<localInspection>` registrations in `plugin.xml`, and eleven files in
  `src/main/resources/inspectionDescriptions/`, whose names match the registered short names exactly
  — a correspondence `SolrPluginDescriptorTest` also asserts, since a description file the IDE cannot
  find is a blank page in Settings.
- **Every severity.** Eleven `level="WARNING"` attributes, read from the registrations rather than
  inferred from the classes.
- **Group sizes.** Six inspection classes under `configset.schema.inspection` and five under
  `configset.solrconfig.inspection` — the two attribute checks in this document's third group sit in
  the first of those packages.
- **Five discontinued elements**, identical on both supported Solr lines, counted from the generated
  element catalogs.
- **The parameter lists.** Sixteen parameters read for field names; of those, four ask for searching,
  two for faceting and three for sorting. Counted from the tables in the `solrconfig.xml` parser,
  which is the single place they are declared.

---

## Code checks

### Code names a field no configset declares — `SolrUnknownCodeField`

**Reports** a Solr field name written in Java or Kotlin that no configset in the project declares.

**Finding: defect.** This is the failure the specification opens with, and it is quieter than any
configuration mistake. A field name inside `addFilterQuery("categry:books")` or `@Field("prce")` is a
string: it compiles, it deploys, and Solr answers a query against a field that does not exist with
zero results rather than an error. The typo reaches production as an empty page, and nothing between
the two has any reason to look at it.

**Reads Java and Kotlin through one implementation.** UAST is a read-only view each JVM language
implements, so `SolrQuery` builder calls, raw parameter strings and `@Field` annotations are
recognized identically in both. Groovy is not supported and the plan records why: its UAST provider
converts annotations but not calls, so half of this would be silently dark there.

**Runs only where a Solr client is on the module's classpath.** The gate is the module rather than
the project, so a repository in which one module talks to Solr does not have the others warned about
their field names.

**Silent on names the source does not spell out.** A name held in a variable, built by
interpolation, or read from a constant is not reported: following values through a program is
best-effort by nature, and this prefers silence. Also silent on the names Solr supplies itself, and
on anything carrying a wildcard.

**Silent, above all, in a project with no configset to check against.** A service talking to a Solr
whose schema lives in another repository is an ordinary deployment, and a check that cannot see must
not accuse. That same rule is what keeps it honest during indexing, when the configsets are not yet
findable — the inspection waits rather than reading "none found" as "none declared", which would
underline every field name in the codebase at once.

**Quick-fix:** the declared fields closest in spelling.
