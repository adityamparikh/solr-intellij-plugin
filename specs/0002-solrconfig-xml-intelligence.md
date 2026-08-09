---
specbuddy-type: spec
plan-file: specs/plans/0002-solr-intellij-plugin-plan.md
---

# solrconfig.xml Intelligence

## Overview

`solrconfig.xml` is the Solr configuration file developers edit most often, and the one this plugin
understands least. Today it gets exactly one capability: field names mentioned in a handful of known
parameters are references, so `<str name="qf">name^3 description</str>` navigates to the schema and
warns when a name is not declared. The elements, their attributes, the parameter names themselves and
the plugin classes are all unmodelled.

This specification covers giving `solrconfig.xml` the same treatment the schema already has —
completion, quick documentation, navigation, and one narrow inspection — under the constraint that
makes the whole thing possible: **the plugin may only assert what it positively knows, and must stay
silent about everything else.** Most of this file will never be modelled, and a reader must never see
correct Solr underlined.

It is a specification for a *slice* of the plugin described in
[`specs/0002-solr-intellij-plugin.md`](0002-solr-intellij-plugin.md), which owns the product intent.
This document owns what "solrconfig.xml as a first-class surface" means concretely. The reasoning
behind each decision below lives in
[`docs/design/pending/2026-08-07-solrconfig-intelligence/design.md`](../docs/design/pending/2026-08-07-solrconfig-intelligence/design.md)
and its prerequisite,
[`docs/design/pending/2026-08-07-solrconfig-catalog/design.md`](../docs/design/pending/2026-08-07-solrconfig-catalog/design.md).

## Goals

- **Element and attribute completion inside `solrconfig.xml`**, drawn from what the plugin knows,
  replacing the platform's schema-less sibling echo.
- **Parameter-name completion and quick documentation** inside `<lst name="defaults">`, `appends` and
  `invariants` of a request handler, search component or `initParams` block.
- **Field-name completion inside the parameters already known to hold field names**, and quick
  documentation on a field name written in one — the inverse of the unknown-field warning that already
  ships, from the same two lists, and needing no generated data that does not exist.
- **Quick documentation on the elements, attributes and classes** the plugin models, with a versioned
  Reference Guide link as the substantive half of the answer.
- **Ctrl-click from a `class` attribute** to the Java class it names, when that class is on the
  project's classpath.
- **One inspection: near-miss correction of a parameter name**, with a quick fix, and nothing else.
- **Both configsets Solr ships, and a configset naming a custom plugin, produce zero findings.**

## Non-Goals

- **Validation by absence, in any form.** A parameter the catalog does not carry is not invalid — any
  Solr component may read any name out of `SolrParams`. A class the catalog has never heard of is
  somebody's custom plugin. This is the constraint the rest of the document is shaped around.
- **Modelling the whole file.** `<autoCommit>`, `<updateLog>`, `<circuitBreaker>` and the cache-sizing
  elements are structure no generator can derive and this specification does not hand-write. They
  fall to the permissive descriptor and stay silent, exactly as an unknown schema element does today.
