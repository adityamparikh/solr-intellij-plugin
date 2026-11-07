# Demo fixture

The project `./gradlew runIde` opens by default: a configset that a human edits, and a Spring Boot
application that names fields from that configset in unchecked string literals. To open something
else, pass `-PrunIdeProject=/path/to/project`, or `-PrunIdeProject=` to open nothing.

What the plugin shows on this project is in [the user guide](../docs/user-guide.md), and the checks
pressed against it are in [the manual test suite](../docs/manual-test-suite.md). The talk built on
it is [the demo runbook](../docs/demo/README.md).

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

## The defects are the point — do not fix them

Every one of these compiles, and every one fails silently at runtime. If a build or an IDE offers to
clean them up, decline.

| Where | What | Why it is here |
|---|---|---|
| `solr/conf/managed-schema.xml` | `copyField source="manufacturer"` — no such field | Dangling reference inspection |
| `solr/conf/managed-schema.xml` | field `legacy` has `type="discontinued"` — no such type | Undeclared field type inspection |
| `solr/conf/managed-schema.xml` | field `notes` has type `custom_text`, whose analyser names `com.example.MyTokenizerFactory` — not a real factory | The match-hint's unrecognised-chain silence, distinct from `legacy`'s undeclared-type silence |
| `src/main/java/.../ProductSearch.java` | `categry:books` — typo for `category` | Field name checked from code |
| `src/main/java/.../ProductSearch.java` | `price` in `setFields` — never existed | Same, in a different argument |
| `src/main/java/.../Product.java` | `@Field("prce")` — typo | Same, in an annotation rather than a literal |

**One reference resolves through a pattern, and it is not a defect.** The `/select` handler's `pf`
names `body_t`, which no `<field>` declares; it resolves through `<dynamicField name="*_t">`, as
Solr resolves it. `DemoConfigsetTest` pins the resolution and `SolrDemoFindUsagesTest` pins Find
Usages from the pattern, so removing either half fails a build.

**This configset cannot be deployed as written.** A Solr core refuses to start on a tokenizer class
that does not exist or a field whose type nobody declares, and SolrCloud also requires a
`_version_` field this schema lacks. `compose.yaml` deploys `../demo-server/products` instead: the
configset the server runs, with the defects repaired and a few deliberate differences for the drift
view. Its header lists each one. It lives outside `demo/` because the plugin would otherwise read it
as a second configset of the project.

## Running it

Solr, for the server features:

```bash
docker compose -f demo/compose.yaml up -d   # http://localhost:8983
docker compose -f demo/compose.yaml down -v # stop, and drop the data volume
```

The first start creates the `products` collection from `../demo-server/products`; later starts reuse
it, and `down -v` starts over. Then, in the sandbox, open `queries.http`, pick the **local**
environment, and run *Index the sample products*.

The application, which starts without Solr and runs until stopped:

```bash
cd demo && ./gradlew bootRun
```

This is a **standalone Gradle build**. The plugin's `settings.gradle.kts` does not include it, so it
is not compiled by the plugin's build, counted against its coverage floor, or scanned by its
documentation gate.
