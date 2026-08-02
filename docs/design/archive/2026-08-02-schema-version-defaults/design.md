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
- Doc values cannot be added to a field that is already indexed, so the `1.7` default is not a
  setting a running collection can simply adopt — which is what the re-index above is for.

The population is therefore split durably, both halves are current on Solr 9 and 10, and *"why isn't
`docValues` on for this field?"* is the most common question the change produced. Answering that with
the schema's own version in hand is the plugin's stated reason to exist.

## Goals

- Resolve every version-dependent default against the schema's declared version.
- Say *why* a value holds, when the reason is the schema version rather than Solr as such.
- Treat an absent `version` as `1.0`, which is what Solr does — not as "latest".

## Non-goals

- **`omitTermFreqAndPositions`.** Its `TextField` exception is a fourth trait for a property nobody
  hovers. Additive whenever it earns its keep.
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
classes have which trait. That half shipped as a second increment and needed no branch interpretation
either, because `ClassHierarchy` already answered both questions it asks.

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

That leaves `uninvertible`, `useDocValuesAsStored` and `multiValued` answerable from the version
alone, and `uninvertible` is the one that is actively wrong today.

### The two that also need the type's class

`omitNorms` and `docValues` need the field type's *class* as well as the version, so the version half
alone cannot resolve them — asserting `docValues` true at `1.7` for a `solr.TextField`, which does not
get it, would be a new wrong answer in place of an honest silence.

The generator answers the class half as hierarchy questions and writes them to a `traits` column:

| Trait | Means | Decides |
|---|---|---|
| `primitive` | descends `PrimitiveFieldType` | `omitNorms`, above version 1.4 |
| `spatialPrefixTree` | descends `AbstractSpatialPrefixTreeFieldType` | `omitNorms`, at every version |
| `sortableText` | descends `SortableTextField` | `docValues`, at every version |
| `docValuesByDefault` | `enableDocValuesByDefault()` resolves true | `docValues`, from version 1.7 |

The last is read from bytecode rather than derived from ancestry because `DenseVectorField` descends
from `PrimitiveFieldType`, which returns true, and overrides it back to false — only the nearest
declaration is the answer. `RankField` needs no special case for the same reason: it inherits the
base's `false`, and its explicit clearing of the bit is redundant.

**The distinction the whole thing rests on** is between *no traits* and *unknown class*. A type
naming a class the catalog does not carry resolves to nothing at all and stays `UNDETERMINED`; a
known class carrying no trait resolves to a definite `false`. Collapsing the two would make every
custom plugin type report a confident wrong answer.

### Model changes

`SolrConfigsetFacts` and `SolrFieldModel` gain `schemaVersion: String?`, parsed by `SolrSchemaParser`
from the root element and carried like `luceneMatchVersion` already is. Absent means Solr's `1.0`,
and so does a value that will not parse — a half-typed attribute is the normal state of a file being
edited, and the model has to keep answering while it is. Both mappings live in the model, not the
parser, so the parser keeps reporting only what the file says.

`SolrSchemaVersion` renders its own label, so the version a popup names is read from the same
normalized value resolution used. A schema declaring nothing therefore reads as `1.0` — the version
its defaults came from — rather than as the absent text in the file.

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
- An absent version resolves as `1.0`, not as the newest, and so does an unparseable one.
- Documentation names the version a value came from, so both of those render as `1.0`.
- A value declared on the field or type still wins over any version rule.
- Properties with no version dependence keep reporting `SOLR_DEFAULT`.
- `SolrSchemaParser` reads the attribute, and its absence, from the root element.

The demo stays at `version="1.6"` deliberately, with a comment saying so: it is the fixture that
exercises the interesting branch, and after this change it demonstrates the feature rather than
contradicting the plugin.
