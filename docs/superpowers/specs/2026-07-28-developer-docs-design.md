# Developer documentation: code organization, contributing guide, how-tos

## Problem

The repository documents its *intent* thoroughly and its *procedure* not at all.

The specification says what the plugin is for. The plan says which step is next. `Module.md` says what
each package is. `README.md` says what works today. None of them answers the question a contributor
actually arrives with: **I want to add an inspection — what do I touch, and what will reject my PR?**

The answer is currently only recoverable by reading an existing inspection and inferring the pattern.
That answer reaches into seven places. `SolrUnknownFieldTypeInspection` is sixty-one lines of Kotlin,
and landing it also meant shared helpers in `SolrInspections`, a `shortName`-matched description HTML
file, four `SolrBundle` keys, a `<localInspection>` block in `plugin.xml`, a test, and clearing two
build gates that fail on things a newcomer has no reason to anticipate — an undocumented public
property, or coverage dipping under eighty percent. Nothing writes that down.

Two secondary problems come with it.

**`CLAUDE.md` is doing a job it was not addressed for.** It carries the architecture rationale and the
conventions, and `README.md` points humans at it. It is a file addressed to an AI agent, and the
prose in it that explains *why the `model` package has no IntelliJ types* is prose a human contributor
needs and should not have to read a machine's instruction file to find.

**Status is written down three times.** `README.md` carries a twenty-five bullet feature list,
`Module.md` carries a "What exists today" list, and the plan marks steps done. `CLAUDE.md` already
names this hazard for the plan-to-CLAUDE.md direction — *"a copy here goes stale while the plan stays
correct"* — and the same reasoning applies to the copies nobody flagged.

## Goals

- A contributor with no IntelliJ Platform experience can go from clone to merged PR without reading
  source to infer a convention.
- A maintainer returning after a month can find the fiddly mechanism — fixture setup, `shortName`
  coupling, cache invalidation — without re-deriving it.
- Every fact lives in exactly one file. Others link.
- `CLAUDE.md` keeps only what an agent breaks if it has to follow a link to find it.

## Non-goals

- **Replacing `docs/modern-intellij-plugin-development.md`.** That tutorial teaches the *platform* to
  someone who has never written a plugin. The how-tos teach *this repository* to someone who already
  knows what a `CompletionContributor` is. They link to it for concepts and never re-explain them.
- **Issue and PR templates.** Separate concern, separate change.
- **A how-to for teaching the plugin a new configset file kind or schema element.** Real, but rarer
  than the three chosen, and it can be added later without disturbing anything here.
- **Documenting unbuilt surfaces.** The server and code tracks get no how-to until they have code.

## Design

### 1. File layout

Everything new lands under `docs/`. `CONTRIBUTING.md` does not go to the repository root: the
directory stays the single place documentation lives, at the cost of GitHub's automatic
"Contributing guidelines" link on new pull requests and issues. That link is worth less here than a
tidy root, because the project is pre-release and its contributor traffic is not yet arriving through
the GitHub PR form.

| File | Owns |
|---|---|
| `docs/contributing.md` | Setup and first run; where work comes from; commit and PR mechanics; CI and review expectations |
| `docs/code-organization.md` | Package-by-package structure, the cross-cutting rules, and where a given change goes |
| `docs/how-to/add-an-editor-feature.md` | One inspection traced end to end, then four capability deltas |
| `docs/how-to/extend-the-field-model.md` | Adding to `org.apache.solr.ide.model` and the parsers that fill it |
| `docs/how-to/testing-and-the-build-gates.md` | The two test conventions, and clearing Dokka and Kover before pushing |

No `docs/how-to/README.md` index. Three files do not need a table of contents; `README.md`'s
documentation table and cross-links between the how-tos are enough.

### 2. `docs/contributing.md`

Four sections.

**Setup and first run.** JDK 21 or later, and *why* — the newest supported Solr requires it, and the
catalog generator reads artifacts from every supported line. The `.run/` configurations and their
Gradle equivalents. `./gradlew runIde` and what a working sandbox looks like.

The section must warn about the first build. `generateSolrCatalog` (`build.gradle.kts:195`) resolves
and reads Solr and Lucene artifacts for every supported release line through ASM, and
`sourceSets.named("main") { resources.srcDir(generateSolrCatalog) }` at line 299 makes
`processResources` depend on it. A first or clean build therefore downloads a large dependency set
and is slow and network-bound. Undocumented, that reads as a hang.

**Where work comes from.** The specification owns intent; the plan owns step status and says which
steps block which; the plan's tracks are independent once the field model exists, so more than one
person can work at once. How to pick a step, and what the plan's own success criteria mean for
"done".

**Commit and PR mechanics.** Conventional-commit subjects, mandatory `git commit -s`, and the rule
that carries real weight here: **commit bodies are load-bearing.** For several changes in this
repository the body is the only record of why a constraint exists. The guide should show one real
example rather than assert it.

