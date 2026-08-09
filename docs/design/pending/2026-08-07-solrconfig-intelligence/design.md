# solrconfig.xml as a first-class surface

## Problem

`solrconfig.xml` is the file people actually edit, and it is the file this plugin knows least about.

The specification settled the scope question under *"`solrconfig.xml` gets the same treatment as the
schema"*, and stated the cost of not doing so:

> Treating it only as a source of field names, which an earlier revision did, gave the most-edited
> file the least support.

That is still where things stand. **What `solrconfig.xml` gets today is exactly one capability**:
the field names it mentions are references, so `<str name="qf">name^3 description category</str>`
navigates to the schema and warns when a name is not declared. Everything else — the elements, their
attributes, the parameter names themselves, the classes — is unmodelled.

### What that costs, measured

**Attribute-name completion is not merely absent. It is actively wrong.**
`SolrSchemaElementDescriptorProvider` gates on `if (!kind.isSchema) return null`, so it declines
`solrconfig.xml` by design. Its own KDoc states what declining means:

> A schema has no XSD, so without this the platform is in schema-less mode, where its answers are
> guesses: attribute-name completion echoes whatever attributes same-named tags elsewhere in the
> file happen to carry, which put an ngram filter's `minGramSize` on every `<filter>` in the schema.

The schema was rescued from that guess. `solrconfig.xml` was never in scope for the rescue, so it is
still in it — and `solrconfig.xml` is a file *made of* same-named tags. Every `<str>` in it is a
sibling of every other `<str>`, which is the worst possible input to an echo. **This is inference
from the plugin's own account of the platform rather than a measurement, and the first fixture
should pin the actual behaviour before the design leans on it.**

Element-name completion is absent rather than wrong, and that half *is* measured:
`SolrSchemaElements.childrenOf` answers for `null`, `schema` and `fieldType` and returns empty for
everything else, so typing `<` inside `<config>` contributes nothing and falls through to the
platform.

**Quick documentation says nothing about any of it.** Hovering `solr.SearchHandler`,
`<requestHandler>` or the parameter name `qf` produces no Solr answer.

**Navigation from a `class` attribute does not exist.** In the demo file, `solr.SearchHandler`,
`solr.UpdateRequestHandler`, `solr.NRTCachingDirectoryFactory` and `solr.SchemaCodecFactory` are
four inert strings.

### The parameter model is narrower than it looks

`SolrConfigParameters` is the bridge from the parser's field-reference rules onto PSI positions, and
two of its bounds matter to anything built on top of it:

| Bound | Consequence |
|---|---|
| `VALUE_TAGS = setOf("str")` | Only `<str>` is read. `<int name="rows">10</int>` is equally legal Solr and entirely invisible — as are `<bool>`, `<long>`, `<float>` and `<double>` |
| `PARAMETER_CARRIERS = setOf("requestHandler", "searchComponent", "initParams")` | Correct for field references, and the same three carriers are the right scope for parameter completion |

The demo file happens to write `<str name="rows">10</str>`, so the `<str>`-only bound is invisible
there. It will not stay invisible once completion offers parameter names, because a reader who
accepts `rows` from a completion list and types `<int name="rows">` has left the modelled subset
without being told.

## Goals

- **Element and attribute completion** inside `solrconfig.xml`, from what the plugin knows, replacing
  the platform's sibling echo.
- **Parameter-name completion and documentation** inside `<lst name="defaults">`, `appends` and
  `invariants`.
- **Validation only where the catalog positively knows better**, which for this file means near-miss
  correction and nothing else.
- **Ctrl-click from a `class` attribute** to the class it names, when that class is on the project's
  classpath.
- Both configsets Solr ships produce zero findings, and so does a configset naming a custom plugin.

## Non-goals

