# Schema-version defaults: the third version number

## Problem

The plugin reports `uninvertible` as defaulting to `false`. On the demo configset it is `true`.

`SolrFieldProperties` records one default per property. Solr does not have one default per property.
Several of them are decided by the `version` attribute on the schema's root element:

```xml
<schema name="products" version="1.6">
```

`FieldType.setArgs` branches on it directly:

```java
if (schemaVersion >= 1.6f) properties |= USE_DOCVALUES_AS_STORED;
if (schemaVersion >= 1.7f && enableDocValuesByDefault()) properties |= DOC_VALUES;
if (schemaVersion < 1.7f)  properties |= UNINVERTIBLE;
```

So `demo/solr/conf/managed-schema.xml`, which declares `1.6`, has `uninvertible` defaulting to `true`
for every field. Quick documentation says `false`, attributed to "Solr default". That is the
confidently-wrong statement `SolrFieldProperties`' own KDoc sets out to avoid, on the one configset
the plugin opens in `./gradlew runIde`.

### This is a third version number

The plugin already tracks two, and this is neither.

| Version | Declared in | Range | Decides |
|---|---|---|---|
| Solr line | derived from `luceneMatchVersion` | `9`, `10` | which catalog file to read |
| `luceneMatchVersion` | `solrconfig.xml` | `10.0.0` | analysis back-compatibility |
| **`version` on `<schema>`** | **the schema** | **`1.0`–`1.7`** | **what field attributes default to** |

It is a property of the file, not of the server. Solr 10 honours a `version="1.1"` schema's 2008
defaults, because the attribute is how Solr changed its defaults without breaking existing schemas.

### It is a live case, not legacy cruft

- Schema `1.7` shipped in **Solr 9.7.0** (September 2024, SOLR-12963) — inside the supported 9 line.
- Upgrading Solr does not touch the attribute. The schema is the user's file.
- Moving `1.6` → `1.7` *"will generally require a re-index of all data"* (Major Changes in Solr 9), so
  users stay at `1.6` deliberately and indefinitely.
- Solr's own `_default` and `sample_techproducts_configs` are `1.7`, so new configsets start there.
- The `1.7` docValues default caused real production breakage severe enough to warrant vendor
  advisories.

The population is therefore split durably, both halves are current on Solr 9 and 10, and *"why isn't
`docValues` on for this field?"* is the most common question the change produced. Answering that with
the schema's own version in hand is the plugin's stated reason to exist.

## Goals

- Resolve every version-dependent default against the schema's declared version.
- Say *why* a value holds, when the reason is the schema version rather than Solr as such.
- Treat an absent `version` as `1.0`, which is what Solr does — not as "latest".

## Non-goals

- **Per-field-type defaults.** `omitNorms` (true for `PrimitiveFieldType` descendants) and `docValues`
  (true at `1.7` for types whose `enableDocValuesByDefault()` returns true) also need the type's
  *class*, which means a new catalog column. That is the next increment; this one leaves both
  reporting `UNDETERMINED`, which is honest and is what they already do.
- **The exotic types.** `RankField`, `DenseVectorField` and the spatial types tweak the property mask
  unconditionally in their own `init`. Rare, additive later, and `UNDETERMINED` is safe meanwhile.
- **Warning about an old schema version.** Deciding whether `1.6` is a problem is the user's call, and
  an inspection that fires on every pre-2024 configset would be noise.

## Design

### Where the rules live: hand-maintained, beside the table

The version rules are hand-written next to the properties they qualify, and are *not* generated.

This is the same argument `SolrFieldProperties` already makes for the property table itself, and it
holds more strongly here. There are five rules. They are documented in prose in the Reference Guide,
which has a page enumerating exactly which types gain docValues at `1.7`. They changed once between
2019 and 2026. And they define semantics rather than enumerating what happens to exist.

The alternative — recovering them from bytecode — means teaching the catalog generator to interpret
branches on a float comparison, which is a materially different and hackier extractor than the
literal-reading one that exists. The generator's job is the enumerative half: which of ~60 field-type
classes have which trait. That half is the next increment and does not need branch interpretation
either, because `ClassHierarchy` already answers both questions it asks.

The split to hold: **semantics are hand-written where they are few and documented; enumerations are
generated.** That is the line the project already draws.

### The rules

Read from `FieldType.setArgs`, `TextField.init` and `PrimitiveFieldType.init`:

| Property | Default is `true` when | Otherwise |
|---|---|---|
| `multiValued` | `< 1.1` | `false` |
| `omitTermFreqAndPositions` | `> 1.1` (and the type is not a `TextField`) | `false` |
| `useDocValuesAsStored` | `>= 1.6` | `false` |
| `uninvertible` | `< 1.7` | `false` |
| `docValues` | `>= 1.7` **and** the type supports it | `false` |

`docValues` stays `UNDETERMINED` in this increment: the version half is necessary but not sufficient,
and asserting `true` at `1.7` for a `solr.TextField` — which does not get it — would be a new wrong
answer in place of an honest silence. Same for `omitTermFreqAndPositions`, whose `TextField` exception
needs the class.

That leaves `uninvertible`, `useDocValuesAsStored` and `multiValued` fully answerable now, and
`uninvertible` is the one that is actively wrong today.

### Model changes

`SolrConfigsetFacts` and `SolrFieldModel` gain `schemaVersion: String?`, parsed by `SolrSchemaParser`
from the root element and carried like `luceneMatchVersion` already is. Absent means Solr's `1.0`;
that mapping lives in the model, not the parser, so the parser keeps reporting only what the file
says.

`SolrPropertyOrigin` gains one value:

```kotlin
/** Declared in neither, and Solr's default for this schema's declared version applies. */
SCHEMA_VERSION_DEFAULT,
```

`SOLR_DEFAULT` keeps its meaning — a default that holds at every schema version — so the two are
genuinely different facts rather than a relabelling. Quick documentation renders the new one as
`Solr default at schema version 1.6`, because on a `1.6` schema the version is the actionable half of
the answer: it is what the user would have to change.

`SolrFieldProperties.resolve` and `effectiveFor` take the schema version. Both call sites are in
`documentation/`, and both already hold the model.

## Testing

Plain JUnit 4 — `SolrFieldProperties` imports nothing from the platform and must not start to.

- `uninvertible` resolves `true` at `1.6` and `false` at `1.7`, with origin `SCHEMA_VERSION_DEFAULT`.
- `useDocValuesAsStored` resolves `false` at `1.5` and `true` at `1.6`.
- An absent version resolves as `1.0`, not as the newest.
- A value declared on the field or type still wins over any version rule.
- Properties with no version dependence keep reporting `SOLR_DEFAULT`.
- `SolrSchemaParser` reads the attribute, and its absence, from the root element.

The demo stays at `version="1.6"` deliberately, with a comment saying so: it is the fixture that
exercises the interesting branch, and after this change it demonstrates the feature rather than
contradicting the plugin.