**CI and review expectations.** What `build.yml` runs; why `check` runs last, so a gate failure still
leaves the coverage report uploaded and SonarCloud published; SonarCloud's new-code gate as the thing
that catches gradual coverage erosion, given the floor deliberately sits below actual coverage;
Actions pinned to full commit SHAs with a trailing version comment, and why the comment matters for
Dependabot. Gradle dependency verification was tried and removed — the guide notes this so its
absence does not read as an oversight.

### 3. `docs/code-organization.md`

Receives the package prose relocated out of `Module.md`, reframed from *what each package is* to
*where does my change go*.

Opens with the organising principle — packages are by feature, and by feature again inside; the
specification's three surfaces are the top level; within a surface each package is one capability
rather than one layer; `org.apache.solr.ide.model` is the single exception and earns it by being what
both surfaces read and the only package with no IntelliJ types, which is what lets the
correctness-critical code be tested without a fixture.

Then the cross-cutting rules, stated as constraints a change can violate:

- Nothing in `model` imports an IntelliJ type.
- Nothing on the editor path contacts a server.
- Detection runs on every file the user opens, so its signals stay cheap, local and cached.
- Every contribution declares itself dumb-aware, and **that is a promise about data sources.** A
  future feature that reads an index must drop the declaration or guard with `DumbService`, and no
  build gate catches it if it does not.

Two Mermaid diagrams, and no others — GitHub renders Mermaid natively, Dokka does not, so they stay
out of `Module.md`:

1. **The activation decision.** `SolrProjectDetector` (does the project depend on a Solr client?) →
   `SolrConfigsetFileKind` (is this a name we recognise?) → `SolrConfigsetFileRole` (does that name
   prove a configset on its own, or only alongside one that does?) → `SolrConfigsetLocator` (which
   configset owns it?), with `SolrConfigsetSettings` shown as the bypass.
2. **Parse to model.** Configset files → `SolrSchemaParser` / `SolrConfigParser` (pure functions over
   text, JDK DOM, doctypes refused) → `SolrConfigsetReader`'s cache, keyed on the modification stamps
   of the files it read → `SolrConfigsetFacts`, with every editor feature shown asking the same
   question: `SolrConfigsetReader.modelFor(PsiFile)`.

Closes with a table mapping a change to its destination package, so landing work has an obvious home
rather than inventing one.

### 4. `docs/how-to/add-an-editor-feature.md`

**One complete walkthrough, then four deltas.** Not five parallel recipes: the shared spine is the
part most likely to drift, and five copies of it is five chances to be wrong. Not a spine plus five
equal sections either — a newcomer arriving from a link would read four irrelevant sections before
anything became concrete.

The walkthrough traces `SolrUnknownFieldTypeInspection`, chosen because it touches the most
machinery of anything in the repository:

| Step | Artefact |
|---|---|
| 1 | `SolrUnknownFieldTypeInspection.kt` — extend `LocalInspectionTool`, `buildVisitor` |
| 2 | `override fun isDumbAware(): Boolean = true` — and the KDoc justifying it as a claim about data sources |
| 3 | `SolrConfigsetReader.getInstance(project).modelFor(holder.file) ?: return EMPTY_VISITOR` — the gate that makes the feature inert outside a configset |
| 4 | `SolrInspections` — `reportOnValue` for underlining inside the quotes, `replacementFixes` for Alt-Enter, `isCheckableFieldName` and the zero-false-positive rule |
| 5 | `SolrBundle.properties` — `inspection.fieldType.displayName`, `inspection.fieldType.unknown`, `inspection.group`, `quickfix.fieldType.family` |
| 6 | `inspectionDescriptions/SolrUnknownFieldType.html` — **filename must equal `shortName`**, or the Settings panel shows a blank description with no build error |
| 7 | `plugin.xml` — the `<localInspection>` block, and why `level="WARNING"` rather than `ERROR` |
| 8 | `SolrUnknownFieldTypeInspectionTest` — `checkHighlighting`, and the link onward to the testing how-to |

Then four deltas of three or four paragraphs each — completion, reference, documentation, inlay —
each naming its extension point, its real implementation to copy from, and only what differs from the
spine. The inlay delta must note what is distinctive about its registration: it goes under
`codeInsight.declarativeInlayProvider` with a `providerId`, a settings `group` and
`isEnabledByDefault`, which is what gives it a user-facing toggle — the reference, completion and
documentation contributors have none.

### 5. `docs/how-to/extend-the-field-model.md`

The no-platform-types rule and what it buys: `model` tests are plain JUnit 4 with `@Test` and backtick
names, needing no fixture, because booting a headless IDE to exercise a pure function costs a second
of wall-clock for nothing. Adding a platform import to `model` silently costs that.

How a new fact reaches the model: parser change → `SolrConfigsetFacts` → the reader's cache key.
Traced against a real example — `SolrMatchAnalysis`, which turns an index-time analyzer chain into
what a field can actually match, is the richest one and is what both the inlay hints and the
documentation provider consume.

