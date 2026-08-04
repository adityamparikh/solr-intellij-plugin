# Declarations as targets: Find Usages from a field or field type

## Problem

Navigation runs one way. Ctrl-click from `type="text_general"` lands on the declaration; press Alt-F7
on that declaration and the IDE answers *Cannot search for usages from this location*.

The half that looks hard is already built. Every reference in
`org.apache.solr.ide.configset.reference` is anchored at a **use site** — `type=`, a `copyField` end,
a handler parameter in `solrconfig.xml`, a filter's resource attribute — and each resolves to the
`name` attribute value of the declaration, found by `SolrSchemaPsi`. Searching that graph in the
reverse direction works today: `SolrConfigFieldReferenceTest` calls `ReferencesSearch.search` on
`<field name="description">` and gets back the `qf` parameter from the other file. The edges are
there and they are traversable.

**What is missing is the step before the search: turning a caret position into something the
platform will search for.** Reading `TargetElementUtilBase` off the 2026.2 platform, it accepts a
caret as a target in exactly three ways — a reference at that offset, a `PsiNamedElement` whose text
offset coincides with the caret, or a `PomTarget` produced by `PomDeclarationSearcher.findDeclarationsAt`
and converted through `PomService.convertToPsi`. On `<fieldType name="text_general">` the plugin
offers none of them. There is no reference on a declaration. `XmlAttributeValue` is platform PSI the
plugin does not own and is not a `PsiNamedElement`. And nothing here implements a declaration
searcher.

Note what is *not* the cause, because it is the obvious suspect and it is innocent: the platform's
`XmlFindUsagesProvider.canFindUsagesFor` explicitly accepts `XmlAttributeValue`. The provider would
say yes. It is never asked.

**None of the above is inference.** `SolrDeclarationTargetTest` asks
`TargetElementUtil.findTargetElement` — the call the Find Usages action itself makes — at each
position in turn. A `field`, `dynamicField` and `fieldType` declaration each yield `null`, which *is*
the refusal the user sees; a `type=` reference and a cross-file `qf` name each yield a target; and
searching from a declaration reaches the `qf` parameter in the other file. Same string, same file,
refused on the declaration and resolved from the reference two lines away — that pair is the whole
diagnosis, and it is measured rather than argued.

**Rename fails the same way, and only that way.** `renameElementAtCaret` on a declaration throws
*element not found in file*: it resolves the same `null` target rather than reaching past it. The
worry that it might instead offer to rename the `<field>` tag — the corruption
`docs/modern-intellij-plugin-development.md` warns about — does not arise, because rename never gets
an element at all. Rename therefore needs this step to *gain* a target, not to *suppress* a wrong
one, which makes Step 8 smaller than it looks.

**Three documents currently disagree about this, and correcting them is part of the work.** The
[specification](../../../../specs/0002-solr-intellij-plugin.md) lists Find Usages for fields and
field types as an editor feature. The
[plan](../../../../specs/plans/0002-solr-intellij-plugin-plan.md)'s Step 5 ticks *"All four reference
kinds resolve; Find Usages returns every reference."* The
[manual suite](../../../manual-test-suite.md) is the one that is right: NAV-3 and NAV-4 record that
the search must be invoked **from a reference**, and that a declaration of either kind refuses it — a
correction made after a real sandbox pass rather than assumed. Demo step 27 says "Invoke Find Usages
on `text_general`", which reads as the declaration and fails as written.

## Goals

- Alt-F7 on a `<field>`, `<dynamicField>` or `<fieldType>` declaration lists every reference to it,
  across both files of the configset.
- **On a dynamic field, that means the names the pattern supplies and not only the pattern's literal
  spellings** — otherwise the result is an empty list on the declaration whose usages are hardest to
  find by hand, which is worse than the refusal it replaces.
- **Reuse the existing reference graph without adding a second search path.** Navigation, the
  inspections and Find Usages must not be able to disagree about which references exist; they already
  cannot, because they consult one resolution, and that must survive this change.
- Leave the plan, the specification and the demo script saying what the plugin does.

## Non-goals

- **Rename.** This lays the foundation Rename needs — a target the platform will accept, whose name
  is the string the user means — but the Shift+F6 behaviour, its `_after` fixtures and the plugin
  scaffold's leftover `src/test/testData/rename/` placeholders stay with Step 8. Splitting them
  follows how the inspections landed: one user-visible capability per pull request.
- **Use sites as targets.** A `copyField` end is a reference and Find Usages already works from it.
  Nothing changes there.
