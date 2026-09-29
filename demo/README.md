# Demo fixture

The project `./gradlew runIde` opens by default, and the fixture the demo runbook
([docs/demo/README.md](../docs/demo/README.md)) is written against.

It is the shape a real service using Solr actually has: a configset that a human edits, and a
Spring Boot application that names fields from that configset in unchecked string literals.

## The defects are the point — do not fix them

Every one of these compiles, and every one fails silently at runtime. They exist so the plugin has
something true to say.

| Where | What | Why it is here |
|---|---|---|
| `solr/conf/managed-schema.xml` | `copyField source="manufacturer"` — no such field | Dangling reference inspection |
| `solr/conf/managed-schema.xml` | field `legacy` has `type="discontinued"` — no such type | Undeclared field type inspection |
| `solr/conf/managed-schema.xml` | field `notes` has type `custom_text`, whose analyser names `com.example.MyTokenizerFactory` — not a real factory | The match-hint's unrecognised-chain silence, distinct from `legacy`'s undeclared-type silence |
| `src/main/java/.../ProductSearch.java` | `categry:books` — typo for `category` | Field name checked from code |
| `src/main/java/.../ProductSearch.java` | `price` in `setFields` — never existed | Same, in a different argument |
| `src/main/java/.../Product.java` | `@Field("prce")` — typo | Same, in an annotation rather than a literal |

If a build or an IDE offers to clean these up, decline.

**One consequence: this configset cannot be deployed.** A Solr core refuses to start on a tokenizer
class that does not exist or a field whose type nobody declares, so uploading `solr/conf` to a real
server fails — which is why `compose.yaml` does not mount it. The drift and upload demos need a
collection built from a deployable configset; a copy of this one with `custom_text`, `notes` and
`legacy` removed is enough.

## Layout

```
solr/conf/managed-schema.xml   the schema, with its real "DO NOT EDIT" banner
solr/conf/solrconfig.xml       handlers; the /select qf names fields from the schema
src/main/java/com/example/demo Spring Boot app: plain SolrJ, wired by Spring
src/main/resources/application.yml   dev and staging profiles, each with its own Solr URL
compose.yaml                   local Solr 10 with a products core from Solr's own default configset
queries.http                   a Solr request naming no host; the environment supplies it
http-client.env.json           the "local" environment: the Solr above and its products core
```

Field names cross every boundary here without anything checking them: `qf` in `solrconfig.xml`
names fields defined in `managed-schema.xml`; `ProductSearch` names them again in Java strings; and
the Solr URL is a property reference resolved against whichever profile is active, not a literal.

**One reference deliberately resolves the hard way, and it is not a defect.** The `/select`
handler's `pf` names `body_t`, which no `<field>` declares — it resolves through the schema's
`<dynamicField name="*_t">`, exactly as Solr resolves it. It is the only reference here whose target
is a pattern rather than a name, which makes it the fixture for anything that has to follow a glob:
resolution, and a usage search that has to reach a name the pattern *supplies* rather than one that
spells it. Both halves are pinned by `DemoConfigsetTest`, so removing either fails a build.

## Running it

Solr, if you are exercising the server features:

```bash
docker compose -f demo/compose.yaml up -d   # http://localhost:8983
docker compose -f demo/compose.yaml down -v
```

The core is created from Solr's **default** configset; `solr/conf` here is deliberately not mounted
into the container, so the repository and the server genuinely differ. That is what gives the drift
comparison something to find, and it makes uploading the configset a real step rather than a no-op.

The application. It starts without Solr, since building the client opens no connection, and nothing
queries at startup; it keeps running until stopped, because the client's threads hold the JVM open:

```bash
cd demo && ./gradlew bootRun
```

This is a **standalone Gradle build**. The plugin's `settings.gradle.kts` does not include it, on
purpose — it would otherwise be compiled by the plugin's build, counted against its coverage floor,
and scanned by its documentation gate.

## What the plugin shows for each defect

Each defect in the table above is there for one thing the plugin says about it:

| Defect | What you see |
|---|---|
| `copyField source="manufacturer"` | A red wavy underline, an error. Alt-Enter offers the declared fields as replacements, closest spelling first. |
| `legacy`'s `type="discontinued"` | A red wavy underline, an error. The field also gets no inlay hint, because nothing can be said about a type nobody declares. |
| `notes`'s `custom_text` | No error, on purpose. The inlay hint keeps what the field stores and drops what it matches, because the analyzer names a factory the plugin does not recognise. |
| `categry:books`, `price` in `setFields` | A warning on the name alone, stopping at the colon. `id` and `name` beside `price` stay unmarked. |
| `@Field("prce")` | The same warning, read from the annotation. |

The `pf` naming `body_t` is the reverse case: it is correct, and nothing is reported on it. Go to
Declaration on `body_t` lands on `<dynamicField name="*_t">`, and Find Usages on that pattern finds
the `pf`.

The rest of what the plugin does is in [the user guide](../docs/user-guide.md), which uses this
project for its examples. Its server features need the Solr above. In the drift view, the
container's `products` core was built from Solr's default configset, not from `solr/conf`, so
comparing the two finds real differences.
