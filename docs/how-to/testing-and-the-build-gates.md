# Testing, and clearing the build gates

Two test conventions live in this repository, and what you are testing decides which one you get.
Then two gates, both bound to `check`, both firing on `./gradlew build` — so CI should never be the
first place you meet them.

## Which convention do I get?

| What you are testing | Base class | Method naming | Costs |
|---|---|---|---|
| Anything with PSI in it — inspections, completion, references, documentation, hints, detection | `BasePlatformTestCase` | `testSomething()` | A headless IDE per run |
| Anything importing nothing from the platform — `model`, the parsers, `SolrSchemaElements` | none; plain JUnit 4 | `` `backtick names` `` with `@Test` | Nothing |
| Anything touching `SolrConfigsetSettings` or `SolrConnectionSettings` | **`SolrConfigsetTestCase`** | `testSomething()` | A headless IDE per run |

Eleven of the thirty-two test files are in the second group. Put your test there if you can — booting
an IDE to exercise a pure function costs a second of wall-clock for nothing.

**`BasePlatformTestCase` is JUnit 3-style despite the JUnit 4 dependency.** Methods must be named
`testSomething()` and are discovered by that prefix, not by `@Test`. An `@Test` annotation on a
method named otherwise will simply not run — silently, reported as a passing class with fewer tests
than you wrote.

Conversely, a `testSomething()` name in the plain-JUnit group still runs, but it reads as a claim
that the test needs a platform it does not.

## Fixture tests

```kotlin
myFixture.addFileToProject("core/conf/managed-schema.xml", content)
myFixture.configureByText("managed-schema.xml", content)
```

**The path is part of the test's meaning, not incidental.** It shapes the directory structure the
detector's heuristics read — whether a `schema.xml` sits in a directory some self-identifying name
has already proven, whether two configsets are distinct. Choosing a path carelessly is choosing a
different test.

These are integration tests whatever they sit beside. They start a headless IDE, and
`checkHighlighting` runs the platform's real analysis pass.

### `checkHighlighting` fails in both directions

```kotlin
myFixture.checkHighlighting(true, false, false)
```

It fails on highlights the fixture did **not** mark as well as on ones it did. That is what makes the
zero-false-positive bar enforceable per test rather than only in review — a clean fixture is a real
assertion that nothing fires, not decoration.

Which is why, for an inspection, you write the clean cases first. Solr configuration is full of
syntax that resembles a field name without being one, and a warning on a correct file is what gets a
plugin uninstalled.

Expected messages go inline in the fixture:

```kotlin
check("""<field name="sku" type="<warning descr="Solr: no field type named 'stored' is declared in this configset">stored</warning>"/>""")
```

A message change breaks the test. That is intentional — user-visible strings are behaviour.

## `SolrConfigsetTestCase`, and why it is not optional

`BasePlatformTestCase` reuses **one light project across test methods *and* test classes**, and
`SolrConfigsetSettings` is a project-level `PersistentStateComponent`. Its state therefore survives
from one test into the next: a test that disables detection, or marks a manual root, silently changes
the starting conditions of everything that runs after it — including tests in other files, in an
order you do not control.

`SolrConfigsetTestCase` resets in `setUp` rather than `tearDown`, which keeps the guarantee one-sided
but reliable: it holds even when a preceding test fails partway through, or when state is left behind
by a class that does not extend it.

It also does something you will otherwise get wrong:

```kotlin
override fun setUp() {
    super.setUp()
    // ...
    givenSolrOnTheClasspath()
}
```

`SolrProjectDetector` gates everything on the project depending on a Solr client, which the light
fixture does not by default. **Without this, a test asserting that nothing fires would pass against a
project the plugin is correctly ignoring** — passing for the wrong reason, and staying green if the
feature broke entirely.

The library it adds carries no jar, because none is needed: the detector matches on the library
*name*, which is where Gradle and Maven put the Maven coordinates.

For the opposite case, assert the silence deliberately:

```kotlin
fun testNothingIsReportedOutsideASolrProject() {
    givenNoSolrOnTheClasspath()
    check("""<field name="sku" type="stored"/>""")
}
```

## When every fixture test fails at once

**A corrupted test sandbox is indistinguishable from a broken plugin until you know the signature.**

The fixture tests run against an IDE system directory at
`.intellijPlatform/sandbox/<project>/<IDE>/system-test`. It persists between runs, and because it
lives outside `build/` it **survives `./gradlew clean`** — which is what makes this expensive to
diagnose.

Three facts together are the diagnosis:

1. Every fixture test in the suite fails at once with the same
   `FileDeletedException: file[#N]: file is deleted, but still in [M].children list`
2. The failures are unaffected by `clean`
3. The plain JUnit 4 tests stay green

Delete the `system-test` directory and re-run. It had reached 279MB the first time this bit, so
deleting it costs one slower run and nothing else.

Do not go looking for the cause in the code under test — this failure is upstream of it, and
`git stash` will not clear it either.

## The documentation gate

Dokka runs with `reportUndocumented` and `failOnWarning`, and `dokkaGenerate` is a dependency of
`check`.

**Any public class, function or property in `src/main/kotlin` without KDoc fails the build**, naming
the declaration. Tests are exempt — only the `main` source set is gated. Overrides of platform
methods are still public declarations and still need KDoc.

```bash
./gradlew dokkaGenerate          # just the gate
open build/dokka/html/index.html # what it produced
```

The bar is not presence, it is usefulness. KDoc that restates the signature will pass the gate and
fail review. Write it explaining the decision — the real `isDumbAware()` override in
`SolrUnknownFieldTypeInspection` spends three sentences on why nothing there consults an index, which
is exactly what a future reader cannot recover from the code.

`internal` and `private` declarations are not gated, and making something `internal` to dodge the gate
is visible in review. If it is API, document it; if it is not, `internal` is the honest answer anyway.

Package and module prose lives in [`docs/Module.md`](../Module.md). Note that **KDoc reads `[foo]` as
a symbol link**, so Markdown reference-style links (`[text][ref]`) do not work there — use inline
`[text](url)` with an absolute URL, since Dokka renders that file somewhere other than GitHub.

## The coverage gate

Kover enforces an **80% line floor** via `koverVerify`, also bound to `check`. `SolrBundle` is
excluded — platform resource-bundle plumbing with no branches of its own.

```bash
./gradlew koverXmlReport   # build/reports/kover/report.xml
./gradlew check            # runs the verification
```

The floor sits below actual coverage on purpose. The headroom is so that landing hard-to-unit-test UI
and PSI code does not immediately block a PR the moment it arrives. It is a backstop against a sharp
drop, not a target — **SonarCloud's new-code gate is what catches gradual erosion**, and it looks at
the lines your PR touched rather than the project total.

So passing `koverVerify` locally does not mean your PR is clear. If your change adds a hundred
uncovered lines, the project figure barely moves and Sonar will still flag it.

## Running things

```bash
./gradlew build            # everything, both gates
./gradlew check            # tests + both gates, no packaging
./gradlew test             # tests only

./gradlew test --tests "*.SolrConfigsetDetectorTest"
./gradlew test --tests "*.SolrConfigsetDetectorTest.testSchemaUnderConfDirIsRecognized"
```

The IDE run configuration **Run Tests** in `.run/` is the same thing.

A green suite is not the same as a working feature. A reference contributor can pass its unit tests
and still do nothing in a real editor, because the platform registration is the part unit tests
routinely miss. The [demo runbook](../demo/README.md) is the acceptance harness for that gap — its
steps are written as things a user does, and they only pass when the whole path works.
