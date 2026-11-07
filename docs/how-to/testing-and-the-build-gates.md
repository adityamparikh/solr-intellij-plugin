# Testing and the build gates

Which test style a change needs, what `./gradlew build` checks before a PR is mergeable, and what
to do when the build fails for reasons that are not your code. Every gate below except
`verifyPlugin` is bound to `check`, so `./gradlew build` meets it locally before CI does; run
`./gradlew verifyPlugin` for that one.

## Writing tests

### Which convention a test gets

| What you are testing | Base class | Method naming |
|---|---|---|
| Anything with [PSI](../glossary.md#psi) in it — [inspections](../glossary.md#inspection), completion, references, documentation, hints, detection | [`BasePlatformTestCase`](../glossary.md#baseplatformtestcase) | `testSomething()` |
| Anything touching `SolrConfigsetSettings` or `SolrConnectionSettings` | **`SolrConfigsetTestCase`** | `testSomething()` |
| Anything importing nothing from the platform — `model`, the parsers, `SolrSchemaElements` | none; plain JUnit 4 | `` `backtick names` `` with `@Test` |

Prefer the plain JUnit tier when you can: a fixture test boots a headless IDE, which costs a second
of wall-clock that a pure function does not need. The tier is decided by the base class a test
extends, not by its imports — a test extending `SolrConfigsetTestCase` boots an IDE without
importing anything from `com.intellij` itself.

**`BasePlatformTestCase` is JUnit 3-style despite the JUnit 4 dependency.** Methods are discovered by
the `test` prefix, not by `@Test`. An `@Test` method named otherwise does not run, and the class is
reported as passing with fewer tests than you wrote.

### Fixture tests

```kotlin
myFixture.addFileToProject("core/conf/managed-schema.xml", content)
myFixture.configureByText("managed-schema.xml", content)
```

**The path is part of the test's meaning.** It shapes the directory structure the detector reads —
whether a `schema.xml` sits under a `conf` directory, whether two
[configsets](../glossary.md#configset) are distinct.

`checkHighlighting` fails on highlights the [fixture](../glossary.md#fixture) did **not** mark as
well as on ones it did, so a clean fixture is a real assertion that nothing fires. For an inspection,
write the clean cases first.

```kotlin
myFixture.checkHighlighting(true, false, false)
```

Expected messages go inline in the fixture, so a changed message breaks the test:

```kotlin
check("""<field name="sku" type="<error descr="Solr: no field type named 'stored' is declared in this configset">stored</error>"/>""")
```

### `SolrConfigsetTestCase`

`BasePlatformTestCase` reuses one light project across test methods *and* classes, and the plugin's
settings are project-level `PersistentStateComponent`s, so state left by one test changes the
starting conditions of the next. `SolrConfigsetTestCase` resets both settings in `setUp`, which holds
even when a preceding test failed partway through.

It also calls `givenSolrOnTheClasspath()`. `SolrProjectDetector` activates the plugin only in a
project that depends on a Solr client, which the light fixture does not; without it, a test asserting
that nothing fires passes against a project the plugin is correctly ignoring. The library it adds
carries no jar, because the detector matches on the library name.

For the opposite case, assert the silence deliberately:

```kotlin
fun testNothingIsReportedOutsideASolrProject() {
    givenNoSolrOnTheClasspath()
    check("""<field name="sku" type="stored"/>""")
}
```

### What fixtures cannot see

Fixture tests build their subject directly — `myFixture.enableInspections(SomeInspection())` — so
nothing in them goes through [`plugin.xml`](../glossary.md#pluginxml). A registration names its
class as a string, and a wrong name compiles while the platform loads nothing.
`SolrPluginDescriptorTest` closes that: it resolves every class name in `plugin.xml` by reflection,
and checks every registered inspection has an `inspectionDescriptions/<shortName>.html`. A new
[extension point](../glossary.md#extension-point) is covered without any change to the test.

What only a running IDE shows — whether a hint renders, whether a popup reads well — is the
[manual test suite](../manual-test-suite.md)'s job.

### The configsets Solr ships

`SolrShippedConfigsetTest` runs the registered inspections over `_default` and
`sample_techproducts_configs` from each supported Solr line, and asserts they report nothing. The
files are vendored verbatim under `src/test/resources/shipped-configsets/<line>/<name>/` —
`solrconfig.xml` and `managed-schema.xml` only; a feature that comes to read a third file brings it
along. Each file's own `<luceneMatchVersion>` selects its catalog. They are excluded from SonarCloud
and must never be edited to satisfy an analyser.

One rule is held out by name: `SolrUnusedFieldTypeInspection`, because Solr ships field types no
field uses yet and reporting them is correct. `testTheHeldOutRuleIsWhyItIsHeldOut` asserts it does
fire there, so a regression that silenced it still fails.

### Contract tests against a real Solr

`SolrServerContractTest` starts a real Solr per supported line through Testcontainers and reads it —
the only tier that notices Solr changing a response shape. Without Docker it skips locally, reported
as skipped; in CI (where `CI` is set) a missing Docker fails it.

### Running tests

```bash
./gradlew build            # compile, test, and every gate
./gradlew check            # tests and gates, no packaging
./gradlew test             # tests only

./gradlew test --tests "*.SolrConfigsetDetectorTest"
./gradlew test --tests "*.SolrConfigsetDetectorTest.testSchemaUnderConfDirIsRecognized"
```

The **Run Tests** configuration in `.run/` is the same thing.

## The build gates

### Documentation: Dokka

[Dokka](../glossary.md#dokka) runs with `reportUndocumented` and `failOnWarning`, and `dokkaGenerate`
is a dependency of `check`. **Any public class, function or property in `src/main/kotlin` without
[KDoc](../glossary.md#kdoc) fails the build**, naming the declaration. Overrides of platform methods
are public declarations too. Tests are exempt; so are `internal` and `private` declarations.

```bash
./gradlew dokkaGenerate          # just the gate
open build/dokka/html/index.html # what it produced
```

The gate checks presence; review checks usefulness. Write KDoc that explains the decision rather
than restating the signature.

Package and module prose lives in [`docs/Module.md`](../Module.md). KDoc reads `[foo]` as a symbol
link, so Markdown reference-style links (`[text][ref]`) do not work there — use inline
`[text](url)` with an absolute URL.

### Coverage: Kover

[Kover](../glossary.md#kover) enforces an **80% line floor** via `koverVerify`, bound to `check`.
`SolrBundle` is excluded — resource-bundle plumbing with no branches of its own.

```bash
./gradlew koverXmlReport   # build/reports/kover/report.xml
```

The floor sits below actual coverage on purpose, as a backstop against a sharp drop. SonarCloud's
new-code gate looks at the lines your PR touched, so passing `koverVerify` locally does not mean a
PR adding many uncovered lines is clear.

### Formatting: Spotless

Spotless checks Google style as part of `check`: ktfmt for Kotlin and the Gradle scripts (including
`demo/`'s), google-java-format for `demo/src/**/*.java`. Run `./gradlew spotlessApply` before
committing rather than formatting by hand.

### Plugin verification: `verifyPlugin`

```kotlin
val verifiedIdeBuilds = listOf("2026.2")
```

This list in `build.gradle.kts` is what the IntelliJ Plugin Verifier checks the plugin against, and
`SolrCompatibilityPageTest` holds [the compatibility page](../compatibility.md) to it. `since-build`
is 262 with no upper bound; the list grows by one on each platform bump, in the same commit. It
reports deprecated API use as well as incompatibilities. CI runs it as its own job.

The plugin targets IntelliJ IDEA only. `com.intellij.modules.java` and `com.jetbrains.restClient`
are hard `<depends>` in `plugin.xml`, because IDEA bundles both.

`verifyPlugin` does not check that the descriptor's own class names resolve — that is
`SolrPluginDescriptorTest`, above.

### Supported Solr lines

`supportedSolrLines` in `build.gradle.kts` is where a line is added or dropped. The same list also
appears as `SolrClassCatalog.SUPPORTED_LINES` and a private twin in `SolrElementCatalog`, and
`SolrCatalogResourceTest` fails when they drift: a line declared with no generated resource reads as
empty, and a line generated but never declared silently falls back to the newest line's answers.

A line bump also refreshes the shipped configsets:

```bash
line=10; version=10.0.0
for cs in _default sample_techproducts_configs; do
  for f in solrconfig.xml managed-schema.xml; do
    curl -sSf -o "src/test/resources/shipped-configsets/$line/$cs/$f" \
      "https://raw.githubusercontent.com/apache/solr/releases/solr/$version/solr/server/solr/configsets/$cs/conf/$f"
  done
done
```

## Troubleshooting

### Every fixture test fails at once

**That is a corrupted test [sandbox](../glossary.md#sandbox), not a broken plugin**, when all three
hold:

1. Every fixture test fails with the same platform-internal error, for example
   `FileDeletedException: file[#N]: file is deleted, but still in [M].children list` or
   `An exception during updateWithMap(). Index DomFileIndex will be rebuilt.`
2. `./gradlew clean` does not change it.
3. The plain JUnit 4 tests stay green.

The fixture tests run against `.intellijPlatform/sandbox/<project>/<IDE>/system-test`, which lives
outside `build/` and so survives `clean`. Delete it and re-run.

### `NoSuchMethodError` after you moved or changed a type

The error names the *old* signature of something you just changed. Two causes, both outside your
code:

- **Gradle's build cache** served a stale `compileTestKotlin` output. The cache lives outside
  `build/`, so `clean` does not clear it.
- **Kotlin's incremental compiler** missed a changed synthetic signature, typically `…$default`
  after adding a defaulted parameter.

```bash
./gradlew --rerun-tasks --no-build-cache compileTestKotlin   # clears both
./gradlew --no-build-cache check                             # green, and repopulates the cache
```

To tell which one it was, read what the freshly built test class references:

```bash
javap -c -p build/classes/kotlin/test/<path>/YourTest.class | grep '\$default'
```

### Containers will not start

`Could not find a valid Docker environment` names neither cause nor fix. The usual cause is an API
version mismatch: a recent Docker Engine answers Testcontainers' negotiated version with HTTP 400 and
an empty body. The build pins `api.version` to 1.44 for the test JVM; override it with
`DOCKER_API_VERSION`:

```bash
DOCKER_API_VERSION=1.47 ./gradlew test --tests "*SolrServerContractTest*"
```

Do not add an SLF4J binding to the test classpath to see Testcontainers' logging: the platform
ships its own, and a second one breaks unrelated fixture tests.

`build.gradle.kts` excludes three of Testcontainers' transitive dependencies that the platform also
ships in other versions: JNA, jackson-annotations, and slf4j-api. Leaving any of them in breaks
fixture tests that have nothing to do with containers.
