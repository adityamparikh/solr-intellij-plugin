# What an attribute means: documentation for the position between the element and the value

## Problem

A tag offers a caret three positions. Two of them answer.

Hovering `<fieldType>` explains what a field type is and what this one does. Hovering
`solr.TextField` — the attribute's *value* — gives the class summary and a guide link. Hovering
`class`, the attribute's *name*, gives nothing. So does `name`, `type`, `source`, `dest`, and both
attributes on the schema root.

**This was found in a sandbox pass, not by reading the code**, which is the second time that has
happened for this provider and worth noting: what a provider *declines* to answer is invisible to a
test suite that only asserts what it does answer.

Three separate causes sit behind one symptom, and
`SolrSchemaDocumentationProvider.documentedProperty` shows two of them in four lines:

```kotlin
if (tag.name !in SolrSchemaTags.FIELD && tag.name !in SolrSchemaTags.FIELD_TYPE) return null
return SolrFieldProperties.byName(attribute.name)
```

The tag check excludes `<schema>` outright, so its `name` and `version` never reach the provider at
all. The `byName` lookup then covers only *field properties* — `indexed`, `stored`, `docValues`,
`multiValued` and their siblings — so on a `<fieldType>`, which passes the tag check, `name` and
`class` still fall through to null.

The third has a different shape. A factory attribute *does* answer — `minGramSize` on an
`EdgeNGramFilterFactory` reports its owner, that it is required, and that it holds *a whole number*.
That is a type, not a meaning. A reader who wants to know what an edge n-gram of 2 to 15 indexes
learns nothing from it that the file did not already show them.

## Goals

- The structural attributes explain themselves: `name`, `class`, `type`, `source`, `dest`.
- `<schema>`'s `name` and `version` answer, and `version` answers *specifically* — it is the most
  consequential attribute in the file and the model already knows what it does.
- A factory attribute states what it does, where that is known, alongside the type and default it
  already reports.
- Nothing is invented anywhere.

## Non-goals

- **`solrconfig.xml`'s vocabulary.** Its plugins and their parameters are not in the catalog — the
  generated file carries `fieldType`, `tokenizer`, `tokenFilter` and `charFilter` and nothing else —
  so there is no source to document them from. That is [Step 25](../../../../specs/plans/0002-solr-intellij-plugin-plan.md#step-25-solrconfigxml-as-a-first-class-surface-done)
  and its generator dependency, not this.
- **Prose for every factory attribute.** The table is deliberately partial; see below.
- **Replacing the Reference Guide.** The link stays the supplement it already is.

## Design

### Structural attributes are documented per element, not per name

`name` means something different on a `<field>` than on a `<copyField>` — on the first it is the
declaration, on the second it is not a field name at all. So the table is keyed by the pair, and a
name the plugin does not model on that element answers nothing rather than answering generically.

| Element | Attribute | Says |
|---|---|---|
| `field`, `dynamicField` | `name` | the name documents use; for a dynamic field, the pattern |
| `field`, `dynamicField` | `type` | names a `fieldType` this schema declares |
| `fieldType` | `name` | the name fields reference from their `type` |
| `fieldType` | `class` | the Java class implementing the type |
| `copyField` | `source`, `dest` | the two ends of the copy rule, and which direction |
| `schema` | `name` | identifies the schema; carries no behaviour |
| `schema` | `version` | what it decides, and what *this* value decides |

### `version` is the one that earns configset-specific prose

`SolrSchemaVersion` already models this — it is read on every field-property resolution to decide
what an undeclared attribute falls back to. The popup therefore states the general rule *and* the
consequence here: at `1.6`, `docValues` defaults off; at `1.7`, on, with `uninvertible` off. That is
the same answer the property popups already compute, surfaced at the attribute that causes it.

### A hand-written table for factory attribute meanings, and why that is not a reversal of principle

[Step 10](../../../../specs/plans/0002-solr-intellij-plugin-plan.md#step-10-completion-validation-and-quick-documentation-done)
declined per-attribute prose, and the reason it gave was sound: the catalog is generated from
bytecode, Javadoc is written per class, so no generated source carries it. Every word of that
remains true.

What it got wrong was treating *no generated source* as *no source*. The rule this plugin actually
holds is that it does not invent facts — a computed default is recorded as absent rather than
guessed, an unknown class stays silent rather than claiming an empty attribute set. A short table of
what `minGramSize` does, written by hand from the Reference Guide, invents nothing. It is a fact,
recorded.

The maintenance argument that justified generating the catalog does not transfer either. The catalog
had to be generated because it is hundreds of classes and thousands of attributes that move every
Solr line. This table is the two dozen attributes a reader actually hovers, and they have not
changed in years — `minGramSize` has meant the same thing since 2012.

**Bounds, so the table does not grow into the thing it is not:**

| Bound | Consequence |
|---|---|
| silent for any attribute not on it | an unlisted attribute keeps exactly the popup it has today — owner, type, default. Nothing regresses |
| meaning only; never a type, default or requiredness | those are the catalog's, proved from bytecode, and must not be restated by hand where they could disagree |
| keyed by attribute name, not by class | `minGramSize` means the same on every n-gram factory; keying by class would triple the table to say one thing |

The last bound has a limit worth stating: an attribute name that genuinely means two different
things on two factories cannot be expressed, and would have to be keyed by class if one ever turns
up. None does today.

**A sandbox pass then settled it further than the argument had.** Hovering `minGramSize` shows the
popup already carries a *per-attribute* Reference Guide link — `minGramSize` on solr.apache.org —
beside the per-class one. That was not known while the table was being drafted. The prose would
therefore have duplicated a link that is already there, already specific to the attribute, and
always current, which is the exact trade the FAQ's rule is about.

**What would close it properly is a generated source.** The guide is ALv2 AsciiDoc with per-filter
pages carrying attribute tables; a build-time extraction would put this on the same footing as the
catalog — mechanical, refreshed per line, no second body of prose to maintain. The cost is a new kind
of build input, since the guide is not a Maven artifact. That is a step of its own, and the decision
between it and *leave the link to do the work* has not been made.

## Testing strategy

Plain JUnit 4 for the table itself — it is a pure function from a pair of strings to prose, and
booting an IDE to ask what `minGramSize` means costs a second for nothing. `BasePlatformTestCase`
only for the provider wiring, which is where the caret positions live.

The assertions that carry weight are the absences, as they were for the intentions: an attribute the
table does not list must produce the *unchanged* popup rather than an empty one, and an element the
plugin does not model must answer nothing at all. A regression here is a popup that appears where it
should not, which is worse than one that does not appear.

## Delivery

One step, split by the three causes rather than shipped at once — the structural table, the schema
root, and the factory meanings are independent of each other and each is demonstrable alone.
