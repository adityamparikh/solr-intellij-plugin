# Platform mechanisms this plugin relies on

Two IntelliJ Platform mechanisms shape code all over this plugin, and neither is
guessable from reading that code. Both were originally worked around rather than
used — one silently, for months — so this records what they are, what we decided,
and why.

This is not a substitute for the [SDK documentation]. It is the part that is
specific to us: the decision, the reasoning, and the evidence.

[SDK documentation]: https://plugins.jetbrains.com/docs/intellij/

---

## Dumb mode, and `DumbAware`

### What it is

When you open a project, IntelliJ builds **indexes** — a searchable database of
every class, symbol and word in it. That takes seconds on a small project and
minutes on a large one. Until it finishes, the IDE is in **dumb mode**: it runs,
you can read and type, but anything backed by an index cannot answer, because its
data source is half-built.

The platform's rule is deliberately conservative:

> A contribution that has not declared itself dumb-aware is **skipped entirely**
> while the project is indexing.

The reasoning is sound. Answering from a partial index produces confidently wrong
results — a Find Usages that reports three usages when there are nine is worse
than one that declines to answer. So the default is silence, and a feature that
does not need an index has to say so. `DumbAware` is that declaration: *I read no
index; run me anyway.*

### Why it mattered here

**Nothing in this plugin reads an index.** The field model is parsed from the text
of `managed-schema.xml` and `solrconfig.xml`. The activation gate reads library
names off the project model rather than PSI, which is why the plugin needs no
dependency on `com.intellij.modules.java`. There is no stub index, no file-based
index, no symbol lookup anywhere in it.

We had simply never said so. The consequence was that completion, all three
inspections, quick documentation and the inline match hints did nothing at all
while a project indexed — and then started working, with nothing logged and no
visible cause.

That is the worst possible failure shape for an editor plugin. It is not a crash
and it is not a wrong answer; it is *absence*, during the exact window when
someone opening a Solr project for the first time is most likely to be opening
configset files. The reasonable conclusion for that user is that the plugin is
broken or flaky, and the reasonable next action is to uninstall it.

### The decision

**Every contribution that can declare dumb-awareness does.** There is no case
where waiting for the index buys correctness, because no answer this plugin gives
depends on one.

Opting in is not uniform, which is the trap. Some platform base classes expose a
method to override; others take a marker interface; one has neither. These were
verified by compilation against the platform on the build classpath, not assumed:

| Contribution | How it opts in |
|---|---|
| `LocalInspectionTool` | `override fun isDumbAware()` |
| `CompletionContributor` | `override fun isDumbAware()` |
| `DocumentationProvider` | `DumbAware` marker interface |
| Declarative inlay provider | `DumbAware` marker interface |
| `PsiReferenceContributor` | **neither** — reference contributors are not filtered this way |

The marker-interface cases are the dangerous ones. Adding `DumbAware` to a class
the platform does not check for it **compiles cleanly and does nothing**, so a
wrong guess here produces code that looks correct forever. Overriding a method
that does not exist at least fails the build, which is how the
`PsiReferenceContributor` row above was established.

### The evidence

`SolrSchemaVocabularyCompletionTest.testCompletionAnswersWhileTheProjectIsIndexing`
asserts completion inside `DumbModeTestUtils.runInDumbModeSynchronously`.

It was checked to **fail** with the override removed, not merely to pass with it
present. That check is the point of the test: a dumb-mode test that would pass
either way asserts nothing, and this repository has already produced one
vacuous test that had to be rewritten after being taken at face value.

### What this does not license

Dumb-awareness is a claim about *this* plugin's current implementation, not a
default to carry forward. Any future feature that reads an index — a Find Usages
provider backed by a stub index, a SolrJ recognizer resolving Java symbols — must
either drop the declaration or guard the index access with `DumbService`.

The rule worth carrying: **`isDumbAware` is a promise about data sources.** Adding
an index to a feature that declares it is a silent correctness bug, and nothing in
the build will catch it.
