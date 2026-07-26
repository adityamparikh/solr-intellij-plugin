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
| `src/main/java/.../ProductSearch.java` | `categry:books` — typo for `category` | Field name checked from code |
| `src/main/java/.../ProductSearch.java` | `price` in `setFields` — never existed | Same, in a different argument |
| `src/main/java/.../Product.java` | `@Field("prce")` — typo | Same, in an annotation rather than a literal |

If a build or an IDE offers to clean these up, decline.

## Layout

```
solr/conf/managed-schema.xml   the schema, with its real "DO NOT EDIT" banner
solr/conf/solrconfig.xml       handlers; the /select qf names fields from the schema
src/main/java/com/example/demo Spring Boot app: plain SolrJ, wired by Spring
src/main/resources/application.yml   dev and staging profiles, each with its own Solr URL
compose.yaml                   local Solr 10, serving the configset above
```

Field names cross every boundary here without anything checking them: `qf` in `solrconfig.xml`
names fields defined in `managed-schema.xml`; `ProductSearch` names them again in Java strings; and
the Solr URL is a property reference resolved against whichever profile is active, not a literal.

## Running it

Solr, if you are exercising the server features:

```bash
docker compose -f demo/compose.yaml up -d   # http://localhost:8983
docker compose -f demo/compose.yaml down -v
```

The core is created from Solr's **default** configset; `solr/conf` here is deliberately not mounted
into the container, so the repository and the server genuinely differ. That is what gives the drift
comparison something to find, and it makes uploading the configset a real step rather than a no-op.

The application (needs the Solr above, since the client is constructed eagerly):

```bash
cd demo && ./gradlew bootRun
```

This is a **standalone Gradle build**. The plugin's `settings.gradle.kts` does not include it, on
purpose — it would otherwise be compiled by the plugin's build, counted against its coverage floor,
and scanned by its documentation gate.

## What the plugin currently does with it

Very little, so far. Opening `managed-schema.xml` gets XML highlighting rather than plain text,
which is the one user-visible behaviour the activation gate produces. Everything in the table above
is caught by features that have not been built yet; the implementation plan
([specs/plans/0002-solr-intellij-plugin-plan.md](../specs/plans/0002-solr-intellij-plugin-plan.md))
owns what has landed.
