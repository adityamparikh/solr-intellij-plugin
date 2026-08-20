# Explaining a boost: documentation for the `^n` in a parameter value

## Problem

A caret on the `^3` of `qf`'s `name^3` answers nothing. The `name` beside it answers fully — the
field's resolved properties, its match capability, and where each value came from. Two characters
later, in the same token, the plugin has nothing to say.

The silence was inherited rather than decided.
[`SolrConfigParser.fieldTokenAt`](../../../../src/main/kotlin/org/apache/solr/ide/configset/solrconfig/parsing/SolrConfigParser.kt)
returns null when the caret sits inside a boost:

```kotlin
// A `^` puts the caret inside a boost rather than in the name it boosts.
if (boostable && '^' in token) return null
```

That is right for completion — completing there would write `name^name` — and
[the manual check](../../../../docs/manual-test-suite.md#9-completion--field-names-inside-solrconfigxml-parameters-prm)
asserts the silence deliberately. But completion's correct silence was read as settling the
position, and documentation was never asked. The parameter work in
[Step 25](../../../../specs/plans/0002-solr-intellij-plugin-plan.md#step-25-solrconfigxml-as-a-first-class-surface-done)
made that worse rather than better: a reader who has just been told what `qf` is meets `^3` in the
same value and gets nothing.

**Half the feature already exists.** `SolrFieldReference.boost` is parsed by `boostedFieldName`,
carried through the model, and asserted in `SolrConfigParserTest` and `DemoConfigsetTest` — and read
by nothing in `src/main`. The parser had to find the `^` to know where the field name ended, so it
kept what followed. This design gives that property its first consumer.

This is the first thing the plugin would say about a parameter's *value* grammar rather than its
name.

## Goals

- A caret anywhere in the boost — on the `^` or in the number after it — explains what the boost
  does.
- The explanation is specific to the parameter. `qf`, `pf`, `pf2`/`pf3`, `bf` and `boost` scale
  different things and must not share a sentence.
- Where the boost follows a field, name that field and what the configset resolves it to.
- Say when a written boost changes nothing.

## Non-goals

- **No new inspection, and nothing new in the Problems view.** See *A popup may judge where an
  inspection may not* below for the line this holds.
- **No completion after `^`.** The existing silence is correct and its manual check stays.
- **No function-query parsing.** `bf` holds `recip(rord(price),1,1000,1000)`; this design reads the
  `^2.5` after it and nothing inside it.
- Nothing about boosts written in a query at runtime. This is about configset text, like everything
  else on the editor path.

## Design

### The position test is a sibling of the completion one, not a reuse of it

A new function in `SolrConfigParser` beside `fieldTokenAt`, sharing its separator and boostable
logic. The two answer different questions and must not be collapsed: `fieldTokenAt` returns the
token *before* the caret, because completion replaces what has been typed so far, while
documentation needs the whole token *under* the caret. Writing the second in terms of the first
would give a popup that changes as the caret moves within one unchanged `^3`.

They live next to each other because
[`SolrConfigParameters`](../../../../src/main/kotlin/org/apache/solr/ide/configset/solrconfig/SolrConfigParameters.kt)
already states the rule: the boundaries agree "by construction rather than by two people remembering
the same thing." A boost's extent is decided by the same separator set that decides a field name's.

### What the popup says

Three parts, in order: what the boost does, the field it applies to, and whether it changes
anything. Only the first is always present.

| Parameter | What the boost scales |
|---|---|
| `qf` | the term match on that field |
| `pf` | the phrase match on that field |
| `pf2` | the two-word phrase (bigram) match |
| `pf3` | the three-word phrase (trigram) match |
| `bf` | the function query's value, which is **added** to the score |
| `boost` | the whole score, which is **multiplied** by the function query's value |

An absent boost is `1.0`, and the popup says so — that is the fact that makes `^1` legible as
pointless rather than as something the reader has misread.

### `bf` and `boost` get their own wording, and departing from the inspection is deliberate

[`SolrNonIndexedRelevanceFieldInspection`](../../../../src/main/kotlin/org/apache/solr/ide/configset/solrconfig/inspection/SolrNonIndexedRelevanceFieldInspection.kt)
stays silent on `bf` and `boost` because their values are function queries read per document, so
boosting a non-indexed field there is correct and common. **That is a decision about reporting a
defect, not about explaining syntax.** The `^` is still a boost, the reader hovering it still gets
nothing today, and the honest answer there differs from `qf`'s rather than being absent.

So the two consumers read the same `BOOSTABLE_PARAMETERS` set differently, and that is the point
worth writing down: the inspection inherits its *semantic* membership and documentation its
*tokenizing* membership. A third consumer must decide which, rather than discover it.

What documentation must not do in those two is name a field. In `recip(rord(price),1,1000,1000)^2.5`
the boost follows a function, so the field line drops and only the syntax half renders. Naming
`recip` as a field would be the same class of error the parser's `plainFieldName` exists to prevent.

### A popup may judge where an inspection may not

The plugin's rule is that **inspections must not fire on a correct file**, and `^1` is a correct
file. This design still says that `^1` changes nothing, because a popup is pull and an inspection is
push: an inspection annotates a file nobody asked it about, while a popup answers a question a
reader asked by putting a caret somewhere. Observing that a value is the default is a fact about the
value, not a complaint about the file.

The line that keeps this honest is presentational, and it is a requirement rather than a preference:
the observation renders as ordinary prose in the popup body, never as a warning or an error, adds
nothing to the Problems view, and produces no highlight. If it ever wants a colour, it has become an
inspection and belongs in a step that argues for one.

### Every claim about the field is tri-state, like the rest of them

[`SolrFieldOperations.supports`](../../../../src/main/kotlin/org/apache/solr/ide/model/schema/SolrFieldOperations.kt)
returns `Boolean?`, where null means the schema has not clearly stated an answer — a field type
naming a class this build has never seen. The field line renders a searchability claim only on a
definite answer, and drops it otherwise. This is the criterion
[Step 27](../../../../specs/plans/0002-solr-intellij-plugin-plan.md#step-27-saying-what-a-propertys-value-means-done)
already holds for the match hint: a property that resolves to `UNDETERMINED` contributes no phrase
while the others still render.

A boost on a field the schema does not declare keeps the syntax half and drops the field half.
Reporting the undeclared name is the unknown-field inspection's job, and a popup that repeated it
would be a second voice on one mistake. A field a `<dynamicField>` supplies resolves like any other,
by the parser's existing longest-literal rule.

### A boost that is not a number

Solr rejects `name^abc` when it parses the query, so the handler answers every request with an
error. **This design documents it and does not inspect it**: the popup states what a valid boost is,
which is enough for a reader looking straight at `^abc`, and the step stays a documentation step.

Recording the alternative so it is not lost: an inspection for a non-numeric boost is a real defect
report of exactly the kind this plugin likes — a quiet, total failure with nothing in the configset
saying so. It needs its own clean fixtures and its own argument about `${...}` parameter references,
which can appear where a literal number would, and it should be proposed on its own rather than
folded in here.

### What it declines

Nothing outside a configset. Nothing in a parameter that holds no field names. A `^` in a parameter
outside the boostable set, where it is an ordinary character. And a caret on the field name, which
keeps exactly the popup it has today — the regression that matters most here is an existing popup
changing.

## Testing strategy

**Plain JUnit 4 for the position function**, with no fixture, since it imports nothing from the
platform. The boundaries are where this will break: caret on the `^` itself, immediately after it,
at the end of the value, inside the second token of `name^3 description`, on a token with no `^`,
and on a `^` with nothing after it.

**A fixture test for the popup that enters through `IdeDocumentationTargetProvider`**, not by asking
the provider. This is the lesson the plan recorded when the class-value popup died in dumb mode:
where a platform decides who answers, at least one test has to start where the user starts. A test
that calls `generateDoc` directly passes with the position test missing entirely, which is the
defect most likely to ship here.

**A test that the field-name caret is unchanged**, pinning the popup that already works.

`SolrDumbModeContractTest` covers the provider already and needs nothing new: it is
`DumbAware`, and this path reads configset text and the field model, neither of which touches an
index.

**Silence, asserted:** `fl` with a `^` in it, and a `^` inside a parameter outside the boostable set.

## Delivery

One pull request, documentation-only in effect and small in code:

1. The position function in `SolrConfigParser`, beside `fieldTokenAt`.
2. A third branch in `SolrConfigDocumentationProvider.getCustomDocumentationElement`, which today
   claims a parameter-name `XmlAttributeValue` and a `defType` `XmlText`.
3. `SolrConfigPresentation.boostDocumentation`, alongside the two presentation functions there.
4. The tests above.
5. A [manual test suite](../../../../docs/manual-test-suite.md) entry beside section 9's existing
   check that completion is silent after a `^` — the two belong together, because the pair is the
   whole point: silent in completion, not silent in documentation.

**The demo needs a `bf` before any of this can be demonstrated.** It carries a `qf` with `name^3`
and a `pf` with no boost at all, so the function-query half of this design — the half with the
wording argument behind it — has no position in the demo configset to be hovered. A capability whose
acceptance gesture cannot be performed is one nobody checks, so adding it is part of the step rather
than a follow-up.