- **Inventing the concrete fields a pattern could match.** The line `SolrCopyFieldReference` draws
  stays drawn: which fields a pattern *could* match depends on the documents indexed, not on the
  schema, so no such name is ever conjured. What
  [dynamic fields](#dynamic-fields-searching-the-names-the-pattern-supplies) adds is the opposite
  direction and needs no invention — names already written in the configset, resolved through the
  model exactly as the references resolve them.
- **Migrating the plugin to the Symbol API.** Considered below and deliberately deferred.
- **`solrconfig.xml` declarations.** A request handler's `name` is not referenced from anywhere the
  plugin models, so making it a target would produce an empty result list and teach the reader that
  the search is broken.

## Design

### A declaration searcher, not a named element

The plugin cannot make `XmlAttributeValue` a `PsiNamedElement` — it is platform PSI. It must not
target the enclosing `XmlTag` either, and the reason is already written down in
`docs/modern-intellij-plugin-development.md`: for a tag, `getName()` means the *tag name*, so the
target of `<field name="description">` would be the string `field`. That mistake is why references
resolve to the attribute value in the first place.

That leaves the third door, which is an extension point rather than an interface on someone else's
PSI: **`pom.declarationSearcher`**. A `PomDeclarationSearcher` is asked "is there a declaration at
this offset in this element", and answers with a `PomTarget` that the platform converts into a
usable PSI element. It is the platform's own answer to *a declaration that is not its own named
element*, which is exactly the shape of a Solr schema.

The target delegates to the `XmlAttributeValue` that `SolrSchemaPsi` already returns —
`RenameableDelegatePsiTarget` is the ready-made class for this, and being renameable is what makes
Step 8 a smaller change later rather than a second foundation.

**The delegation is load-bearing, not a convenience.** The target's element must be *the same
element* every existing reference resolves to, because that identity is what `isReferenceTo`
compares. Point the target anywhere else and Find Usages would return a different set from the one
Ctrl-click navigates, which is the disagreement the second goal exists to prevent.

### Why not the Symbol API

`lang.symbolSearchTarget` and `rename.symbolRenameTargetFactory` are both registered in 2026.2, and
the Symbol API is where the platform is heading. It is still the wrong choice *here*, on grounds of
blast radius rather than taste: every reference in this plugin is a classic `PsiReference` found by
`ReferencesSearch`. Adopting symbol targets means either writing a `UsageSearcher` that bridges back
to `ReferencesSearch` — the new API wrapping the old one, for no visible gain — or converting all
four reference providers, which is a rewrite of the feature this one depends on.

The POM route reuses the search that is already tested. If the references ever move to
`PsiSymbolReference`, the target moves with them, and this record is where to start.

### Which declarations become targets

| Tag | Target | Because |
|---|---|---|
| `field` | its `name` attribute value | referenced by `copyField` ends and handler parameters |
| `dynamicField` | its `name` attribute value | same, and it is the same tag set `SolrSchemaPsi.findField` already searches |
| `fieldType` | its `name` attribute value | referenced by every `field`'s `type` |

Guarded by the configset check every reference provider already makes, and for the same reason:
`name` is the commonest attribute in XML, and without the guard this would offer a Solr target inside
a `pom.xml`.

Nothing else becomes a target. `uniqueKey`, `copyField` and the analyzer factories are either
references themselves or name things the plugin does not model as declarations.

### What the search returns

Nothing new has to be built. `ReferencesSearch` reaches every reference already, and the four
providers' `isReferenceTo` is the inherited comparison against `resolve()`. The one thing that must
be checked rather than assumed is that the platform equates the `PomTarget`-derived element with the
raw `XmlAttributeValue` the references resolve to. If it does not, the search returns nothing and the
feature is a more confusing failure than the refusal it replaces — so **the first implementation
action is a fixture proving Alt-F7 from a declaration returns the cross-file reference**, before any
of the rest is written.

### Dynamic fields: searching the names the pattern supplies

`SolrConfigFieldReference` resolves `body_t` through the model, so a `qf` naming `body_t` resolves to
`<dynamicField name="*_t">`. The reference genuinely points at that declaration.

The default search does not find it, and that is measured too: `SolrDeclarationTargetTest` puts
`body_t` in a `qf`, searches from `<dynamicField name="*_t">`, and gets nothing back.
`ReferencesSearch` picks candidates out of the word index *before* it asks any reference to confirm
itself, and the word it looks for is the declaration's own name — `*_t`. The text `body_t` shares no
word with it, so it is never a candidate, never offered to `isReferenceTo`, and never appears. Left
alone, Find Usages on a dynamic field lists the references that spell the pattern literally and
**silently omits every name that matched it** — an empty list that reads as "nothing uses this
pattern".

**A silently incomplete usage list is the failure this plugin's posture exists to prevent**, so the
matched names are searched too. It is also the only answer to the question a dynamic field actually
provokes, which is *what would I break by changing this pattern* — and the literal spellings are
never the interesting part of that answer.

**The mechanism is to stop fighting the word index and change the search space instead.** There is no
suffix query to ask it, but there does not need to be: the search space for a Solr field reference is
not the project, it is **the configset** — a handful of files the detector has already identified. So
a `referencesSearch` executor, entered only for a dynamic field target, walks the owning configset's
own reference positions — the same `SolrConfigParameters` occurrences and `copyField` ends the four
providers already read — and asks the model to resolve each one. An occurrence resolving to this
`dynamicField` is a usage.

**That is the same `SolrFieldModel.resolve` the reference's own `resolve()` calls**, which is what
keeps the two from disagreeing — the constraint the whole step exists to preserve. The executor is not
a second opinion about what a name means; it is the same opinion, asked from the other end.

Bounds, stated rather than discovered later:

| Bound | Consequence |
|---|---|
| entered only for a `dynamicField` target | concrete fields keep the default word-index path, which already works and is faster |
| scoped to the owning configset | a same-named field in a *different* configset is not a usage — Solr resolves per configset, so this is correctness, not a shortcut |
| declines before doing any work for any other target | the executor is consulted on every `ReferencesSearch` in the project, so the first check must be the cheap one |

Each usage is reported at the occurrence's own range, so `name^3 body_t` highlights `body_t` alone —
the ranges `SolrConfigParameters` already computes for the references.

## Testing strategy

**`SolrConfigsetTestCase` throughout**, not `BasePlatformTestCase` directly. This is target
resolution at a caret, so there is no pure-function half to test as plain JUnit 4 — and every test
here reaches configset detection, which reads settings. `BasePlatformTestCase` reuses one light
project across methods *and* classes, so settings leak between tests; the same base class also puts
a Solr client on the fixture's classpath, without which a test asserting that nothing resolves can
pass for the wrong reason. Both matter more here than usual, because most of these assertions are
absences.

**The starting point is already written down.** `SolrDeclarationTargetTest` pins where Find Usages
stops today: three declarations yielding no target, two references yielding one, the reverse search
reaching across the file boundary, and the dynamic pattern missing the name it supplies. **This step
inverts the three `yieldsNoTarget` assertions**, and that inversion is the cleanest available proof
it did what it claimed — a suite that went from asserting an absence to asserting a presence, rather
than new tests grading their own homework.

- The cross-file case first, as described above: caret on `<field name="description">`, and the `qf`
  parameter in `solrconfig.xml` is among the usages.
- One per target kind — `field`, `dynamicField`, `fieldType` — that the caret yields a target at all.
- **The dynamic-field case gets its own pair**, because it is the one the default search cannot do:
  `<dynamicField name="*_t">` with a `qf` naming `body_t` reports that occurrence, at the range of
  `body_t` alone; and a `copyField dest="*_t"` spelling the pattern literally is still reported
  beside it, since the executor adds to the default result rather than replacing it.
- **The negative cases carry the weight, as they do for the intentions.** A `name=` attribute in a
  non-configset XML file yields no Solr target; `<requestHandler name="/select">` yields none; a
  caret on the attribute *name* rather than its value yields none; and a same-named field in a
  second configset in the same project is not reported.
- The declaration's own name is not reported as a usage of itself.

## Registration

Two extension points in `plugin.xml`:

- `<pom.declarationSearcher>` — no language attribute; declaration searchers are registered globally
  and decline by inspecting the element, which the configset guard already does.
- `<referencesSearch>` — the dynamic-field executor, likewise global and likewise declining first.

Dumb-aware like everything else here, and the same promise about data sources: both read schema PSI,
the model and the detector, and contact nothing.

## Risks

- **The target and the reference targets are not equated.** The one genuine unknown, and the reason
  the proving fixture comes first rather than last. If it fails, the fallback is to have the
  references resolve to the POM-derived element instead of the raw attribute value — a change inside
  `SolrSchemaPsi` and its four callers, contained but not free.
- **A declaration searcher runs on every caret movement in every XML file, and the references
  executor on every search in the project.** Mitigated by the same ordering the reference providers
  use: cheapest check first, and the configset detector is cached. The executor's first act is to ask
  whether the target is a Solr dynamic field, which is a type check.
- **The configset walk is O(configset), not O(project) — but it is a walk.** Acceptable because Find
  Usages is an explicit, occasional gesture rather than something on the highlighting path, and
  because the alternative bound is the whole project. It must not migrate onto the editor path.
- **Find Usages now works where the manual suite says it does not.** NAV-3 and NAV-4 describe the
  refusal as expected behaviour; both must be rewritten in the same change, or the suite will fail a
  correct plugin.

## Delivery

**[Step 28 in the plan](../../../../specs/plans/0002-solr-intellij-plugin-plan.md), placed in the
Editor track immediately before Rename.** It closes a criterion Step 5 claimed and did not have, and
it is the prerequisite Step 8 would otherwise have to build before it could start.

Shipped, this step must also correct: Step 5's success criterion in
[the plan](../../../../specs/plans/0002-solr-intellij-plugin-plan.md), the
[specification](../../../../specs/0002-solr-intellij-plugin.md)'s Find Usages line if it needs
qualifying, NAV-3 and NAV-4 in [the manual suite](../../../manual-test-suite.md), and demo step 27's
gesture.

**Acceptance:** demo step 27 as written — caret on the `text_general` *declaration*, Alt-F7, every
field using it appears — plus the NAV-3 screenshot at `docs/images/07-find-usages-field-type.png`,
which can then be shot from the declaration rather than from a reference.
