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
PR A  groundwork ── no dependencies, start here ─┐
                                                 ├─→ PR 1  element structure  (also PR 0 + Q1)
PR 0  ── catalog extension ──────────────────────┼─→ PR 2  parameters
   (separate design record)                      └─→ PR 3  near-miss inspection  (needs PR 2)

PR B  field names in parameter values  ── needs no catalog, runs beside PR A
PR 4  class navigation  ── independent of all of the above, needs Q2
```

**PR A exists because nothing else here is startable.** PR 1 waits on Q1, PRs 2 and 3 wait on the
catalog, PR 4 waits on Q2 — read without PR A this plan has no entry point, which is a property of the
dependencies rather than of the work. The unblocked pieces are the shared foundations the other four
sit on, so they ship first and alone.

### PR A — Groundwork: pin the present, remove the duplicates

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

### PR B — Field names in parameter values

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

**Quick documentation may already work by accident.** `getCustomDocumentationElement` claims attribute
values and schema tags, and a field name in `qf` is tag *text*, so the provider returns null — which
lets the platform resolve the reference at the caret and document its target instead. **Find out before
building**: if that path works this is a test pinning an unclaimed capability, and if it does not it is
a small addition. Either way the behaviour is currently unasserted.

**Gate:** `qf` offers declared fields and dynamic patterns; `ps` offers nothing; a caret after `name^`
and a `sort`'s second token offer nothing; hover on a field name in a `qf` answers with the field.

### PR 0 — Catalog extension

Not this record's. [The catalog design](../2026-08-07-solrconfig-catalog/design.md) owns it, and it
ships first because every symptom of a catalog that generated an empty kind is indistinguishable
from an editor feature that was never wired up.

**Gate:** the per-kind representative assertions in that record pass on both supported lines.

### PR 1 — Element and attribute structure

Widen the descriptor gate from `kind.isSchema` to PR A's shared file-kind predicate, and give the
descriptor a second vocabulary.

**Blocked on Q1** (below), and on PR A for the predicate. Landing this before Q1 closes means transcribing two files by hand and
possibly throwing it away.

**Split within the PR:** the descriptor-gate change lands as its own commit, gated on the *schema*
test suite. It is the change most likely to disturb something that currently works, and the
regression would be invisible in solrconfig tests.

**Gate:** attribute completion inside `<requestHandler>` offers no schema field property; the schema
suite is unchanged; both shipped configsets produce zero findings.

### PR 2 — Parameter completion and documentation

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

### PR 4 — Class navigation

Independent of PRs 0–3 and of the catalog. Blocked only on **Q2**.

A soft reference on a `class` attribute value, resolving through Java PSI, degrading to nothing when
the class is not on the classpath. Not dumb-aware — Java resolution is index-backed, unlike
everything else in this plugin.

**Gate:** Ctrl-click on `solr.SearchHandler` in the demo file lands in the class; an unresolvable
class produces no warning anywhere.

## Open questions, and who closes them

**Q1 — Where does the element vocabulary come from?** The specification names the shipped `_default`
and `sample_techproducts_configs` configsets as ground truth, and `solr-core-10.0.0.jar` contains no
`.xml` resources at all — verified by listing the artifact. The candidate that would retire the
question is `SolrConfig$SolrPluginInfo`, already on the scanned classpath: if it enumerates
`solrconfig.xml`'s legal plugin elements in a bytecode-readable form, this becomes one more generator
pass instead of two vendored files.

*Closed by:* one investigation against the resolved jar, sized in hours. **Do it before PR 1 is
scoped**, not during it.

**Q2 — Optional Java dependency, or defer class navigation to Phase 3?** `plugin.xml` carries
`com.intellij.modules.java` commented out and marked Phase 3. The optional-dependency route costs one
config file and makes the feature present in IDEA and absent elsewhere.

*Closed by:* the plan owner, not this record. It pulls a Phase 3 dependency forward, which is a
product decision.

**Q3 — Does `fq` get field references at all?** It is the parameter a reader would most expect to be
covered and the one the sixteen-name list cannot hold: `fq` takes query syntax, where a field name is a
fragment of the value rather than the whole of it, and every existing rule assumes a field list.
Covering it means parsing Solr query syntax far enough to find the field positions — a feature rather
than an extension, and one whose bad version puts false warnings on correct filters.

*Closed by:* nothing in this plan. **It blocks no PR here and is recorded so that omitting the
parameter readers most want is a decision rather than an oversight.**

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
