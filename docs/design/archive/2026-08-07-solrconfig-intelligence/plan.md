# solrconfig.xml as a first-class surface — the split

**Goal:** the four actions of Step 25, delivered as separate pull requests, behind one that unblocks
them — plus one the step did not ask for and should have, since it needs nothing that does not exist.

**Specified in [`specs/0002-solrconfig-xml-intelligence.md`](../../../../specs/0002-solrconfig-xml-intelligence.md).**
That document owns the requirements and the reasons; this one owns the order and what each PR gates
on. Where they disagree, the specification wins and this file is stale.

**This is a sequencing plan, not a task-by-task one, and that is deliberate.** The archived plans in
`docs/design/archive/` specify each task down to the failing test, which is the right form once a
design is settled. Two questions in [the design](design.md) are not settled — where the element
vocabulary comes from, and whether Java PSI arrives as an optional dependency — and task-level steps
written over an open question are invented detail rather than a plan. **Each PR below gets its own
task-level plan when it starts**, once its blocking question has closed.

Step 25 asked for exactly this: *"This is the largest step in the configuration surface and should be
split when it starts. It is written as one step because the pieces share a dependency and a shape,
not because it is one pull request."*

## Global constraints

The standing ones, unchanged: `./gradlew build` (jvmToolchain 21); the Dokka gate on every public
declaration in `src/main/kotlin`; the Kover 80% floor; nothing in `org.apache.solr.ide.model`
importing an IntelliJ type; dumb-aware and index-free on the editor path; PSI tests extending
`SolrConfigsetTestCase` in JUnit 3 style, pure tests plain JUnit 4; conventional commit subjects with
sign-off.

**One constraint specific to this step, above all the others:** an inspection here must not fire on a
correct file, and this file makes that harder than the schema did. Clean fixtures before rules, every
time.

## Order

```
MERGED, in the order they landed:

  #111 read every parameter value tag, and share one configset file-kind test  (PR A)
  #112 say which operations a field supports                                   (PR C)
  #113 complete schema field names inside a handler's parameters               (PR B)
  #131 read the plugin roots Solr declares, and catalog their classes          (PR 0)
  #132 complete, explain and navigate the classes solrconfig.xml names         (PR 4, + the
                                                                                class half of PR 1)
  #133 complete and explain the request parameters solrconfig.xml carries      (PR 2)

STILL TO DO:

  PR 1  element and attribute structure   ── the descriptor gate is still `kind.isSchema`;
                                             needs the rest of Q1
  PR 3  near-miss inspection              ── unblocked now that PR 2 has landed
```

**Two of the five remaining blockers cleared themselves.** Q2 closed in the affirmative — the optional
`com.intellij.modules.java` dependency shipped with `#132`, so PR 4 is done rather than deferred — and
PR 2 landing means PR 3 waits on nothing but someone writing it. What is left is the half of Q1 that a
sandbox pass exposed: `<config>`, `luceneMatchVersion` and `dataDir` are in neither generated source, so
PR 1 still needs a decision about where those three come from.

**The lettered PRs this record planned as A, B and C shipped as #111, #112 and #113**, in that
dependency order rather than the order they were written: the capability model had to precede the
completion that filters by it, or completion would have offered fields the inspections underline. The
letters are kept below only so the argument for each is still findable; the branch names are what
exist.

**PR A exists because nothing else here is startable.** PR 1 waits on Q1, PRs 2 and 3 wait on the
catalog, PR 4 waits on Q2 — read without PR A this plan has no entry point, which is a property of the
dependencies rather than of the work. The unblocked pieces are the shared foundations the other four
sit on, so they ship first and alone.

### PR A — Groundwork: pin the present, remove the duplicates — *shipped as #111*

No user-visible change, deliberately. Three items, all unblocked today, all of them changes that would
otherwise be made *underneath* a new feature whose own tests would mask a regression.

1. **A fixture recording what attribute completion offers inside `solrconfig.xml` today.** The
   sibling-echo claim [the design](design.md) rests on is inferred from the plugin's account of the
   platform, not measured. Measure it before anything leans on it — and if the platform's behaviour
   turns out to be tamer than the design assumes, PR 1's value estimate changes.
2. **One shared file-kind predicate for "the schema and `solrconfig.xml`".**
   `SolrDynamicFieldSearcher` already carries a private `holdsFieldReferences` extension property with
   exactly that definition. PR 1 needs the same pairing for the descriptor gate, and a second private
   copy would be a third chance to disagree — on top of the two vocabularies PR 1 introduces. One
   documented predicate on `SolrConfigsetFileKind`, both call sites reading it, no behaviour change.