- **Validation by absence, in any form.** This is the constraint the whole record is shaped around
  and it is restated in [what may be flagged](#what-may-be-flagged-and-what-may-not). A parameter the
  generator did not find is not invalid; a class it has never heard of is somebody's custom plugin.
- **Modelling the whole file.** `<autoCommit>`, `<updateLog>`, `<circuitBreaker>` and the cache
  sizing elements are structure the catalog cannot derive and this record does not hand-write. They
  fall to the permissive descriptor and stay silent, exactly as an unknown schema element does today.
- **Parameter *values*.** `defType` has a closed set of parsers and could be completed; `bf` holds a
  function query with its own grammar and cannot. Rather than draw that line badly, values stay with
  the platform. The field-name references inside them already work and are untouched.
- **The Config API and `configoverlay.json`.** `docs/solr-configuration-files.md` records that the
  API writes a JSON overlay beside this file rather than editing it. Reconciling an overlay against
  the file it overlays is a real feature and it is not this one.
- **Extending the field-reference rules.** Which parameters hold field names stays with
  `SolrConfigParser`'s sixteen names. See
  [the catalog is not a field-name source](#the-catalog-is-not-a-field-name-source).
- **`solrconfig.xml` declarations as Find Usages targets.** Already argued and declined in
  [declarations as targets](../2026-08-04-declaration-targets/design.md): a request handler's `name`
  is referenced from nowhere the plugin models, so targeting it would return an empty list and teach
  the reader the search is broken.

## Design

### This depends entirely on a catalog that does not exist yet

[Extending the catalog to solrconfig.xml's classes and
parameters](../2026-08-07-solrconfig-catalog/design.md) is the prerequisite, and it is a prerequisite
for three of the four actions rather than a nicety. The relevant summary:

- The catalog's four kinds are all schema vocabulary; nothing corresponds to a request handler.
- Solr's plugin roots **are** present in `solr-core-10.0.0.jar` — verified — so the hierarchy pass
  that already finds field types will find them.
- Parameter names **are** readable as inlined constants off `org.apache.solr.common.params.*` in
  `solr-solrj` — verified — with a curation problem in `CommonParams` that record owns.

Nothing here should be built before that record's representative assertions pass, because every
symptom of a catalog that generated an empty kind looks identical to a completion contributor that
was never wired up.

### The ground truth the specification names is not currently reachable

The specification treats this as settled and already paid for:

> the `_default` and `sample_techproducts_configs` configsets Solr ships are real, valid
> `solrconfig.xml` files, so they pin the element structure and serve as the clean fixtures that
> must produce zero findings.

**`solr-core-10.0.0.jar` contains no `.xml` resources at all.** Listing the resolved artifact
returns zero entries matching `*.xml`; the only non-class resource near this population is
`EditableSolrConfigAttributes.json`. Those configsets ship in the Solr *distribution* tarball, under
`server/solr/configsets/`, and the build resolves Maven artifacts — `solr-core` and
`solr-analysis-extras` — not the tarball.

So the two files the specification calls ground truth are not on any path the build has today. That
is a gap in the plan, not in the specification's reasoning: the files are the right ground truth and
they are genuinely required. **The decision this record must not make silently is how they arrive**,
and it is worth stating the options rather than picking one in passing:

| Route | Cost |
|---|---|
| Resolve the distribution tarball as a build dependency | A ~200MB download per line, for two small files, in a build already slow on first run |
| Vendor the two files per line into `src/test/resources` | ALv2 files with an ASF provenance note; goes stale silently when a line's default configset changes |
| Derive structure from `SolrConfig.SolrPluginInfo` instead | `solrconfig.xml`'s legal plugin elements are declared in `SolrConfig` — visible as `SolrConfig$SolrPluginInfo` in the jar — which is the *generated* answer this plugin prefers, and would make the shipped configsets a test fixture rather than a data source |

**The third is the one worth investigating first**, because it is the same argument that produced the
catalog: a list Solr itself declares beats a list read off two example files. `SolrConfig` is
already on the scanned classpath. Whether `SolrPluginInfo` actually enumerates the element names in
a bytecode-readable form is unverified and is the first question this step should answer — it changes
the element-structure design from "transcribe two files" to "one more generator pass", and it would
retire the tarball question entirely.

Whichever route wins, the two configsets remain the **zero-findings fixture**, because that criterion
is about the inspections and not about where the vocabulary came from. Vendoring two files for test
use is a smaller commitment than vendoring them as the data source.

### Element and attribute completion: widen the gate, keep the permissiveness

The mechanism already exists and is the right one. `SolrSchemaElementDescriptorProvider` owns the
descriptors and gets its permissiveness exactly right for a file with no XSD:

- `getElementDescriptor` returns `AnyXmlElementDescriptor` for an unknown child rather than null, so
  no element is flagged for being somewhere the plugin did not anticipate.
- `getAttributeDescriptor` resolves *every* attribute name, declared or not, because "answering null
  here is how a platform paints an attribute as wrong" and that judgement belongs to the inspections.
- `SolrSchemaAttributeDescriptor.validateValue` returns null unconditionally.

**That contract is more important in `solrconfig.xml` than in a schema**, because the proportion of
the file the plugin will never model is far higher. So the change is to widen the gate from
`kind.isSchema` to the configset's two file kinds, and give the descriptor a second vocabulary
source — not to write a stricter descriptor.

The class is named for schemas throughout (`SolrSchemaElementDescriptorProvider`,
`SolrSchemaTagDescriptor`, `SolrSchemaAttributeDescriptor`) and the vocabulary lookup is a `when` over
schema tags. **Whether that becomes one provider with two vocabularies or two providers sharing a
descriptor is an implementation call**, and the constraint on it is the one
`SolrClassKind.forTag`'s KDoc already states about the schema mapping: *completion and documentation
both read it, so a kind added to one cannot silently be missed by the other.* Two vocabularies must
not become two chances to disagree.

The parallel structure for the parameter carriers:

| Position | Offers | From |
|---|---|---|
| child of `<config>` | the plugin elements `solrconfig.xml` accepts | catalog or `SolrPluginInfo`, per the question above |
| attribute of a plugin element | `name`, `class`, and what the named class reads | catalog, resolved through `class` exactly as a `<filter>`'s attributes are today |
| child of `<lst name="defaults">` | `<str>`, `<int>`, `<bool>`, `<arr>`, `<lst>` | the value-tag vocabulary |
| `name` of a parameter tag | the parameter names | catalog |

The fourth row is the valuable one and it is the one that needs a new position pattern:
`SolrConfigsetCompletionContributor` today completes attribute *values* by reading `tag.name` and
`attribute.name`. Here the position is an attribute value — `<str name="|">` — whose legal set
depends on the *grandparent* being a parameter list under a parameter carrier. That check exists:
`SolrConfigParameters.enclosingIsParameterList` performs precisely it, and the KDoc explains why the
enclosing check is not optional — `<lst name="defaults">` also appears under an update processor
chain, where its contents are not query parameters.

**Reuse that predicate rather than restating it.** It is `internal` to `parsing` and both consumers
of it already live outside; the completion contributor becomes a third.

### Parameter documentation

Quick documentation on a parameter name answers: what it is, which params family it belongs to, and
its summary where Solr's sources carry one. `SolrConfigsetDocumentationProvider` already answers at
several positions by asking what kind of thing the caret is on, and this is one more.

**Where the catalog carries no summary, the popup shows the name and the family and stops.** This is
the `StrField` precedent: a class with no class-level Javadoc shows no summary, and that is recorded
as *a fact about what Solr's maintainers wrote, not a gap in the extraction*. Parameters will hit
this more often than classes do.

**The Reference Guide link is the other half and is more valuable here than for a factory.**
`SolrReferenceGuide` already builds versioned links and `docs/faq.md` records why linking beats
copying. A parameter's guide page is where its real documentation lives — Solr's own constant-field
comments will never approach it.

### What may be flagged, and what may not

One inspection, and it is narrow on purpose.

**A near-miss of a parameter name the catalog knows.** `<str name="qff">` inside a `defaults` list,
where `qf` exists and `qff` does not, within an edit distance of one. `SolrReplaceNameQuickFix`
already exists for exactly this shape and is what the schema's unknown-attribute inspection uses.

Everything else stays silent, and the reasons are worth keeping together:

| Not flagged | Because |
|---|---|
| A parameter the catalog does not know | Any component may read any name out of `SolrParams`. Absence proves nothing |
| A class the catalog does not know | `solrconfig.xml` accepts plugin classes from outside Solr. This is the false positive that would fire on every project with a custom component |
| A known parameter in a handler that may not read it | Solr reads parameters permissively; an unread parameter is inert, not rejected. `mm` under a `defType=lucene` handler is pointless and legal |
| An element the plugin does not model | Most of the file. The permissive descriptor is what keeps this quiet |

**The near-miss rule needs one guard the schema's version does not.** Solr's parameter families
genuinely contain names one edit apart — `pf2` and `pf3`, `ps2` and `ps3` are real and distinct.
An edit-distance rule that fires on a *known* name would flag `pf3` as a misspelling of `pf2`. So the
rule is: fire only when the written name is **not** in the catalog and exactly one known name is
within distance one. **`SolrInspections` and the clean fixtures first**, per the standing rule, and
the `pf2`/`pf3` pair is the fixture that proves the guard.

### Navigation from a `class` attribute

The one action that does not need the catalog. A `class` value is a fully qualified Java class name
(or the `solr.`-prefixed short form the catalog maps), and the platform resolves those given a
`JavaPsiFacade` — so a reference provider on the `class` attribute value resolves it and Ctrl-click
lands in the decompiled class or its sources.

**This is the one place the plugin's `<depends>` set is a live constraint.** `plugin.xml` declares
`com.intellij.modules.platform` and `com.intellij.modules.xml`, with a commented-out
`com.intellij.modules.java` marked *Phase 3*. Java PSI is not available today.

Two ways out, and the choice is a product decision rather than a technical one:

- **Optional dependency** — `<depends optional="true" config-file="…">` registering the class
  reference provider only where Java PSI exists. The feature appears in IntelliJ IDEA and is absent
  in a non-Java IDE, which is honest and is the platform's own idiom for this.
- **Defer to Phase 3** — the three other actions ship without it.

**The optional dependency is the recommendation**, because it costs one config file and this is the
gesture a reader reaches for first on an unfamiliar `class` value. But it pulls a Phase 3 dependency
forward, which is a decision the plan owns rather than this record.

**Resolution must degrade to nothing, not to a warning.** A class not on the project's classpath is
the normal case — the user is editing a configset, not a Solr build — so an unresolved `class` is a
soft reference exactly as the four existing reference providers are soft. This must not become a
second, sterner voice saying what the declined class inspection already refuses to say.

### The catalog is not a field-name source

Restated here because this record puts a generated parameter list one import away from the code that
decides which parameters hold field names, and merging them would look like a simplification.

`SolrConfigParser`'s sixteen names answer *does this parameter hold a field name*. The catalog
answers *does this parameter exist*. The second does not imply the first: `rows` and `debugQuery`
will be in the catalog and neither holds a field name. A merge would either widen field references to
parameters that hold no fields — producing false "no such field" warnings, the exact failure that
class's KDoc exists to prevent — or narrow the catalog to sixteen names and lose the feature.

Two lists, two questions. The record that adds the catalog population says the same thing from the
other side.

## Testing strategy

`SolrConfigsetTestCase` for everything that reaches PSI, and plain JUnit 4 for whatever parsing helper
falls out — the standing split, and the settings-leak reason applies as usual since all of this
reaches configset detection.

**The clean fixtures come first**, which is the standing rule for inspections and is load-bearing
here: this file is full of syntax that looks like something the plugin might have an opinion about.

- **Both shipped configsets produce zero findings.** The specification's gate. Whether they arrive
  vendored or resolved is the open question above; the assertion does not change.
- **A custom plugin produces zero findings** — a `<searchComponent class="com.example.MyComponent">`
  with parameters no catalog will ever contain, in a `defaults` list, underlined nowhere. **This is
  the criterion that catches an inspection that drifted into validating by absence**, and it should
  be written before the inspection is.
- **The `pf2`/`pf3` guard**, per above: two known names one edit apart, neither flagged.
- **A near-miss fires and its quick fix repairs it** — `qff` → `qf`.
- **Attribute completion inside `<requestHandler>` does not offer a schema field property.** The
  negative that proves the second vocabulary is scoped, and the direct analogue of the `minGramSize`
  echo the schema descriptor was built to stop.
- **Parameter completion respects the enclosing check** — offered under
  `<requestHandler><lst name="defaults">`, and not under an update processor chain's `<lst
  name="defaults">`. The `SolrConfigParameters` KDoc names this case; the fixture makes it a test.
- **The existing field-reference behaviour is unchanged.** `qf` still navigates, `body_t` still
  resolves through `*_t`, and the non-indexed relevance warning still fires. Widening the descriptor
  gate is the change most likely to disturb them, because it puts a descriptor where there was none.

**The demo configset is the acceptance fixture, not a unit fixture.** Per the recent precedent of
performing demo gestures on the demo configset, the sandbox pass runs on
`demo/solr/conf/solrconfig.xml` itself.

## Registration

No new extension points for three of the four actions — the descriptor provider, the completion
contributor and the documentation provider are registered and get wider gates. The near-miss
inspection is one more `localInspection` in the established shape with its `inspectionDescriptions`
entry. The `class` reference provider is either a new `psi.referenceContributor` or, on the optional
route, a second `config-file` conditioned on `com.intellij.modules.java`.

**Dumb-aware throughout, and the promise about data sources holds**: all of this reads the configset's
own text, the generated catalog resource, and the detector. Nothing reads an index and nothing
contacts a server. The `class` reference is the one to watch — Java PSI resolution *is* index-backed,
so that provider must not claim dumb-awareness the way the rest do.

## Risks

- **The largest risk is scope, and it is the one the plan already names.** Step 25 says this "should
  be split when it starts". The `plan.md` beside this record is that split; the record is one
  argument because the pieces share a dependency and a shape, not because it is one pull request.
- **Widening the descriptor gate touches a file that currently works.** The schema's descriptors are
  the reason attribute completion stopped guessing, and they are consumed by completion, by the
  platform's validation, and by the `runRemainingContributors` de-duplication in the vocabulary
  provider. A regression here is invisible in `solrconfig.xml` tests and visible in schema ones,
  which is an argument for the descriptor change landing in its own commit with the schema suite as
  its gate.
- **The ground-truth route is unresolved and blocks the element half.** Sized at one investigation —
  does `SolrConfig$SolrPluginInfo` enumerate the plugin elements readably — with two fallbacks, both
  workable and neither free. The parameter half and the class-navigation half do not depend on it and
  can proceed in parallel.
- **The `<str>`-only bound becomes user-visible.** Once completion offers parameter names, a reader
  may write `<int name="rows">` and drop out of the modelled subset silently. Widening `VALUE_TAGS`
  is small and should happen in the same step; leaving it is defensible only if written down.
- **Catalog size on the highlighting path.** Parameter completion resolves against the catalog on
  every keystroke in a `defaults` list. `SolrClassCatalog` caches per line and the lookup is a list
  scan — fine at 185 entries, worth measuring at several hundred, and a linear scan per keystroke is
  the kind of thing that is fine until it is not.
- **Four actions is four chances to overreach into validation.** Every one of them has a version that
  flags the unknown, every one of those versions is wrong, and the failure is a plugin that
  underlines correct Solr. The custom-plugin zero-findings fixture is the single check that catches
  all four, which is why it is written first.

## Delivery

**Step 25 in `specs/plans/0002-solr-intellij-plugin-plan.md`**, in the Editor track, after the
catalog extension and therefore after Step 9. This record supplies the design Step 25 was written
without; `plan.md` beside it supplies the split Step 25 asked for.

Step 25's success criteria are unchanged and are the right ones. Shipped, this step also updates:

- **The plan's Step 25**, which currently carries four actions and no design.
- **`docs/solr-configuration-files.md`**, whose table says `solrconfig.xml` gets "Reference and
  inspection coverage" and that "the rest of `solrconfig.xml` is a later concern" — accurate today
  and wrong afterwards.
- **`docs/manual-test-suite.md`**, with the gestures for the new positions.
- **`docs/demo/README.md`**, which Step 25 notes "predates this scope" and has no solrconfig step
  beyond the cross-file `qf` navigation.

**Acceptance:** a new demo step on `demo/solr/conf/solrconfig.xml` — parameter completion inside
`<lst name="defaults">` of the `/select` handler, and quick documentation on a parameter — plus
Ctrl-click on `solr.SearchHandler` if the optional-dependency route is taken. The file is already
written to be demonstrated from: its comments call out the `qf` cross-file navigation and the `*_t`
dynamic resolution, and this step adds the third thing that file can show.
