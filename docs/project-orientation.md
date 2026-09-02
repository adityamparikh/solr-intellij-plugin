# Project orientation

> **Who this is for.** A reader deciding where to look next in this project — new to Solr, to the
> IntelliJ Platform, or both — who wants the shape of the work before the plan's step-by-step detail.
> **Read first:** [Glossary](glossary.md) if Solr or IntelliJ Platform terms are new · [README](../README.md)

A reader's map of where this project stands — for someone who wants to know "what's the situation
here" before diving into the plan's step-by-step detail, not a second source of truth for it.

**This document owns no status.** [CLAUDE.md](../CLAUDE.md) is explicit: "the plan owns what is
built," and mirroring its step status into another file is exactly the mistake that lets a copy go
stale while the plan stays correct — the session that produced this document found a live example of
that mistake, seven paragraphs down. So nothing here says which steps are done and which are not; every
claim of that shape links to [the implementation plan](../specs/plans/0002-solr-intellij-plugin-plan.md)
instead of restating it. What this document adds is orientation: which of the three surfaces the
specification promises exist in any form, how they relate, and where to look next depending on what
you're trying to do.

> **In Java terms.** This is the same discipline as normalizing a database schema: pick one column —
> here, [the plan](../specs/plans/0002-solr-intellij-plugin-plan.md) — to own a fact, and every other
> place that needs it references or links to that column instead of storing its own copy. A derived
> column can drift from the one it was computed from; a second status paragraph can drift from the
> plan the same way, and by the same mechanism — someone updates one copy and not the other. [The
> section below](#two-corrections-and-the-second-one-is-this-documents-own) is that failure caught twice,
> not prevented forever.

## The shape of the project

The [specification](../specs/0002-solr-intellij-plugin.md) describes three surfaces unified by one
shared model of what a Solr [field](glossary.md#field) is and what it can do:

- **Configuration** — editor intelligence over [configset](glossary.md#configset) XML: completion,
  navigation, [Find Usages](glossary.md#find-usages), [rename](glossary.md#rename-refactoring),
  [inspections](glossary.md#inspection), quick documentation, [inlay hints](glossary.md#inlay-hint),
  and a couple of [intentions](glossary.md#intention) that generate new configuration rather than only
  explaining or flagging what exists. Needs no running Solr.
- **Server** — a live connection: browsing collections, a query console, indexing test documents,
  uploading and reloading configsets, and the drift view that shows where a repository and a deployed
  server disagree. Needs a running Solr.
- **Code** — Java/Kotlin support: checking and completing field names used from
  [SolrJ](glossary.md#solrj), query-syntax injection inside string literals, running a query from a
  gutter icon. Needs neither, works better with a live connection for the query-console bridge.

The plan groups work into tracks that mirror this split, plus a Foundation track underneath all three
and a cross-cutting track (CI, documentation) that runs continuously. Read
[the plan's "Build order"](../specs/plans/0002-solr-intellij-plugin-plan.md#build-order) for the
dependency graph between them — the short version is that the three surfaces are independent of each
other once the field model exists, which is why one can be far ahead of the other two without either
being blocked.

## Where effort has actually gone

Of the three surfaces, the **Configuration** one is where most of the work has landed, and not as a
partial slice of that surface either. Its scope grew as it went: steps were added to it after the
plan was written, as sandbox use and design work turned up gaps the original scope had not
anticipated. Which those are, and which of them are closed, is
[the plan's Editor track index](../specs/plans/0002-solr-intellij-plugin-plan.md#editor-track).
[The user guide](user-guide.md) is the capability-by-capability account of what that adds up to for
someone using the plugin, and [the manual test suite](manual-test-suite.md) is the evidence trail —
which gestures have actually been pressed against a running sandbox, and when, as distinct from what
merely has a passing automated test.

The **Server** and **Code** surfaces both exist in code. How far each has got is a per-step question
this document deliberately does not answer, and the paragraph that used to answer it is the second
half of [the corrections below](#two-corrections-and-the-second-one-is-this-documents-own).
[The plan's track index](../specs/plans/0002-solr-intellij-plugin-plan.md#build-order) carries the
position of every step on all three tracks, and
[its Server and Code track sections](../specs/plans/0002-solr-intellij-plugin-plan.md#server-track)
say what each step involves, for anyone picking one up. What is durable enough to state here is only
this: neither surface is blocked by the other or by the Configuration track finishing, so the order
in which they have been built reflects where attention went rather than any dependency between
them.

**"Done" in the Configuration track is a narrower claim than "finished."** The track's own text is
direct about this: what it does *not* close is the manual verification pass. Several checks in the
suite are written — a gesture with an expected outcome — and have never been pressed against a live
sandbox, which is a different thing from being wrong. One inspection's presentation (an unused field
type, which reports something true rather than something wrong) is recorded in the plan as an open
design question rather than a settled one. Reading "every Configuration step is done" as "there is
nothing left to verify" is the mistake to avoid here.

## Two corrections, and the second one is this document's own

Before this document was written, [the plan's "Current state" section](../specs/plans/0002-solr-intellij-plugin-plan.md#overview)
carried a stale claim: that `solrconfig.xml` "still lacks its own structure," because element
completion inside it was still gated on the schema. That was true when it was written and had not
been true for some time — [step 25](../specs/plans/0002-solr-intellij-plugin-plan.md#step-25-solrconfigxml-as-a-first-class-surface-done)
shipped structure completion, and the Build Order section already said so. It was verified directly
against the running sandbox during this session — typing `<` on a blank line inside `<config>` offers
Solr's own top-level vocabulary, not an echo of whatever sibling tags happen to be nearby — and the
plan's opening paragraph has been corrected to match, with the sandbox capture as evidence
([screenshot catalog entry 10](screenshots.md#10-solrconfigxmls-own-structure--10-completion-solrconfig-structurepng)).

The reason to record the correction here rather than let it disappear into a diff: it is exactly the
failure mode this document exists to avoid producing. A second file describing status is a second
place status can go stale, and the plan's opening paragraph had, for a claim that mattered — someone
reading only that paragraph would have believed a shipped capability was still missing. The fix was
to correct the one file that owns status, not to add a second correct answer alongside a wrong one.

**The second correction is this document's own, and it is the same failure with the roles reversed.**
The paragraph above about the Server and Code surfaces used to enumerate what did not exist: no HTTP
client, no tool window, no query console, no recognizer. Every clause was true the day it was
written. By the time anyone checked, the Server track had shipped all five of its steps and the Code
track had started — and this file had spent that whole stretch telling readers that nothing here
talks to a running Solr. So the document that opens by declaring it owns no status had gone stale on
status, in the same section that reports the plan doing it.

That is the strongest argument available for the rule stated at the top, and it sharpens it. The
discipline is not "keep the copy updated," because a copy is only ever written by someone who
believes they will keep it updated. It is "do not write the copy at all." The replacement above links to the plan's track
index rather than restating any part of it, and asserts only the one thing that does not move: that
the three surfaces are independent of each other. Note what survived the correction untouched — the
[shape of the project](#the-shape-of-the-project) section describes all three surfaces in the present
tense and needed no edit, because describing what a surface *is for* is orientation, while counting
what it has *shipped* is status wearing orientation's clothes.

By that test one sentence in [where effort has actually gone](#where-effort-has-actually-gone) was
still status — a tally of how many steps the Configuration track had gained since the plan was
written. It was accurate on the day it was checked, which is exactly what the two corrections above
were as well. It went in the same pass, while it was still true, and the sentence now points at the
track index instead of counting for it.

## Where to look next

| If you want to… | Start here |
|---|---|
| Use the plugin as a Solr developer | [User guide](user-guide.md) |
| Know exactly what is built and what isn't, step by step | [The implementation plan](../specs/plans/0002-solr-intellij-plugin-plan.md) |
| Know what the plugin is *for*, including what's still ahead | [The specification](../specs/0002-solr-intellij-plugin.md) |
| Reproduce a claim by hand, or see what's been verified and when | [Manual test suite](manual-test-suite.md) |
| See what a capability looks like without building it | [Screenshot catalog](screenshots.md) |
| Set up the build and find work worth doing | [Contributing](contributing.md) |
| Understand where a change belongs in the codebase | [Code organization](code-organization.md) |
| Learn the platform this plugin is built on, using it as the worked example | [Plugin development tutorial](modern-intellij-plugin-development.md) |
