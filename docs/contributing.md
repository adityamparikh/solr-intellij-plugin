# Contributing

How to get the plugin building, find work worth doing, and get a change merged.

For where code goes once you are writing it, see [code organization](code-organization.md). For the
step-by-step of adding a feature, see the [how-to guides](how-to/).

## Setup

**JDK 21 or later.** The build pins `jvmToolchain(21)`, and the floor is not arbitrary: the newest
supported Solr requires Java 21, and the catalog generator reads Solr artifacts from every supported
line. A JDK 21 toolchain also loads the previous major's lower-baseline classes, so one toolchain
spans every line the plugin supports.

Nothing else needs installing. Gradle resolves the IntelliJ Platform itself.

```bash
git clone https://github.com/adityamparikh/solr-intellij-plugin.git
cd solr-intellij-plugin
./gradlew build
```

### Your first build is slow, and that is expected

`generateSolrCatalog` resolves `solr-core` for **every supported Solr line** and reads the class
files inside each one, along with Lucene's. It is wired in as a resource source directory:

```kotlin
sourceSets.named("main") { resources.srcDir(generateSolrCatalog) }
```

That single line is what makes `processResources` depend on the generator, and therefore what makes
a clean build regenerate the catalog. The consequence is that a first build — or any build after
`./gradlew clean` — downloads a large dependency set before it compiles anything. On a slow
connection this looks like a hang. It is not.

Why it exists: a configset names Solr classes as strings, and the plugin cannot complete or explain
`class="solr.StrField"` without knowing what exists. That list runs to roughly 170 entries per line
and changes between lines, so it is generated rather than written down.

### Commands

```bash
./gradlew build            # compile, test, and run both gates
./gradlew check            # tests + coverage floor + documentation gate
./gradlew runIde           # sandbox IDE with the plugin installed
./gradlew buildPlugin      # distributable ZIP in build/distributions/
./gradlew verifyPlugin     # IntelliJ Plugin Verifier + plugin structure checks
./gradlew dokkaGenerate    # render API docs to build/dokka/html
./gradlew koverXmlReport   # coverage report at build/reports/kover/report.xml

./gradlew test --tests "*.SolrConfigsetDetectorTest"
./gradlew test --tests "*.SolrConfigsetDetectorTest.testSchemaUnderConfDirIsRecognized"
```

Equivalent IDE run configurations live in `.run/`: **Run Plugin**, **Run Tests**, **Run
Verifications**. They appear in the run-configuration dropdown after the Gradle import finishes.

### First run

`./gradlew runIde` launches a sandbox IDE with the plugin installed, and opens the `demo/` project
rather than whatever the sandbox had open last. That is deliberate — `demo/` is a real Solr
application: it declares `org.apache.solr:solr-solrj` in its build, which passes the outer activation
gate, and it carries a real configset under `demo/solr/conf/`. So the plugin activates immediately
and you can see the features working without building a fixture first.