The cache: `SolrConfigsetReader` keys on the modification stamps of the files it read, which is what
makes the model rebuild before save. A new input file read during parsing must join that key, or the
model goes stale in a way no test catches by accident.

### 6. `docs/how-to/testing-and-the-build-gates.md`

**Two test conventions, and what you are testing decides which you get.** Anything with PSI in it
extends `BasePlatformTestCase` — JUnit 3-style despite the JUnit 4 dependency, so methods must be
named `testSomething()` and are found by that prefix, not by `@Test`. Anything importing nothing from
the platform is plain JUnit 4 with `@Test` and backtick names. A `testSomething()` name in the second
group still runs, but reads as a claim that the test needs a platform it does not.

Anything touching `SolrConfigsetSettings` extends `SolrConfigsetTestCase` instead, because
`BasePlatformTestCase` reuses one light project across methods *and* classes while the settings are a
project-level `PersistentStateComponent` — so one test disabling detection silently changes the
starting conditions of every test after it.

Fixtures: `myFixture.addFileToProject(path, content)`, where the path shapes the directory structure
the detector's heuristics read and is therefore part of the test's meaning rather than incidental.

`checkHighlighting` fails on highlights the fixture did *not* mark as well as ones it did, which is
what makes the zero-false-positive bar enforceable per test rather than only in CI.

Then the two gates, framed as what fails and what the failure looks like:

- **Dokka.** `reportUndocumented` plus `failOnWarning`, with `dokkaGenerate` a dependency of `check`.
  Any public class, function or property in `src/main/kotlin` without KDoc fails the build, naming
  the declaration. Tests are exempt. Adding public API means documenting it in the same change.
- **Kover.** An 80% line floor via `koverVerify`, also bound to `check`, with `SolrBundle` excluded.
  The floor sits below actual coverage on purpose, so landing hard-to-unit-test UI and PSI code does
  not immediately block a PR.

### 7. Edits to existing files

**`CLAUDE.md` → roughly 80 lines.** Keeps inline only the rules an agent violates if it has to follow
a link: the build commands, both gates as hard constraints, the dumb-aware promise, the two test
conventions, `SolrConfigsetTestCase` for settings tests, and sign-off. Everything else becomes a
pointer table. Its existing convention naming `README.md` the authority on what is built is rewritten
to name the plan instead.

**`docs/Module.md`.** Each `# Package` heading keeps one sentence of purpose and a link to
`code-organization.md` for the reasoning. Links must be absolute GitHub URLs, since Dokka renders
this elsewhere — and note that KDoc reads `[foo]` as a symbol link, so reference-style Markdown links
do not work here. The "What exists today" list is deleted in favour of a plan pointer.

**`README.md`.** The Documentation table gains rows for the three new documents. The Status section
loses its twenty-five bullet feature list and keeps three or four sentences: pre-release, the
configset surface largely built, the server and code surfaces unbuilt, and the plan as the authority
on step-level truth. Prose rather than a list, because a list invites per-feature maintenance, which
is the duplication being removed — but keeping something there, because blanking the front page costs
a visitor the twenty-second answer to "does this do anything yet".

## Testing strategy

Documentation, so the verification is factual accuracy rather than assertions:

- Every file path, class name, extension point, `shortName`, bundle key and Gradle task cited is
  checked against the code while writing, not from memory.
- Every internal link resolved. `Module.md`'s outbound links must be absolute, and are checked for
  the `[foo]`-is-a-symbol-link trap.
- `./gradlew build` run at the end. Not ceremony: `Module.md` feeds Dokka, and Dokka is a gate, so
  editing it can fail the build.
- Mermaid diagrams checked as rendered on GitHub rather than assumed to parse.

## Delivery

One branch, `docs/developer-docs`, off `main` at `997fcfd`, and one pull request. The moves do not
split sensibly: removing prose from `CLAUDE.md` and `Module.md` is only correct once its destination
exists, so a split PR would leave `main` briefly missing content it used to have.

Commit order within the branch, each commit self-contained:

1. `docs: write the contributing guide`
2. `docs: relocate the package prose into a code-organization guide` — includes the `Module.md` edit
3. `docs: add the how-to guides`
4. `docs: let the plan own status, and CLAUDE.md point at the human docs` — includes `README.md`

## Risks

**The how-tos age against the code they trace.** Tracing a real inspection is what makes them useful
and is also what breaks when that inspection is refactored. Mitigated by tracing structure and file
roles rather than quoting large code blocks, so a rename invalidates a line rather than a section.
Accepted: a generic skeleton would age better and help less.

**Dokka's package pages lose their prose.** A one-line purpose per package plus a link is thinner than
what is there now, and the API reference is a surface where a reader has no easy path back to
`docs/`. Accepted deliberately: the alternative is maintaining the reasoning twice.

**`CLAUDE.md` shrinking changes agent behaviour.** Rules that move behind a link may stop being
followed. Mitigated by keeping the load-bearing rules inline rather than linked — the test is whether
an agent would produce wrong code without seeing the rule, not whether the rule is architectural.
