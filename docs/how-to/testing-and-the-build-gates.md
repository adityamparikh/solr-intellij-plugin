# Testing, and clearing the build gates

> **Who this is for.** A Java engineer who wants to know which test style a change needs, and what
> the two build gates check before a PR is mergeable.
> **Read first:** [Glossary](../glossary.md) if Solr or IntelliJ Platform terms are new ·
> [Contributing](../contributing.md) for the build commands these gates hook into.

Two test conventions live in this repository, and what you are testing decides which one you get.
Then two gates, both bound to `check`, both firing on `./gradlew build` — so CI should never be the
first place you meet them.

## Which convention do I get?

| What you are testing | Base class | Method naming | Costs |
|---|---|---|---|
| Anything with [PSI](../glossary.md#psi) in it — [inspections](../glossary.md#inspection), completion, references, documentation, hints, detection | [`BasePlatformTestCase`](../glossary.md#baseplatformtestcase) | `testSomething()` | A headless IDE per run |
| Anything importing nothing from the platform — `model`, the parsers, `SolrSchemaElements` | none; plain JUnit 4 | `` `backtick names` `` with `@Test` | Nothing |
| Anything touching `SolrConfigsetSettings` or `SolrConnectionSettings` | **`SolrConfigsetTestCase`** | `testSomething()` | A headless IDE per run |

Eleven of the thirty-two test files are in the second group. Put your test there if you can — booting
an IDE to exercise a pure function costs a second of wall-clock for nothing.

> **In Java terms.** Plain JUnit over `model` is an ordinary unit test. `BasePlatformTestCase` is
> closer to `@SpringBootTest` than to a unit test — it boots a real, if headless, IDE with its own
> project context, which is why it costs a second instead of a millisecond. There is no third tier
> here that goes further and drives a running IDE window the way a Selenium test drives a browser;
> what that would catch is left to the manual test suite instead (see the last section below).

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
has already proven, whether two [configsets](../glossary.md#configset) are distinct. Choosing a path
carelessly is choosing a different test.

These are integration tests whatever they sit beside. They start a headless IDE, and
`checkHighlighting` runs the platform's real analysis pass.

### `checkHighlighting` fails in both directions

```kotlin
myFixture.checkHighlighting(true, false, false)
```

It fails on highlights the [fixture](../glossary.md#fixture) did **not** mark as well as on ones it
did. That is what makes the zero-false-positive bar enforceable per test rather than only in review —
a clean fixture is a real assertion that nothing fires, not decoration.

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

**A corrupted test [sandbox](../glossary.md#sandbox) is indistinguishable from a broken plugin until
you know the signature.**

The fixture tests run against an IDE system directory at
`.intellijPlatform/sandbox/<project>/<IDE>/system-test`. It persists between runs, and because it
lives outside `build/` it **survives `./gradlew clean`** — which is what makes this expensive to
diagnose.

Three facts together are the diagnosis:

1. Every fixture test in the suite fails at once with the same platform-internal error. Two
   spellings have been seen so far, and the corruption wears others:
   `FileDeletedException: file[#N]: file is deleted, but still in [M].children list`, and
   `An exception during updateWithMap(). Index DomFileIndex will be rebuilt.`
2. The failures are unaffected by `clean`
3. The plain JUnit 4 tests stay green

Delete the `system-test` directory and re-run. It had reached 279MB the first time this bit, so
deleting it costs one slower run and nothing else.

Do not go looking for the cause in the code under test — this failure is upstream of it, and
`git stash` will not clear it either.

## What the fixtures structurally cannot see

Every fixture test builds its subject directly — `myFixture.enableInspections(SomeInspection())`,
`SolrSchemaParser.parse(text)` — which is what makes them fast and precise, and also means **nothing in
the suite goes through [`plugin.xml`](../glossary.md#pluginxml)**. A registration names its class as a
string, so a wrong name compiles and every test passes while the platform finds nothing to load.

That is not hypothetical: a package rename moved three inspections and left three registrations pointing
at the package that used to hold them. `./gradlew build` was green and all three were dead in the IDE.

**`verifyPlugin` does not close this, which was checked rather than assumed.** With one
`implementationClass` pointed at a deleted package, the IntelliJ Plugin Verifier reports nothing and the
task succeeds — it verifies API compatibility against IDE builds, not that this descriptor's own class
names resolve. It is worth running for what it does cover, and CI runs it as its own job.

`SolrPluginDescriptorTest` is what closes it: plain JUnit, reflection over every class name in
`plugin.xml`, milliseconds, and it runs in `./gradlew build` beside everything else. **If you add an
[extension point](../glossary.md#extension-point), that test already covers it** — there is nothing
to remember. It carries a second
descriptor check for the same reason: an inspection registered with no
`inspectionDescriptions/<shortName>.html` still works, and shows a blank panel in Settings →
Inspections where its explanation should be. Nothing else notices, because no fixture test ever
renders that pane.

There is a deeper version of the same blind spot, and it is about fixtures rather than registrations.
**A hand-written clean fixture is composed by the author of the rule it reassures**, which makes it
evidence about the cases that author thought of and silent about the rest. Every clean fixture in this
suite was hand-written but four.

## The configsets Solr ships

`SolrShippedConfigsetTest` runs every inspection `plugin.xml` registers — all of them but one, held
out by name at the end of this section — over four configsets nobody here wrote: `_default` and
`sample_techproducts_configs`, from both supported lines. It asserts they report nothing. That is the
only fixture in the repository nobody here wrote, and it earned its place on the first run by finding
a false positive in a shipped rule.

The files are vendored verbatim under `src/test/resources/shipped-configsets/<line>/<name>/`, and
only the two the plugin parses: `solrconfig.xml` and `managed-schema.xml`. A feature that comes to
read a third — a stopword list, a mapping table — brings that file with it.

**Nothing in this test names an exact Solr release, and that is deliberate.** Each `solrconfig.xml` declares
its own `<luceneMatchVersion>` — `9.12` and `10.3`, which are *Lucene* versions — so each fixture
selects its own catalog. Both generated catalogs are exercised without a line ever being written down
twice.

**Refreshing them is part of a line bump.** When `supportedSolrLines` in `build.gradle.kts` changes,
these files are the other place the old release survives:

```bash
line=10; version=10.0.0
for cs in _default sample_techproducts_configs; do
  for f in solrconfig.xml managed-schema.xml; do
    curl -sSf -o "src/test/resources/shipped-configsets/$line/$cs/$f" \
      "https://raw.githubusercontent.com/apache/solr/releases/solr/$version/solr/server/solr/configsets/$cs/conf/$f"
  done
done
```

They are excluded from SonarCloud analysis. They are not this project's code and must never be edited
to satisfy an analyser — the whole point of them is that nobody here wrote them.

**One rule is held out by name**, and the hold-out is exactly one exception, not a general escape
valve: eleven inspections are registered in `plugin.xml`, and ten of them are asserted, across all
four shipped configsets, to report nothing at all. The eleventh, `SolrUnusedFieldTypeInspection`, is
excluded from that assertion — by name, not by filtering on what it happened to say — because it
reports 45 true findings on `sample_techproducts_configs` and two more on `_default`: Solr ships a
palette of language and spatial types for fields the copier has not written yet, and reporting an
unused type there is correct, not a bug. That makes it the only rule in this suite whose finding is a
fact about the file rather than a defect in it, and so the one rule a *zero findings* gate cannot hold
without lying.

Being excluded from the zero-findings assertion is not the same as being untested, which is what
"pinned from both sides" means. The first pin is the one every other rule already has: the exclusion
only applies to these four shipped configsets, so if `SolrUnusedFieldTypeInspection` ever fired
somewhere else in the suite, that fixture's own zero-findings test would still fail. The second pin
is a test of its own, `testTheHeldOutRuleIsWhyItIsHeldOut`, which asserts the rule *does* fire on
`sample_techproducts_configs` — so a regression that silenced it entirely, which would otherwise hide
behind its own exclusion, fails that test instead
(`src/test/kotlin/org/apache/solr/ide/configset/editing/SolrShippedConfigsetTest.kt:38-40,102-112`).

## Which Solr lines are supported, and the two copies of the answer

`supportedSolrLines` in `build.gradle.kts` is where a line is added or dropped. It is not, despite
what its comment says, the only place the answer is written down: `SolrClassCatalog.SUPPORTED_LINES`
and a private twin inside `SolrElementCatalog` are the same list in another language, and each has a
KDoc promising it is kept in step.

**Drift is silent in both directions**, which is why `SolrCatalogResourceTest` exists:

- a line **declared with no generated resource** reads as *empty* — deliberately, so a missing
  catalog cannot take the editor down — so completion and documentation stop answering for that line
  with nothing in the log;
- a line **generated but never declared** ships inside the plugin, and every configset targeting it
  quietly falls back to the newest line's answers.

If you add or drop a line, that test tells you which copies you missed. Adding one phantom line to
`SUPPORTED_LINES` fails five of its six tests.

## What `verifyPlugin` checks against

```kotlin
val verifiedIdeBuilds = listOf("2026.2")
```

**One list, in `build.gradle.kts`, and it is the subject of the gate rather than a preference.** Left
unconfigured, the task verifies against whatever the build happens to target — a gate whose subject
is decided elsewhere changes its answer without a commit here, which is the same hole the pinned
`pluginVerifier("…")` version closed one shape smaller. The compatibility matrix is due to be
rendered from this list rather than restated beside it.

One entry is the honest number, not a placeholder: `since-build` is 262 with no upper bound, so the
plugin claims every build from 2026.2 onward, and nothing can verify a build that has not shipped.
The list grows by one on each platform bump, in the same commit.

**It reports deprecation as well as incompatibility**, which is the class of finding no test here can
produce — today, one `ReadAction.compute(ThrowableComputable)` usage.

**IntelliJ IDEA only, and the list is complete rather than a first entry.** The plugin targets IDEA
and nothing else. Worth stating, because a one-item list otherwise reads as one somebody forgot to
extend.

The descriptor used to imply something wider: `com.intellij.modules.java` was an *optional*
dependency with its one registration in a separate `solr-withJava.xml`, so that an IDE without Java
PSI would load the plugin and simply lack class navigation. Nothing verified that and nothing
needed to — IDEA has bundled Java in a single unified distribution since 2025.3, so the condition
was true wherever it was evaluated. It is a hard dependency now, and the descriptor and this list
say the same thing.

## The documentation gate

[Dokka](../glossary.md#dokka) runs with `reportUndocumented` and `failOnWarning`, and
`dokkaGenerate` is a dependency of `check`.

> **In Java terms.** Dokka is this project's Javadoc — it renders [KDoc](../glossary.md#kdoc)
> comments into API documentation the same way `javadoc` renders `/** */` blocks. `failOnWarning` is
> the part with no familiar default: an undocumented public declaration fails the build exactly as a
> missing `@Override` never would, rather than merely showing up as a warning to skim past.

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

[Kover](../glossary.md#kover) enforces an **80% line floor** via `koverVerify`, also bound to
`check`. `SolrBundle` is excluded — platform resource-bundle plumbing with no branches of its own.

> **In Java terms.** Kover is this project's JaCoCo: an instrumentation-based line-coverage tool
> bound into the build's verification lifecycle, not a separately run report you have to remember to
> generate.

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

## A test failing with `NoSuchMethodError` after you moved a type

The symptom is a test that cannot possibly be wrong — it calls a function whose
signature you just changed, and the error names the *old* signature:

```
java.lang.NoSuchMethodError: 'org.apache.solr.ide...SolrConfigParser$SolrBoostOccurrence
  org.apache.solr.ide...SolrConfigParser.boostAt$org_apache_solr_ide_solr_intellij_plugin(...)'
```

That is Gradle's **build cache** serving a stale `compileTestKotlin` output, not your
change. It survives `./gradlew clean`, because `clean` empties `build/` and the cache
lives outside it — so the next build restores the same stale classes into the tree you
just emptied. Disassembling the freshly "rebuilt" test class shows it still referencing
the type you moved.

```bash
./gradlew --no-build-cache --rerun-tasks compileTestKotlin   # confirms it
./gradlew --no-build-cache check                             # green, and repopulates
```

After one clean run without the cache, ordinary `./gradlew check` is correct again.

**`--no-build-cache` alone is sometimes not enough**, and the second cause looks
identical. Kotlin's *incremental* compiler tracks which test classes need rebuilding
when a main class changes, and adding a defaulted parameter to a function changes a
synthetic signature — `of$default` gains an argument — that its dependency tracking
can miss. The failure is again `NoSuchMethodError`, this time naming `…$default`
with the old parameter list.

`--rerun-tasks` is what clears that one, since it forces the task to run rather than
merely refusing a cached result:

```bash
./gradlew --rerun-tasks --no-build-cache compileTestKotlin
```

The way to tell the two apart is not to guess: disassemble the freshly built test
class and read what it actually references.

```bash
javap -c -p build/classes/kotlin/test/<path>/YourTest.class | grep 'of\$default'
```

Worth knowing because the failure looks like a logic error in the refactor, and the
natural response — reverting the move — makes it go away for the wrong reason.