3. **Widen `VALUE_TAGS` beyond `<str>`, and widen `enclosingIsParameterList`'s visibility.**
   `<int>`, `<bool>`, `<long>`, `<float>` and `<double>` read as parameters. The predicate is
   `private`, so PR 2's "reuse rather than restate" is a change and not an import; making it reachable
   here keeps PR 2 about completion. Both carry a KDoc obligation under the Dokka gate.

**Gate:** the schema suite and the full existing suite are unchanged — this PR's whole claim is that
it changes no behaviour except which value tags are read. The field-reference tests are the ones to
watch, since item 3 widens what the parser sees.

### PR B — Field names in parameter values — *shipped as #113*

**Completion of schema field names inside the parameters the parser already knows hold them**, and
quick documentation on a field name written in one. [FR-9 and FR-10](../../../../specs/0002-solrconfig-xml-intelligence.md#requirements).

**Reads no catalog and answers no open question, so it runs beside PR A rather than behind PR 0.** Both
lists it needs exist: `SolrConfigParser`'s sixteen field-holding parameter names, and the schema's
fields and dynamic patterns from the model. Nothing here waits on anything.

**This is the inverse of a capability that already ships**, which is why it is worth more than its size.
The unknown-field inspection tells a reader `descriptoin` is not a field; the list that lets it say so
is the list that would have offered `description` first. Today the completion contributor has no
`solrconfig.xml` awareness at all — verified, not inferred — so a reader gets the correction and never
the suggestion.

**Two things it must not do.** Complete into boost or direction syntax: `qf` offers `name` and not
`name^3`, a caret after `name^` is inside a boost, and a `sort`'s second token is a direction. And
widen beyond the sixteen — the parameter must be one the parser reads, or completion starts offering
field names inside `rows` and `defType`.

**Quick documentation already works through reference resolution.** `getCustomDocumentationElement` claims attribute
values and schema tags, and a field name in `qf` is tag *text*, so the provider returns null — which
lets the platform resolve the reference at the caret and document its target instead. **Regression coverage:**
`SolrConfigFieldReferenceTest.testHoverOnFieldNameInQfParameterResolvesDocumentation` asserts this path, ensuring
the behaviour does not regress when either the documentation provider or reference contributor changes.

**Gate:** `qf` offers declared fields and dynamic patterns; `ps` offers nothing; a caret after `name^`
and a `sort`'s second token offer nothing; hover on a field name in a `qf` answers with the field.

**Settle Q4 first.** Whether a doc-values-only field belongs in a `qf` completion list is the same
question as whether the relevance inspection should flag one, and answering it in two places
independently is how a plugin comes to suggest a field it then underlines.

### PR C — Which operations a field supports, as a model fact — *shipped as #112*

**The rule Q4 settled belongs in `model`, not inside an inspection**, and the reason is the code track
rather than this one. When SolrJ completion arrives it will ask the same question from the other side —
whether `addFacetField("x")` or a sort on `x` can work — and a rule living inside a `solrconfig.xml`
inspection is unreachable from there. Two implementations of "is this field facetable" is two chances to
disagree, in two surfaces a reader expects to agree.

**It has a consumer in each of the plugin's three tracks**, which is what makes `model` the only
defensible home: the configuration surface asks whether a `qf`, `facet.field` or `sort` names a field that
can serve it; the code track asks whether an `addFacetField("x")` will be rejected by the server; and the
query console asks which fields completion should *offer* for a parameter while a reader composes a query.
Three implementations is three chances to disagree, across the surfaces the parent specification exists to
make agree — it promises "a single shared model of what fields exist and what they can do", and **these
rules are the second half of that sentence.**

The console consumer changes the shape rather than merely adding a caller. An inspection asks *is this
wrong*; completion asks *what may I write*. A boolean per call site cannot rank or filter a completion
list, so the model fact names the operations a field supports rather than answering one question per
caller.

**The precedent is exact.** `SolrMatchCapability` models what a field can *match*, derived from its
analyzer chain, lives in `model` with no IntelliJ import, and is read by six surfaces — inlay hints, two
intentions, documentation, completion and an inspection. What is missing is the sibling: which *operations*
a field supports.

**Because it spans all three tracks, the plan may want this as its own step** in the model area rather
than as a pull request under Step 25, which is an Editor-track step. Recorded here rather than decided:
the plan owns where a step sits.

| Operation | Rule | Expressed today |
|---|---|---|
| Search / boost — `qf`, `pf` | `indexed`, **or** `docValues` for a non-text type (Q4) | Partly, and wrongly, inside one inspection |
| Filter — `fq` | `indexed` **or** `docValues` | Nowhere. `fq` has no references at all — Q3 |
| **Facet — `facet.field`** | `docValues`, **or** `indexed` and uninvertible | **Nowhere.** A `facet.field` naming a field with neither is silently accepted, and Solr fails the request |
| **Sort** | `docValues`, **or** `indexed` and uninvertible; and single-valued | **Nowhere**, same gap |
| Highlight — `hl.fl` | `stored`, and the chain for the faster highlighters | Nowhere |

**Every rule in that table is a disjunction, and the plugin has never expressed one.** Every property
check today resolves one property and compares it. That is the shape change, and it is the reason this is
its own PR rather than a line in another: `SolrFieldProperties` already resolves `indexed`, `docValues`,
`stored` and `multiValued` three tiers deep, so the facts are present and only the combining rule is
missing.

**Scope discipline.** This PR moves the `qf` rule and adds the facet and sort rules, because those two are
the ones whose absence lets a *broken* configuration through — the opposite failure from the rest of this
plan, and the reason they are worth having. `fq` waits on Q3, and highlighting waits on someone wanting it.

**Gate:** the doc-values-only `qf` fixture flips from flagged to clean; a `facet.field` and a `sort` naming
a field with neither `indexed` nor `docValues` are flagged; `popularity` in a `facet.field` and a `sort`
stays clean, as its existing fixtures already assert; and the non-indexed *text* field in a `qf` is still
flagged.

### PR 0 — Catalog extension — *shipped as #131*

Not this record's. [The catalog design](../2026-08-07-solrconfig-catalog/design.md) owns it, and it
ships first because every symptom of a catalog that generated an empty kind is indistinguishable
from an editor feature that was never wired up.

**Gate:** the per-kind representative assertions in that record pass on both supported lines.

### PR 1 — Element and attribute structure

Widen the descriptor gate from `kind.isSchema` to PR A's shared file-kind predicate, and give the
descriptor a second vocabulary.

**No longer blocked.** Q1 is closed, and closing it also split this PR's dependency in two: the element
*names* come from `SolrConfig.plugins` directly, so element completion waits on nothing, while attribute
completion still needs PR 0 to know what a named class accepts. Worth landing in that order rather than
waiting for the catalog to do either. Landing this before Q1 closes means transcribing two files by hand and
possibly throwing it away.

**Split within the PR:** the descriptor-gate change lands as its own commit, gated on the *schema*
test suite. It is the change most likely to disturb something that currently works, and the
regression would be invisible in solrconfig tests.

**Gate:** attribute completion inside `<requestHandler>` offers no schema field property; the schema
suite is unchanged; both shipped configsets produce zero findings.

### PR 2 — Parameter completion and documentation — *shipped as #133*

The highest-value slice and the one the demo file shows. Parameter names inside
`<lst name="defaults">`, `appends` and `invariants`, with quick documentation and a Reference Guide
link on each.

Reuses `SolrConfigParameters.enclosingIsParameterList` rather than restating the enclosing check —
**which is why PR A widens its visibility first.** Restating it would produce two copies that agree
until an update processor chain grows a second parameter-list spelling.

**Needs PR A**, which carries the widened `VALUE_TAGS` this PR makes user-visible: a reader who accepts
`rows` from a completion list and writes `<int name="rows">` must not silently leave the modelled
subset. That widening used to sit in this PR; it moved because it is unblocked and this one is not.

**Gate:** completion answers inside the demo file's `/select` handler and stays silent inside an
update processor chain's `<lst name="defaults">`; the existing field-reference behaviour — `qf`
navigation, `body_t` through `*_t`, the non-indexed relevance warning — is unchanged.

### PR 3 — The near-miss inspection

One rule: a parameter name not in the catalog, with exactly one known name at edit distance one.
`SolrReplaceNameQuickFix` already exists.

**One rule, where Step 25 authorised two, and the second is declined.** That step's third action names
"a misspelling of a name it knows, *a value outside a set it knows to be closed*". The specification
declines the value half: closedness is knowable for only a minority of parameters, and an inspection
firing on that minority while silent on the rest teaches the reader that an unflagged value was
checked — the same shape as validation by absence. Worth reopening as its own step once the catalog can
prove closedness from bytecode rather than from a curated list. **Recorded here so this PR does not read
as half-finished, and so Step 25's criteria can be corrected rather than quietly missed.**

**Needs PR 2**, since it consumes the same catalog lookup and the same position predicate.

**Write the clean fixtures first**, and specifically these two before the rule exists:

- a `<searchComponent class="com.example.MyComponent">` with parameters no catalog will contain,
  underlined nowhere — the criterion that catches an inspection drifting into validation by absence
- `pf2` and `pf3` in the same `defaults` list, neither flagged — the guard against an edit-distance
  rule firing on two real names one edit apart

**Gate:** both shipped configsets and the custom-plugin fixture produce zero findings; `qff` → `qf`
fires and its quick fix repairs the file.

### PR 4 — Class navigation — *shipped as #132*

Independent of PRs 0–3 and of the catalog. Was blocked only on **Q2**, which closed in its favour.

A soft reference on a `class` attribute value, resolving through Java PSI, degrading to nothing when
the class is not on the classpath. Not dumb-aware — Java resolution is index-backed, unlike
everything else in this plugin.

**Gate:** Ctrl-click on `solr.SearchHandler` in the demo file lands in the class; an unresolvable
class produces no warning anywhere.

## What building it turned up

Five things the design did not anticipate, recorded because each one is a fact about this file rather
than about the pull request that found it.

**The sibling-attribute echo is real.** The premise the descriptor change rests on was inferred from
the plugin's own account of the schema rescue. It is now measured: `startup="lazy"` written on one
`<requestHandler>` is offered inside another that does not carry it. PR 1 can be scoped against a
fixture rather than against an inference.

**Reading a constant as a field name was a live false positive, older than any of this work.**
`plainFieldName` rejected glob, function, alias, transformer and parameter-reference syntax and had no
rule for a number, so `<str name="boost">1.5</str>` was underlined with "no field named '1.5'". Widening
the value tags is what made the numeric spellings — the ones anybody would actually write — reach it.
**The lesson generalises to the rest of this plan: the syntax rules are where this file's surprises
live, and every one found so far was found by a fixture rather than by reading.**

**Sorting needs a single value, which no record had noticed.** A `multiValued` field has no defined
order, so Solr rejects a plain sort and requires a selector. It is the only asymmetry between the facet
rule and the sort rule, and it was missing from the design's table.

**Quick documentation on a field name in a parameter already worked**, through a path nobody wrote for
it: the provider declines the position, the platform resolves the reference there, and the provider
answers for the target. Q3's sibling question is answered — the work was a test, not a feature.

**Nothing in a package name says which file a feature serves**, because packages are organised by
capability and the surface above them is "configuration files" — one surface for both. That is the
documented principle and it stands, but the *gate* that decides file scope was written two ways: the
schema side asked `isSchema`, and the configuration side compared a file name against a literal in four
places. `isSolrConfig` now joins `isSchema`, so every feature declares its scope in one shape. **PR 1
should follow that convention rather than adding a fifth spelling**, since widening the descriptor gate
is exactly where a new one would appear.

## Open questions, and who closes them

**Q1 — Where does the element vocabulary come from? — HALF CLOSED, and the half matters.**

`SolrConfig` carries `public static final List<SolrPluginInfo> plugins`, and each entry pairs a `tag`
string with the `Class<?>` that tag's `class` attribute must implement. Both are plain constants in the
static initializer, so one pass yields **23 element names and the superclass each one requires** —
identical on 9.10.1 and 10.0.0, read off the resolved artifacts. Three of the 23 carry a parent path
rather than a bare name (`indexConfig/deletionPolicy`, `updateHandler/updateLog`, `//listener`), so the
declaration supplies nesting that two example files could only have implied.

**That covers the plugin elements and nothing else, which a sandbox pass made obvious.** Hovering
`<config>`, `luceneMatchVersion` and `dataDir` produces nothing, and none of the three is in the 23:
they are plain fields on `SolrConfig`, read through `get("…")` in its constructor rather than declared in
a list. An earlier revision of this record called element structure settled on the strength of the
plugin half. It is not.

**The rest of the vocabulary has its own generated source, previously overlooked.**
`EditableSolrConfigAttributes.json` ships inside `solr-core` — on both supported lines — and carries a
nested tree of `updateHandler`, `query` and `requestDispatcher` with **40 leaf attributes**, each with a
type code that also says whether Solr reads it as an attribute or as a child element (`0` string
attribute, `1` string node, `10` boolean attribute, `11` boolean node, `20` int, `30` float). So it gives
nesting, attribute names and value types for the three subtrees where most hand-editing happens, and it
is a JSON resource rather than bytecode.

**It is the *editable* subset, so it is not the whole remainder.** `luceneMatchVersion`, `dataDir` and
`<config>` itself are not runtime-editable and do not appear in it. Whatever covers those is the third
source, and until one is found they are the small hand-written set this record has so far avoided
needing — which is a much smaller commitment than the two vendored configsets it replaced.

*Closed by:* nothing further for the plugin half. **The remaining question is narrower than the original
one**: read the JSON for the three editable subtrees, and decide whether `<config>`, `luceneMatchVersion`
and `dataDir` are worth hand-writing or worth another look in the jar.

**Q2 — Optional Java dependency, or defer class navigation to Phase 3? — CLOSED: the dependency, now.**
`plugin.xml` no longer carries `com.intellij.modules.java` commented out; it carries
`<depends optional="true" config-file="solr-withJava.xml">`, so class navigation is present in IDEA,
absent elsewhere, and the plugin loads either way. It cost one config file, as this record estimated.

*Closed by:* the plan owner, in `#132`, by pulling the Phase 3 dependency forward.

**Q3 — Does `fq` get field references at all?** It is the parameter a reader would most expect to be
covered and the one the sixteen-name list cannot hold: `fq` takes query syntax, where a field name is a
fragment of the value rather than the whole of it, and every existing rule assumes a field list.
Covering it means parsing Solr query syntax far enough to find the field positions — a feature rather
than an extension, and one whose bad version puts false warnings on correct filters.

*Closed by:* nothing in this plan. **It blocks no PR here and is recorded so that omitting the
parameter readers most want is a decision rather than an oversight.**

**The rule `fq` would need is a disjunction, and nothing here expresses one yet.** A field is filterable
if it is indexed **or** carries doc values, because Solr scans doc values when there is no index to
consult. Every property check the plugin makes resolves one property and compares it. The data is present
— `docValues` sits in the property table with the same three-tier resolution as `indexed`, and inlay
hints, the exact-companion intention and quick documentation all read it — so what is missing is a
consumer rather than a fact.

**Q4 — Should the non-indexed relevance warning fire on a field carrying doc values? — CLOSED: no, and
it does today.**

`FieldType.getFieldQuery` opens with `hasDocValues() && !indexed()` and on that branch delegates to
`getRangeQuery` with the value as both bounds, reaching `SortedSetDocValuesField.newSlowRangeQuery`. An
exact match becomes a single-value doc-values range query — slower than a term lookup, and functional.
`StrField` overrides none of it. **Byte-identical on 9.10.1 and 10.0.0**, read off the resolved artifacts
rather than recalled.

So the warning is a false positive on a doc-values-only field, and the fix is silence rather than a
reworded message: the field is searchable *and* boostable, since a constant-scoring range query still
multiplies by the boost. What it is not is *ranked* by term statistics, which is a subtler claim than an
inspection should be making.

**The original case is untouched.** `TextField` overrides `getFieldQuery` for the analysis path and
declares no doc-values support at all, so a non-indexed *text* field in a `qf` is genuinely unsearchable —
what the inspection was written for, and what its other fixtures assert.

Behaviour is [pinned by a test](../../../../src/test/kotlin/org/apache/solr/ide/configset/inspection/SolrNonIndexedRelevanceFieldInspectionTest.kt)
written before the answer was known, so the fix flips a recorded expectation rather than discovering one.

*Fixed in* **#112**, ahead of #113 as required — completion cannot offer a field the inspection then
underlines, and #113 reads #112's mapping rather than a copy so the two cannot drift.

## What ships alongside

Per [the design's Delivery section](design.md#delivery), and not as an afterthought — one of these is
currently *correct* and becomes wrong:

- `specs/plans/0002-solr-intellij-plugin-plan.md` — Step 25 gains a design reference, a specification
  reference, and its split. **Its success criteria also need two additions and one correction**: the
  `pf2`/`pf3` guard and the present-day-behaviour fixture, both of which catch silent wrongness rather
  than visible failure and are exactly what a split loses; and the third action's value-validation
  clause, per [PR 3](#pr-3--the-near-miss-inspection).
- `docs/solr-configuration-files.md` — the table saying `solrconfig.xml` gets "Reference and
  inspection coverage" and that "the rest is a later concern".
- `docs/manual-test-suite.md` — gestures for the new positions.
- `docs/demo/README.md` — a solrconfig step beyond the cross-file `qf` navigation.