Open `demo/solr/conf/managed-schema.xml` and you should see inline hints beside each field whose
type the schema declares, saying what it can match and how it is stored. Two fields are quiet on
purpose: `notes` carries only the storage half, because its analyser names a factory the plugin
does not recognise, and `legacy` carries no hint at all, because its type is undeclared. If you
see nothing anywhere, the plugin has not activated; see [when nothing
activates](#when-nothing-activates).

To point the sandbox somewhere else:

```bash
./gradlew runIde -PrunIdeProject=/path/to/project   # a real-world configset, or a bug report
./gradlew runIde -PrunIdeProject=                   # open nothing; the sandbox restores its own state
```

### When nothing activates

The plugin is gated twice, and silence is the designed behaviour outside a Solr project. In order:

1. **Does the project depend on a Solr client?** `SolrProjectDetector` matches `solr-solrj` or a
   wrapper carrying it, by artifact id, never version. A repository of bare configsets with no build
   file fails this gate.
2. **Is the file's name one the plugin recognises?** See the role tiers in
   [code organization](code-organization.md).

**There is no user-facing escape hatch for either gate yet.** `SolrConfigsetSettings` carries the
manual override the spec promises — a marked directory bypasses the outer dependency gate, which is
the only way a configset repository with no build file can activate at all — but nothing in
`plugin.xml` reaches it: there is no registered `<action>`, no settings page, no `Configurable`
anywhere in the plugin. *Mark Directory as Solr Configset Root* is a bundle string
(`SolrBundle.properties`) with no action behind it, left over from before the gesture that would use
it was built. [Step 22](../specs/plans/0002-solr-intellij-plugin-plan.md#step-22-settings-and-the-detection-escape-hatch)
is where this is tracked, and every one of its success criteria is still unticked — this is not an
oversight in this guide, it is genuinely unbuilt.

So today, if detection is silent and you need to prove the rest of your change works anyway, the only
way in is code rather than UI: call `SolrConfigsetSettings.getInstance(project).addManualRoot(dir)`
yourself — from a scratch file, a one-off test, or a debugger — or, if you would rather commit a
marked root for a fixture that has no build file, hand-edit that project's `solr.xml` component to
match the shape `SolrConfigsetSettings.State` persists (see that class's KDoc for the exact fields).
Neither is a substitute for the settings page; both are what a contributor can actually reach before
it exists.

## Where work comes from

Three documents, and it matters which one you are reading:

| Document | Owns |
|---|---|
| [`specs/0002-solr-intellij-plugin.md`](../specs/0002-solr-intellij-plugin.md) | **Intent.** What the plugin is for and what each feature should do. Read it before designing anything. |
| [`specs/plans/0002-solr-intellij-plugin-plan.md`](../specs/plans/0002-solr-intellij-plugin-plan.md) | **Status and order.** Which steps are done, which block which, and what "done" means for each. |
| [`docs/demo/README.md`](demo/README.md) | **Acceptance criteria in user terms.** A runbook for demonstrating the plugin, which doubles as the sharpest definition of done available. |
| [`README.md`](../README.md) | The short version, for someone deciding whether to care. |

**The plan owns status.** Do not infer what is built from the API reference, from this guide, or from
the specification — the specification describes intent, and much of it is unbuilt. If you want to
know whether something works today, the plan is the only file that answers.

The plan groups work into **tracks** — foundation, editor, server, code, and cross-cutting — which
are independent of each other once the field model exists. That is what makes parallel work possible
without re-deriving the dependency graph each time. It also records why steps are split the way they
are, and those reasons are sometimes the point: the fake HTTP layer belongs to the server step rather
than a follow-up, specifically so that no test ever needs a running Solr.

Pick a step from a track whose prerequisites are done. Its success criteria are the acceptance test —
they are written in user terms, and a step is not finished until they are true.

The [demo runbook](demo/README.md) is worth reading alongside them. Its steps are acceptance criteria
phrased as things a user does: *"Ctrl-click `name` inside the `qf` line and land in the schema"* is a
sharper definition of done than "request-handler parameters resolve to schema fields", because it can
only pass when the whole path works — detection, model, reference resolution, and the platform
registration that unit tests routinely miss. A green test suite is not the same as a working feature,
and this is where that gap shows up.

## Commits and pull requests

**Conventional-commit subjects.** `feat:`, `fix:`, `docs:`, `build:`, `ci:`.

**Sign-off is mandatory.** Use `git commit -s`, which appends the `Signed-off-by` trailer. Every
commit in this repository's history carries one; a pull request whose commits do not will be asked to
rebase, which is more annoying than remembering the flag.

**Commit bodies carry real weight here.** For several changes in this repository, the body is the
only record of *why* a constraint exists — and those constraints are re-derived expensively when the
record is missing. Write the body for the person who, a year from now, will look at your constraint
and wonder whether it can be deleted.

The bar is not length, it is whether a future reader can tell what would break. From `997fcfd`:

> The catalog section said "analysis factories" and meant it. Field type classes — `StrField`,
> `TextField`, `IntPointField` — are a different population, reached a different way, and were never
> in scope. That left the `class` attribute of every `<fieldType>` with no answer while appearing to
> be covered by a step already on the plan, which is the worst way for a gap to hide.

That paragraph is why the scope changed. Without it the change reads as an arbitrary widening.

A useful test before pushing: if your body only restates the subject in more words, it is not doing
the job. If it names something that would otherwise have to be rediscovered, it is.

## CI and review

Three jobs in [`.github/workflows/build.yml`](../.github/workflows/build.yml): **Build** produces the
plugin ZIP, **Test** runs the suite and both gates, **Verify plugin** runs the IntelliJ Plugin
Verifier. A draft release is prepared on pushes to `main`, never on pull requests.

### The step order in the Test job is load-bearing

It runs the report-producing tasks first and the gates last:

```
./gradlew test koverXmlReport   →   upload coverage   →   ./gradlew sonar   →   ./gradlew check
```

This is deliberate and easy to break. `koverVerify` and `dokkaGenerate` are both bound to `check`, so
invoking `check` early would make the coverage report's existence depend on Gradle's scheduling after
a task failure. Generating the report first makes the artifact upload and the SonarCloud scan
independent of whether the gate passes — which matters precisely when the gate fails, because that is
when you most want to read the report.

**If you add a step, keep it on the correct side of `check`.** An artifact upload placed before
`check` will find nothing, because `check` is what generates it. The rendered KDoc upload sits after
the gate for exactly this reason, with `always()` so partial output survives a failure.

### What the gates check

Both fire on `./gradlew build` locally, so CI should not be the first place you meet them.

- **Documentation.** Dokka with `reportUndocumented` and `failOnWarning`. Any public class, function
  or property in `src/main/kotlin` without KDoc fails the build, naming the declaration. Tests are
  exempt.
- **Coverage.** Kover enforces an 80% line floor. The floor sits below actual coverage on purpose, so
  landing hard-to-unit-test UI and PSI code does not immediately block a PR.

[Testing and the build gates](how-to/testing-and-the-build-gates.md) covers clearing both.

### SonarCloud

Analysis runs from CI rather than SonarCloud's Automatic Analysis, because Automatic Analysis cannot
ingest a coverage report and the two modes are mutually exclusive. The **new-code gate** is what
catches gradual coverage erosion, given the Kover floor is a backstop against a sharp drop rather
than a target.

The checkout uses `fetch-depth: 0` because SonarCloud needs full history to attribute new code
correctly — a shallow clone makes every line look new.

### What a reviewer will push back on

- A public declaration with no KDoc, or KDoc that restates the signature.
- A commit body that does not say why.
- An inspection that can fire on a correct file. Solr configuration is full of syntax that looks like
  a field name without being one; see the ground rules in `SolrInspections`.
- Anything on the editor path that contacts a server, or reads an index while declaring itself
  dumb-aware.
- Mirroring plan status into another file.

## Things that will bite you

**The configuration cache is on.** `org.gradle.configuration-cache = true` in `gradle.properties`. A
task action that calls a function declared in `build.gradle.kts`, or reads one of its properties,
captures the Gradle `Project` object — which the cache cannot serialize. The task works until the
cache has to be rebuilt, and then fails. Both `runIde` and `generateSolrCatalog` carry scars from
this; each captures what it needs as plain values first, and declares local functions inside the
action rather than at script level. Copy that shape.

**Supported Solr lines are declared in one place.** `supportedSolrLines` in `build.gradle.kts`. Adding
a line or dropping an end-of-life one is one edit there. The policy behind it — whatever Apache Solr
has not declared EOL — lives in the specification's "Version support" section. A Solr EOL
announcement is a maintenance trigger, not a background event.

**GitHub Actions are pinned to full commit SHAs** with a trailing version comment. Keep it that way:
tags are mutable and CI has `SONAR_TOKEN` in scope, and the trailing comment is what lets Dependabot
still propose upgrades.

**Gradle dependency verification is absent on purpose.** It was tried and removed — the manual
regeneration on every dependency bump was not worth it for a pre-release plugin. Commits `19a0e0f`
and `e675f26` are the only record of what that regeneration cost. Both touch a
`gradle/verification-metadata.xml` that no longer exists; read them as the history behind the
removal, not as live build guidance.

**A corrupted test sandbox looks exactly like a broken plugin.** If every fixture test fails at once
with the same `FileDeletedException` while the plain JUnit tests stay green, the sandbox VFS is bad,
not your code. [Testing and the build gates](how-to/testing-and-the-build-gates.md) has the
diagnosis and the fix — including why `./gradlew clean` does not help.
