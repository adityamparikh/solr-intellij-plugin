# Quick documentation on `class` attribute values

**Date:** 2026-07-29
**Status:** Approved

## What this is

Hovering `class="solr.StrField"` on a `<fieldType>` — or `class="solr.EdgeNGramFilterFactory"`
on a `<filter>`, and likewise for `<tokenizer>` and `<charFilter>` — answers with quick
documentation about the named class. Today the documentation provider answers on field names,
type references, field properties and the schema elements themselves; the `class` attribute,
the most-hovered thing in a schema after the field names, answers with nothing.

## Slicing

Two increments, decided up front:

1. **This design: the hover.** The popup shows what the class catalog and the field model
   already know — the kind of class, both name spellings, the attributes it accepts, a
   Reference Guide link, and schema-specific usage.
2. **A follow-up: the prose.** The catalog generator resolves the `-sources` artifacts and
   emits a Javadoc summary column, which enriches the same popup. This is Step 9's open
   documentation criterion and is out of scope here. Hand-writing descriptions is not a
   fallback: the plan forbids it — ~198 entries per Solr line, differing between lines.

## Trigger and data flow

`SolrConfigsetDocumentationProvider.documentedTarget()` gains a fourth case: the hovered
attribute value sits in a `class` attribute whose tag maps to a `SolrClassKind` →
`Target.Class(name, kind)`. The existing `getCustomDocumentationElement` value arm picks
this up with no structural change.

The tag→kind mapping — `fieldType`/`fieldtype` → `FIELD_TYPE`, `tokenizer` → `TOKENIZER`,
`filter` → `TOKEN_FILTER`, `charFilter` → `CHAR_FILTER` — moves to `SolrClassKind.forTag(tagName)`
in the model. It is a pure `String → enum?` function, so the model's no-IntelliJ-imports rule
holds. The completion contributor currently carries two private copies of this mapping; both
switch to the shared one, so a future kind cannot be added to completion and missed by
documentation.

`generateDoc`'s new arm resolves the name through `SolrClassCatalog.find(name, version)`,
which already matches both the `solr.`-prefixed spelling and the fully qualified one. The
version comes from the existing `versionOf(model)`; an unsupported or undeclared line falls
back to the newest shipped catalog, which is the catalog's existing behaviour.

## Popup composition

A new `classDocumentation(entry, specifics, version)` builder in `SolrFieldPresentation`,
in the house style of the existing popups:

- **Definition line:** the name as written, and the kind in words — *field type class*,
  *tokenizer factory*, *token filter factory*, *character filter factory*.
- **The fully qualified name**, in code font.
- **The attributes the class accepts**, from the catalog, each with its value type where one
  is known. This is the constructor-bytecode data reflection cannot supply, and it is the
  genuinely useful half before prose exists: "what can I configure on this factory".
- **Schema specifics**, for field type classes only: "Used by *N* field types in this schema:
  `string`, `strings`." — computed in `SolrSchemaElements` from `model.fieldTypes`, matching
  either spelling of the class name. Factories get no specifics line in this increment.
- **No prose slot yet.** The Javadoc summary joins in the follow-up increment.

`getUrlFor` gains the matching arm: `fieldTypesPage(version)` for field type classes, and the
existing — currently unused by hover — `analyzerComponentPage(className, version)` for the
three factory kinds.

## Edge cases

- **Unknown class** (someone's custom plugin class): no popup. The plugin says nothing rather
  than something false — the same rule the inspections follow.
- **A class in the wrong position** (a tokenizer factory named on a `<fieldType>`): the popup
  documents what the name refers to. Stating its kind is itself the honest answer; flagging
  the misplacement is the inspection's job, not the hover's.
- **Empty value:** no popup, via the existing emptiness guard.
- **Dumb mode:** unchanged. The catalog is a classpath resource and the model comes from the
  cached configset reader; nothing reads an index, so the provider stays `DumbAware`.
- **No server contact**, as on every editor path.

## Testing

- **Fixture tests** in `SolrConfigsetDocumentationProviderTest`: hovering `solr.StrField`
  shows the kind, the fully qualified name, an accepted attribute and the used-by line;
  hovering `solr.EdgeNGramFilterFactory` shows the factory popup and the Reference Guide URL;
  the fully qualified spelling resolves; an unknown class shows nothing.
- **Plain JUnit tests** for `SolrClassKind.forTag` and the specifics computation, which
  import nothing from the platform.
- **The existing completion tests** guard the mapping hoist — they fail if the shared
  mapping diverges from what completion offered before.

## Build gates

New public API (`SolrClassKind.forTag`, the presentation builder) needs KDoc in the same
change, or Dokka fails the build. Kover's 80% line floor holds as usual.