- **Parameter value *grammars*.** `defType` has a closed set of parsers; `rows` has none; `bf` holds a
  function query with its own grammar, and `fq` holds full query syntax where a field name is a
  fragment of the value rather than the whole of it. Rather than draw that line badly, value grammars
  stay with the platform.

  **This excludes grammars, not field names, and an earlier revision of this document conflated the
  two.** Completing a function query is a parser problem; completing a *field name* into a parameter
  the plugin already knows holds field names is the field list crossed with a sixteen-name list, both
  of which exist today. See [FR-9](#requirements). The distinction matters because the conflated
  version foreclosed the highest-value unblocked capability in this area on the strength of an argument
  that only applies to `bf`.

  **This narrows the plan, deliberately, and the narrowing is recorded here because otherwise Step 25
  reads as unfulfilled.** That step's third action authorises two validations — "a misspelling of a
  name it knows, *a value outside a set it knows to be closed*". The first becomes
  [FR-7](#requirements); the second is declined. The reason is that "a set it knows to be closed" is
  only knowable for a minority of parameters, and a value inspection that fires on the closed minority
  while silent on the rest teaches the reader that an unflagged value was checked. When the catalog can
  prove closedness from bytecode rather than from a curated list, this is worth reopening as its own
  step.
- **Extending the field-reference rules.** Which parameters hold *field names* stays with the
  parser's existing sixteen names. See [Two lists, two questions](#two-lists-two-questions).
- **Per-handler parameter scoping as a validation basis.** Knowing which params family declares `mm`
  does not make `mm` illegal under a `defType=lucene` handler. Scoping may rank a completion list; it
  may not fire an inspection.
- **The Config API and `configoverlay.json`.** Reconciling an API-written JSON overlay against the
  file it overlays is a real feature and it is not this one.
- **`solrconfig.xml` declarations as Find Usages targets.** Declined already: a request handler's
  `name` is referenced from nowhere the plugin models, so targeting it would return an empty list and
  teach the reader the search is broken.

## Background

### What the file gets today, and what that costs

The schema was rescued from the platform's schema-less mode by a descriptor provider that supplies a
vocabulary where no XSD exists. That provider declines `solrconfig.xml` by design, so
`solrconfig.xml` is still in schema-less mode — where attribute-name completion echoes whatever
attributes same-named tags elsewhere in the file happen to carry.

`solrconfig.xml` is a file *made of* same-named tags. Every `<str>` is a sibling of every other
`<str>`, which is the worst possible input to an echo. **The first fixture must pin the actual
present-day behaviour before any work leans on this claim** — it is inferred from the plugin's own
account of the platform, not measured.

Element-name completion is absent rather than wrong, and that half is measured: the schema
vocabulary answers for the schema root and `fieldType` and returns empty for everything else, so
typing `<` inside `<config>` contributes nothing.

Quick documentation says nothing about any of it, and the four `class` values in the demo file are
inert strings.

### The parameter model is narrower than it looks

Two bounds in the existing parameter bridge matter to anything built on top of it:

| Bound | Consequence |
|---|---|
| Only `<str>` is read as a value tag | `<int name="rows">10</int>` is equally legal Solr and entirely invisible — as are `<bool>`, `<long>`, `<float>` and `<double>` |
| Three parameter carriers: `requestHandler`, `searchComponent`, `initParams` | Correct for field references, and the right scope for parameter completion too |

The `<str>`-only bound is invisible in the demo file, which happens to write `<str name="rows">`. It
stops being invisible the moment completion offers parameter names: a reader who accepts `rows` and
types `<int name="rows">` has left the modelled subset without being told.

## Requirements

### Functional

**FR-1 — Element completion.** Inside `<config>`, completion offers the plugin elements
`solrconfig.xml` accepts. Unknown children remain legal and unflagged.

**FR-2 — Attribute completion.** On a modelled plugin element, completion offers `name`, `class`, and
the attributes the named class reads — resolved through the `class` attribute exactly as a
`<filter>`'s attributes are resolved today. Attribute completion inside `<requestHandler>` must not
offer a schema field property; that negative is the direct analogue of the sibling echo the schema
descriptor was built to stop.

**FR-3 — Value-tag completion.** Inside a parameter list, completion offers the value tags Solr
accepts — `<str>`, `<int>`, `<bool>`, `<arr>`, `<lst>` — and the plugin reads all of them, not only
`<str>`.

**FR-4 — Parameter-name completion.** In the `name` attribute value of a parameter tag, completion
offers the query parameter names the catalog carries. The position test is not the tag name: it is
that the *enclosing* `<lst>` is a parameter list under a parameter carrier. `<lst name="defaults">`
also appears under an update processor chain, where its contents are not query parameters — that
position must offer nothing. `SolrConfigParameters.enclosingIsParameterList` performs precisely this
check and must be reused rather than restated — its KDoc is where the reason the enclosing check is
not optional is already written down.

**It is `private`, so reuse is a change rather than an import.** Widening its visibility is the
mechanical part of this requirement and carries a KDoc obligation, since Dokka gates undocumented
public API. Restating the predicate in the completion contributor is the failure to avoid: the two
copies would agree until an update processor chain grew a second parameter-list spelling.

**FR-5 — Parameter documentation.** Quick documentation on a parameter name answers what it is, which
params family it belongs to, and its summary where Solr's sources carry one. Where no summary exists,
the popup shows the name and the family and stops — the established precedent for a class with no
class-level Javadoc, and parameters will hit it more often than classes do. A versioned Reference
Guide link is the other half, and is worth more here than for a factory: a parameter's real
documentation lives on its guide page and Solr's constant-field comments will never approach it.

**FR-6 — Class navigation.** A `class` attribute value resolves as a reference to the Java class it
names, including the `solr.`-prefixed short form. Ctrl-click lands in the class or its sources.
**Resolution must degrade to nothing, not to a warning** — a class absent from the project classpath
is the normal case, since the user is editing a configset rather than building Solr. The reference is
soft, exactly as the existing four reference providers are soft.

**FR-7 — The one inspection.** A parameter name written inside a parameter list fires a warning only
when it is **not** in the catalog *and* exactly one catalog name is within edit distance one; the
quick fix replaces it. `<str name="qff">` where `qf` exists is the case.

This needs a guard the schema's equivalent does not: Solr's parameter families genuinely contain
distinct names one edit apart — `pf2` and `pf3`, `ps2` and `ps3` — so a rule that fires on a *known*
name would flag `pf3` as a misspelling of `pf2`.

**FR-8 — What must stay silent.**

| Not flagged | Because |
|---|---|
| A parameter the catalog does not know | Any component may read any name out of `SolrParams`. Absence proves nothing |
| A class the catalog does not know | `solrconfig.xml` accepts plugin classes from outside Solr. This is the false positive that would fire on every project with a custom component |
| A known parameter in a handler that may not read it | Solr reads parameters permissively; an unread parameter is inert, not rejected |
| An element the plugin does not model | Most of the file. The permissive descriptor is what keeps this quiet |

**FR-9 — Field-name completion inside a field-holding parameter.** Inside the text of a parameter the
parser already knows holds field names — `qf`, `pf`, `fl`, `df`, `sort` and the rest of the sixteen —
completion offers the fields and dynamic-field patterns the schema declares.

**This is the inverse of a capability that already ships**, which is the whole argument for it: the
unknown-field inspection tells a reader that `descriptoin` is not a field, and the plugin holding the
list needed to say so is the same list needed to offer `description` before it is mistyped. Today the
completion contributor has no `solrconfig.xml` awareness at all, so a reader gets the correction and
never the suggestion.

**Scope is the sixteen names and nothing wider.** The parameter must be one the parser reads, because
that list is what distinguishes a value holding field names from a value holding a number, a parser
name or a function query — the same reason it exists for references. `ps` holds phrase slop and gets
nothing; `fq` holds query syntax and is [an open question](#open-questions) rather than an omission.

**Boost and sort syntax must survive.** `qf` completion offers `name`, not `name^3`, and a caret after
`name^` is inside a boost rather than a field name — completing there would produce `name^name`.
Likewise `sort` takes `field direction`, so the second token is not a field. The occurrence mapping
that already locates field names within these values is what knows the difference.

**A constant is not a field name either, and reading one as a field was a live defect.** `boost` takes a
multiplier and `bf` an additive function, so a flat number in either is ordinary Solr — and the rule that
rejects glob, function, alias and transformer syntax had no case for a number, so `<str name="boost">1.5</str>`
produced a warning about a field nobody could declare. Both directions need it: a number must not be read
as a reference, and must not be offered as one.

**FR-11 — The non-indexed relevance warning must not fire on a field that carries doc values.** This
corrects the inspection that already ships, and it is a requirement here because
[FR-9](#requirements) cannot be built around a rule that is wrong: the list completion offers and the
inspection that judges what was written must agree, or the plugin suggests a field it then underlines.

**Solr answers a query against a doc-values-only field, and the mechanism is explicit in the
bytecode** — verified against both supported lines rather than recalled:

`FieldType.getFieldQuery` opens with `hasDocValues() && !indexed()`, and on that branch delegates to
`getRangeQuery` with the value as both bounds, which reaches
`SortedSetDocValuesField.newSlowRangeQuery`. An exact match becomes a single-value doc-values range
query. Slower than a term lookup, and functional. `StrField` overrides none of it. So a `qf` naming a
doc-values-only string field *does* search it, and the warning's claim that it "cannot search or boost
it" is false.

**The inspection's original case is untouched and remains correct.** `TextField` overrides
`getFieldQuery` for the analysis path and declares no doc-values support at all, so a non-indexed
*text* field in a `qf` is genuinely unsearchable — which is what the inspection was written for and
what its other fixtures assert.

**One nuance worth carrying into the fix rather than discovering later.** A doc-values range query is
constant-scoring, so a boost on such a field multiplies a constant rather than a relevance score. The
field is searchable and boostable; what it is not is *ranked* by term statistics. That makes silence
correct and makes a reworded warning the wrong repair.

**FR-12 — Which operations a field supports is a fact about the model, not about an inspection.** The
rule FR-11 corrects must live in `org.apache.solr.ide.model` beside the rest of the field model, and the
configuration surface is the least important of its consumers.

**It has one in each of the plugin's three tracks, which is what makes the model the only defensible
home:**

| Track | Asks |
|---|---|
| Configuration | Is this `qf`, `facet.field` or `sort` in `solrconfig.xml` naming a field that can serve it? |
| Code | Can this `addFacetField("x")` or sort-by-`x` in SolrJ work, or will the server reject it? |
| Query console | Which fields should completion *offer* for this parameter, when the reader is composing a query against a live core? |

Three implementations of "is this field facetable" is three chances to disagree, across surfaces the
parent specification exists to make agree — its stated purpose is connecting the three "through a single
shared model of what fields exist and what they can do". **These rules are the "what they can do" half,
and they currently live in one inspection.**

The console consumer is the one that changes the shape rather than merely adding a caller: an inspection
asks *is this wrong*, and completion asks *what may I write*. A rule that only answers the first as a
boolean cannot rank or filter a completion list, so the model fact should name the operations a field
supports rather than answer one question per call site.

`SolrMatchCapability` is the precedent and the shape to follow: it models what a field can *match*, from
its analyzer chain, imports no IntelliJ type, and is read by six surfaces. What is missing is its sibling
— which *operations* a field supports.

| Operation | Rule | Expressed today |
|---|---|---|
| Search and boost | `indexed`, or `docValues` for a non-text type | Partly, and wrongly, inside one inspection |
| Filter | `indexed` or `docValues` | Nowhere — `fq` has no references at all |
| **Facet** | `docValues`, or `indexed` and uninvertible | **Nowhere.** A `facet.field` naming a field with neither is accepted silently, and Solr fails the request |
| **Sort** | the facet rule, **and** not `multiValued` — several values have no defined order, so Solr requires a selector | **Nowhere**, same gap |
| Highlight | `stored`, and the chain for the faster highlighters | Nowhere |

**Every rule there is a disjunction, and the plugin has never expressed one** — every property check
today resolves one property and compares it. `SolrFieldProperties` already resolves `indexed`,
`docValues`, `stored` and `multiValued` three tiers deep, so the facts are present and only the combining
rule is absent.

**The facet and sort rows are a different failure from the rest of this document.** Everywhere else the
risk is firing on a correct file; there the plugin stays silent on a configuration Solr will reject
outright. Both are worth having for that reason.

**FR-10 — Quick documentation on a field name in a parameter value.** Hovering `name` inside
`<str name="qf">name^3</str>` answers with the field's documentation, as hovering its declaration does.

**It already works, through a path nobody wrote for it.**
`getCustomDocumentationElement` claims attribute values and schema tags; a field name in `qf` is tag
*text* and matches neither, so the provider returns null — and null lets the platform resolve the
reference at the caret and document its target instead, which the reference contributor supplies. So
this requirement is satisfied by a test pinning an undocumented capability rather than by new code.
**Its value is that the behaviour was unasserted and unclaimed**, which is one refactor away from an
unnoticed regression. **Regression coverage:** `SolrConfigFieldReferenceTest.testHoverOnFieldNameInQfParameterResolvesDocumentation`
asserts that hover on a field name inside a `qf` parameter resolves to the field's documentation via
reference resolution when `SolrConfigsetDocumentationProvider` declines the position.

### Non-functional

- **Nothing on the editor path contacts a server**, and no feature here reads an index. Sources are
  the configset's own text, the generated catalog resource, and the cheap local detector.
- **Dumb-aware throughout, with one exception that must be honest about it.** Java PSI resolution is
  index-backed, so the `class` reference provider must not claim dumb-awareness the way the rest do.
- **Completion latency.** Parameter completion resolves against the catalog on every keystroke inside
  a parameter list. The current catalog is a per-line cached list scanned linearly — fine at 185
  entries, worth measuring at several hundred.
- **No new cache in front of the configset model reader.** It already caches through the platform;
  any new fact read from a file must join the reader's declared sources or the model goes stale after
  the first edit.
- **The existing field-reference behaviour is unchanged.** `qf` still navigates, a dynamic-field
  suffix still resolves, and the non-indexed-relevance warning still fires.

## Technical Design

### The first increment is the one with no dependencies

Every capability above is blocked on something — the catalog population, the element-structure
question, or the Java-dependency decision. Read as a whole this specification therefore looks
unstartable, and that reading is wrong: three pieces of it are unblocked today, they are the pieces
everything else sits on, and they belong in the first pull request.

1. **Pin the present-day behaviour.** A fixture recording what attribute completion actually offers
   inside `solrconfig.xml` now. Until this exists, the sibling-echo claim this specification's
   [Background](#what-the-file-gets-today-and-what-that-costs) rests on is inference.
2. **Unify the file-kind predicate**, per [the descriptor section](#widen-the-descriptor-gate-keep-the-permissiveness) — one documented
   "schema and `solrconfig.xml`" predicate, replacing the private copy, with no behaviour change.
3. **Widen the value tags and the enclosing-list predicate's visibility** — `<int>`, `<bool>`,
   `<long>`, `<float>` and `<double>` read as parameters, and
   `enclosingIsParameterList` reachable by a future completion contributor.

None of the three adds a user-visible feature, and that is the point: they are the changes most likely
to disturb the schema surface, so they land while **the schema suite is the only thing that can fail**
rather than underneath a new feature whose own tests would mask a regression. This is the same
argument the [Risks](#risks) section makes for the descriptor commit standing alone.

**Two of this specification's test criteria are not in Step 25's success criteria and must be added
when the step is split**: the `pf2`/`pf3` guard, and the present-day-behaviour fixture above. Both
catch silent wrongness rather than visible failure, which is exactly the kind of criterion a split
loses.

### This depends on a catalog population that does not exist yet

Three of the four capabilities are blocked on
[extending the catalog to `solrconfig.xml`'s classes and parameters](../docs/design/pending/2026-08-07-solrconfig-catalog/design.md).
The catalog's four kinds are all schema vocabulary; nothing corresponds to a request handler. Two
findings make the extension tractable, both verified against the resolved artifact rather than
recalled:

- Solr's plugin superclasses are present in `solr-core-10.0.0.jar` at the expected paths, so the
  hierarchy pass that already finds field types will find them given more roots.
- Parameter names are readable as inlined constants off the `org.apache.solr.common.params` interface
  family in `solr-solrj`, with a curation problem in `CommonParams` that the catalog record owns.

**Nothing here should be built before that record's per-kind representative assertions pass**, because
every symptom of a generator that produced an empty kind looks identical to a completion contributor
that was never wired up.

### Element structure: Solr declares its own element names

The parent specification treats the two configsets Solr ships as ground truth for element structure,
and **they are not on any path the build has today** — `solr-core` contains no XML resources; those
configsets ship in the distribution tarball, and the build resolves Maven artifacts. That left three
routes: download the tarball per line, vendor two files per line, or derive the elements from Solr's
own declaration.

**The third is settled and the other two are retired.** `SolrConfig` carries
`public static final List<SolrPluginInfo> plugins`, and each entry pairs a `tag` string with the
`Class<?>` that tag's `class` attribute must implement. Both are plain constants in the static
initializer, so one pass over the jar yields **23 element names and the superclass each one requires** —
read off the resolved artifacts for both supported lines, where the two lists are identical.

**The generated answer is strictly better than the transcribed one, not merely cheaper.** Three of the
23 are not bare names — `indexConfig/deletionPolicy`, `updateHandler/updateLog` and `//listener` — so
the declaration carries *nesting*, and `//` means the element may appear at any depth. Two example
files could only have implied that, and only for the elements those two files happen to use.

**The two configsets remain the zero-findings fixture.** That criterion is about the inspections rather
than about where the vocabulary came from, and it is the one thing the tarball question was never
about.

### Widen the descriptor gate; keep the permissiveness

The mechanism for FR-1 through FR-3 exists and is the right one. The schema's descriptor provider is
deliberately permissive in three ways: an unknown child gets an any-element descriptor rather than
null, *every* attribute name resolves whether declared or not, and value validation is unconditionally
a no-op — because answering null is how a platform paints something as wrong, and that judgement
belongs to the inspections.

**That contract matters more in `solrconfig.xml` than in a schema**, because the proportion of the
file the plugin will never model is far higher. So the change is to widen the gate from the schema
file kind to the configset's file kinds and give the descriptor a second vocabulary source — not to
write a stricter descriptor.

**The widened gate already exists and must not be written twice.**
`SolrDynamicFieldSearcher` carries a private `holdsFieldReferences` extension property whose
definition is exactly "the schema and `solrconfig.xml`" — the pairing this work needs everywhere. A
second private copy beside the descriptor would be a third chance to disagree, on top of the two
vocabularies below. **One shared, documented predicate on `SolrConfigsetFileKind`**, with both call
sites reading it, is the requirement; which module it lands in is an implementation call.

Whether that becomes one provider with two vocabularies or two providers sharing a descriptor is an
implementation call. The constraint on it is the one the schema's tag-to-kind mapping already states:
completion and documentation both read the vocabulary, so a kind added to one cannot silently be
missed by the other. **Two vocabularies must not become two chances to disagree.**

Positions and their sources:

| Position | Offers | From |
|---|---|---|
| child of `<config>` | the plugin elements `solrconfig.xml` accepts | catalog or `SolrConfig`'s plugin-info, per above |
| attribute of a plugin element | `name`, `class`, and what the named class reads | catalog, resolved through `class` |
| child of a parameter `<lst>` | the value tags | the value-tag vocabulary |
| `name` of a parameter tag | the parameter names | catalog |

The fourth row is the valuable one and the only one needing a new position pattern: existing
attribute-value completion reads the tag name and the attribute name, whereas the legal set here
depends on the grandparent.

### Two lists, two questions

The parser's sixteen names answer *does this parameter hold a field name*. The catalog answers *does
this parameter exist*. The second does not imply the first — `rows` and `debugQuery` will be in the
catalog and neither holds a field name.

This work puts a generated parameter list one import away from the field-reference rules, so merging
them will look like a simplification. It is not: a merge would either widen field references to
parameters holding no fields — producing false "no such field" warnings, the exact failure the
narrowness exists to prevent — or narrow the catalog to sixteen names and lose the feature.

### Registration

Three of the four capabilities add no extension point: the descriptor provider, the completion
contributor and the documentation provider are registered already and get wider gates. The near-miss
inspection is one more local inspection in the established shape with its description entry.

The `class` reference provider is the exception, and it is where the plugin's declared dependencies
become a live constraint: **Java PSI is not available today** — the Java module dependency is
commented out and marked for a later phase. Two ways out, a product decision rather than a technical
one:

- **Optional dependency**, registering the class reference provider only where Java PSI exists. The
  feature appears in IntelliJ IDEA and is absent in a non-Java IDE — honest, and the platform's own
  idiom for this.
- **Defer**, shipping the other three capabilities without it.

**The optional dependency is the recommendation**: it costs one config file, and Ctrl-click is the
gesture a reader reaches for first on an unfamiliar `class` value. But it pulls a later-phase
dependency forward, which is the plan's decision to make.

## Testing Strategy

Fixture tests extend the configset test case — everything here reaches configset detection, so the
settings-leak reason applies throughout — and any parsing helper that falls out is plain JUnit 4.

**The clean fixtures come first**, which is the standing rule for inspections and is load-bearing
here, because this file is full of syntax that looks like something the plugin might have an opinion
about.

| Test | Proves |
|---|---|
| Both shipped configsets produce zero findings | The parent specification's gate |
| A `<searchComponent class="com.example.MyComponent">` with parameters no catalog will contain produces zero findings | **The single check that catches an inspection which drifted into validating by absence — written before the inspection** |
| `pf2` and `pf3` together, neither flagged | The near-miss guard |
| `qff` fires, and the quick fix repairs it to `qf` | The near-miss rule itself |
| Attribute completion inside `<requestHandler>` offers no schema field property | The second vocabulary is scoped |
| Parameter completion offered under a handler's `defaults`, and not under an update processor chain's | The enclosing check |
| The present-day attribute-completion behaviour in `solrconfig.xml` | Pins the sibling-echo claim before the design leans on it |
| `qf` completion offers declared fields and dynamic patterns; `ps` completion offers nothing | FR-9's scope is the sixteen names, not every parameter |
| A caret after `name^` in a `qf`, and the second token of a `sort`, offer no field | FR-9 does not complete into boost or direction syntax |
| Hover on a field name inside `<str name="qf">` answers with the field | FR-10, whether by reference resolution or by a new branch |
| A doc-values-only field in a `qf` is flagged **today** | Pins the behaviour behind [open question 5](#open-questions) before anyone decides it. Already written: the fixture schema carried the field and no case put it in a `qf`, so the one contentious position was the one left unasserted |
| The schema suite, unchanged, gating the descriptor commit | Widening the gate did not disturb the file that works |
| `qf` still navigates; a dynamic-field suffix still resolves; the non-indexed-relevance warning still fires | Field references survive |

**The demo configset is the acceptance fixture, not a unit fixture.** The sandbox pass runs on
`demo/solr/conf/solrconfig.xml` itself, per the precedent of performing demo gestures on the demo
configset.

## Risks

- **Scope.** This is four capabilities across a generator, a descriptor, a completion contributor, a
  documentation provider, an inspection and a reference provider. The plan already says the step
  should be split when it starts, and
  [`plan.md`](../docs/design/pending/2026-08-07-solrconfig-intelligence/plan.md) beside the design
  record is that split.
- **Widening the descriptor gate touches a file that currently works.** The schema's descriptors are
  the reason its attribute completion stopped guessing, and they feed completion, platform
  validation, and the vocabulary provider's de-duplication. A regression there is invisible in
  `solrconfig.xml` tests and visible in schema ones — an argument for that change landing in its own
  commit with the schema suite as its gate.
- **The ground-truth route is unresolved and blocks the element half**, sized at one investigation
  with two workable fallbacks. The parameter half and class navigation do not depend on it and can
  proceed in parallel.
- **The `<str>`-only bound becomes user-visible** the moment completion offers parameter names.
  Widening the value tags is small and should happen in the same step; leaving it is defensible only
  if written down.
- **Four capabilities is four chances to overreach into validation.** Every one has a version that
  flags the unknown, every one of those versions is wrong, and the failure mode is a plugin that
  underlines correct Solr.

## Open Questions

1. **Optional Java dependency now, or defer class navigation?** A product decision the plan owns.
2. **Does the catalog stay a linearly scanned per-line list** once it carries parameters, or does
   parameter lookup need an index?
3. **Does `fq` get field references at all?** It is the parameter a reader would most expect to be
   covered and the one the sixteen-name list cannot hold, because `fq` takes query syntax — `category:books
   AND price:[0 TO *]` — where a field name is a fragment of the value rather than the whole of it. Every
   existing rule assumes the value is a field list. Covering it means parsing Solr query syntax far enough
   to find the field positions, which is a feature rather than an extension, and doing it badly means false
   warnings on correct filters. **Recorded as a question because silently omitting the parameter readers
   most want is worse than declining it out loud.**

   **When `fq` does get references, the property rule it needs is a disjunction, which is new.** A field
   is filterable if it is indexed **or** carries doc values — Solr will scan doc values when there is no
   index to consult. Every property check the plugin makes today resolves one property and compares it,
   so this is the first rule that cannot. The data is all present: `docValues` is in the property table
   with the same three-tier resolution as `indexed`, and inlay hints, the exact-companion intention and
   quick documentation all already read it. What is missing is a consumer, not a fact.

4. **This file is numbered `0002`, which the parent specification already uses.** Renumbering, or an
   explicit convention that a slice shares its parent's number, is a housekeeping decision worth
   making before a third file arrives.

## References

- [`specs/0002-solr-intellij-plugin.md`](0002-solr-intellij-plugin.md) — product intent
- [`specs/plans/0002-solr-intellij-plugin-plan.md`](plans/0002-solr-intellij-plugin-plan.md) — Step 25
  in the Editor track owns delivery status
- [`docs/design/pending/2026-08-07-solrconfig-intelligence/design.md`](../docs/design/pending/2026-08-07-solrconfig-intelligence/design.md)
  and its [`plan.md`](../docs/design/pending/2026-08-07-solrconfig-intelligence/plan.md)
- [`docs/design/pending/2026-08-07-solrconfig-catalog/design.md`](../docs/design/pending/2026-08-07-solrconfig-catalog/design.md)
  — the prerequisite
- [`docs/design/pending/2026-08-04-declaration-targets/design.md`](../docs/design/pending/2026-08-04-declaration-targets/design.md)
  — where Find Usages on `solrconfig.xml` declarations was declined
- [`docs/solr-configuration-files.md`](../docs/solr-configuration-files.md) — updated by this work;
  its table's "the rest of `solrconfig.xml` is a later concern" becomes wrong
- [`docs/faq.md`](../docs/faq.md) — why documentation links to the Reference Guide rather than copying it
