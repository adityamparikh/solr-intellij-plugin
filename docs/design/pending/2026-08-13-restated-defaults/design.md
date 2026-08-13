# Restated defaults: showing which attributes carry no weight

## Problem

A schema renders `indexed="true"` identically whether deleting it would change the index or change
nothing at all. The difference is not in the file. It depends on what the field's `<fieldType>`
declares, on the class that type names, and on the version the schema's own root element states —
three places the reader would have to resolve in their head, for every attribute, to know which
lines are load-bearing.

The plugin already resolves exactly that. The property table behind quick documentation marks each
value as declared or defaulted, so a reader who hovers an attribute learns what it decides. What no
surface shows is the same fact *without* being asked, at the place where an author auditing a schema
would use it: on the attribute itself.

## What this is not

**Not an inspection.** A restated default is correct Solr. The standing rule is that inspections do
not fire on correct files, and an underline here would be the plugin manufacturing a problem in
order to have something to say. The platform has an idiom for "true but removable" — the dimmed
rendering it gives redundant code — and this uses it, silently and at information severity, so
nothing reaches the Problems view and no problem count moves.

**Not an opinion about the attribute.** Restating a default is sometimes deliberate: writing
`indexed="true"` can record that the author considered the question. The claim is narrow and factual
— deleting this would leave the same field — and the removal is an intention rather than a
quick-fix precisely because an intention carries no assertion that anything is wrong.

## The rule: knowability, not category

The plan's original action 3 said to stay silent on any property whose default depends on the field
type. That was written before the catalog carried type traits, when a type-dependent default could
not be resolved at all. It now can: `omitNorms` is true for a `solr.StrField` and false for a
`solr.TextField`, and the catalog proves which by reading the class's ancestry.

So the test is **whether the value can be determined**, not which category the property falls into:

| Origin | Dims? |
|---|---|
| Solr's own flat default | yes |
| Schema-version default | yes |
| Inherited from the `<fieldType>` | yes |
| Type default the catalog proves | yes |
| `UNDETERMINED` | **never** |

`UNDETERMINED` is the case that matters, and it is not an edge case: a type naming a class outside
the catalog — a custom plugin, the normal case in a real schema — makes every type-dependent default
a guess. Dimming on a guess invites a deletion that changes the index, which is the one failure this
feature must not have. Silence there is the same vow the class popup takes.

This amends the plan's second success criterion, which said a property whose default depends on the
field type never dims. The replacement is that a property whose default cannot be *determined* never
dims.

**Only an exact string match dims.** Solr would read `TRUE` as true, and this does not, so an
attribute spelled that way is never offered for removal. That is a missed dim rather than a wrong
one, which is the direction to be wrong in.

## Shape

The judgement lives in `model`, where it can be tested without an IDE:

```kotlin
SolrFieldProperties.restatesDefault(property, writtenValue, fieldType, schemaVersion, typeTraits)
```

It is the existing resolution with the field's own declaration set aside — and once that is set
aside the answer no longer depends on the field at all, only on the type, the version and the
class's traits. That fell out of the implementation rather than being designed, and it is why the
function takes no field: `resolve` and `restatesDefault` now share one tail, so the dim cannot drift
from the popup that reports the same defaults.

Two editor surfaces read one predicate, `SolrRestatedAttribute.isRestated`:

- `SolrRestatedDefaultAnnotator` renders it, as `NOT_USED_ELEMENT_ATTRIBUTES` over the whole
  attribute — name and value together, since removing half of one is not a thing.
- `SolrRemoveRestatedAttributeIntention` acts on it.

They share the predicate rather than each computing it, because a reader shown a dimmed attribute
that Alt-Enter then declines to remove has been told two different things about the same file.

## What the tests have to prove

This inverts the usual burden. An inspection is dangerous when it fires on a clean file; here
*every* file it fires on is clean, and the dangerous case is dimming an attribute whose removal would
change the field — because the reader is being invited to delete it.

So the load-bearing assertion is not the resulting text but that the field resolves identically
afterwards: the intention test captures every effective property before and after and compares them.
A deletion that changed one would still produce plausible XML and pass any text comparison.

**The label collided, and writing the test first is what caught it.** `Remove attribute` is an
intention the platform's own XML support already offers, so the negative cases — asserting nothing is
offered — failed while the positive ones passed against a stock IntelliJ feature. A suite written
after the code would have been green with this intention absent entirely.

## Both elements, and what differs between them

A `<field>` resolves through the type it names before reaching Solr's defaults. A `<fieldType>` *is*
that layer, so it answers to the defaults and its own class's traits directly — and the absence of
anything above it is expressed by passing no type at all to the same model function. The second half
needed no new rule, only the PSI half knowing which element it is looking at.

**Scope is what separates them, and it produced a defect in the first half.** Every property is legal
on a `<fieldType>`; only some are legal on a `<field>`. `enableGraphQueries` is type-only and
defaults to true, so the field half — which compared any known property — dimmed it on a `<field>`,
reporting it as removable. The conclusion was accidentally true and the reason was wrong: Solr
ignores that attribute on a field outright, which is a different thing to tell the reader and not
this feature's to tell. The predicate now declines a property outside the element's scope.

**`<dynamicField>` is included, and the pattern is the only thing that makes one different.** It
names a type exactly as a concrete field does, and none of these properties is about the pattern —
`indexed` means the same for the fields it will match as for a field written out. The model had
already reached that conclusion: `FOR_FIELD` is documented as the properties legal on a field *or* a
dynamic field, so excluding it here would have been the editor disagreeing with the table it reads.
Its `name` is a declaration rather than a property and is never dimmed, exactly as a field's is.
