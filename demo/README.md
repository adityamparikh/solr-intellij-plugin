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

**One consequence: this configset cannot be deployed as written.** A Solr core refuses to start on
a tokenizer class that does not exist or a field whose type nobody declares, and SolrCloud also
requires a `_version_` field this schema lacks, so uploading `solr/conf` to a real server fails.
`compose.yaml` deploys `../demo-server/products` instead: a ready configset standing for what the
server runs, with the defects repaired the way a real server might have them and a few more
differences, so the drift view has one row of each kind to show. Its header lists each difference.
It lives beside this project rather than in it because the plugin would otherwise read it as a
second configset of the project.

## Layout

```
solr/conf/managed-schema.xml   the schema, with its real "DO NOT EDIT" banner
solr/conf/solrconfig.xml       handlers; the /select qf names fields from the schema
src/main/java/com/example/demo Spring Boot app: plain SolrJ, wired by Spring
src/main/resources/application.yml   dev and staging profiles, each with its own Solr URL
compose.yaml                   local SolrCloud 10; creates products from ../demo-server/products
sample-products.json           ten products to index, from queries.http
queries.http                   Solr requests naming no host: index the samples, then search them
http-client.env.json           the "local" environment: the Solr above and its products collection
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

On first start it creates the `products` collection from `../demo-server/products`; later starts
reuse it, and `down -v` starts over. Solr never serves `solr/conf` itself, so the repository
and the server stay two things that can disagree, which is what the drift view compares.

Then, in the sandbox, open `queries.http`, pick the **local** environment, and run *Index the sample
products*. The requests below it search what that indexed, including the query `ProductSearch`
sends.

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
project for its examples. Its server features need the Solr above. In the drift view, `solr`
against `products` shows `sku` *Not deployed*, `legacy` and `custom_text` *Differs*, and
`manufacturer`, `_version_` and `plong` *Only on server*; Apply sends `sku` alone.
