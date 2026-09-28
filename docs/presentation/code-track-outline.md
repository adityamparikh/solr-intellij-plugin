# Act 5 — the Code track: a speaker's outline

> **Who this is for.** Someone building the code-track slides in PowerPoint or Keynote. This is
> content and structure, not a deck — `solr-intellij-plugin.pptx` is a designed artifact with
> embedded screenshots, and new slides need building by hand in the same template.
>
> **What is already in the deck.** All of it, as Act 5 — slides 56 to 62: a divider, five deep dives
> (C2, C2a, C4, C5 and C6, each with its screenshot), and a recap carrying C8. The three sections with
> no screenshot of their own are folded into those five rather than dropped: C1's failure is C2's
> *without it*, C3's single implementation is C2's *mechanism*, and C7's sanctioned dependency is C6's.
> The closing slide says three surfaces. C4 embeds `28-code-navigate-to-schema.png` itself and crops
> it in the slide, from the tabs down to the lines that matter, because the full split is illegible at
> slide size — the file in the deck stays the catalog's, so a reshoot replaces it in one place.
>
> **Screenshots.** All five exist — `26-code-field-warning.png`, `27-code-query-colour.png`,
> `28-code-navigate-to-schema.png` and `29-code-run-from-gutter.png`, entries 26 to 29 of
> [the screenshot catalog](../screenshots.md), and `32-code-completion-popup.png`. 27 and 32 were shot
> on 2026-09-28 on the final 0.2.0 build, in the default light theme; the rest against a real Solr
> rather than a fixture, outside a verification pass. The catalog says what each must show, so
> re-shoot from there rather than re-framing by eye.
>
> **Status.** Release 0.2.0 ships this track complete for plain Java and Kotlin with SolrJ: field
> checks, completion, navigation, query colouring and running a query from the gutter. Framework
> support is the next release — Spring Boot first, in IntelliJ IDEA Ultimate only, then Quarkus,
> Micronaut, MicroProfile and Apache Camel.

The Editor act works because each slide makes **one claim** and shows **one screenshot** proving it,
and the Server act keeps that shape while carrying more in words because its claims are mostly about
what the plugin declines to do. This act is different again: its claims are about a boundary. Every
slide here says something about the gap between a name written in Java and a name declared in XML,
and the deck's job is to make an audience feel that gap before it shows the plugin closing it.

---

## C1 · The failure this act is about

**Claim.** `q.addFilterQuery("categry:books")` compiles, deploys, and returns nothing. Solr answers a
query against a field that does not exist with **zero results, not an error** — so the typo arrives
as an empty page in production, and nothing between the two has any reason to look at it.

**Why it lands.** Everyone in the room has shipped a string like this. Say the field name is a
string and let it sit for a beat before showing anything.

**Source.** `demo/src/main/java/com/example/demo/ProductSearch.java`, which carries the defect
deliberately and says so in its own Javadoc.

---

## C2 · What the plugin says about it

**Claim.** The name is underlined, and the message names the field rather than the line.

**What to show.** `26-code-field-warning.png`. The mark covers `categry` and stops at the colon, not
the whole string — that distinction is the slide. The shipped crop carries `price` in the same frame,
which is a second defect of a different kind: mention it only if the room is quick, because two
claims on one slide is how this act loses its shape.

**What it costs.** The check runs against every configset in the project, and reports nothing where
there is none — a service talking to a Solr whose schema lives in another repository is an ordinary
deployment, and a check that cannot see must not accuse.

**Source.**
[the catalog entry](../inspection-catalog.md#code-names-a-field-no-configset-declares--solrunknowncodefield).

---

## C2a · The half that prevents the mistake

**Claim.** Field names are offered while they are typed inside a SolrJ string — and only the names
the call can use.

**What to show.** `32-code-completion-popup.png`: `de` typed inside `setQuery`, the popup open without
<kbd>Ctrl-Space</kbd>, offering `description` from the `solr` configset and nothing else.

**Why it did not open before.** Typing a letter always schedules the popup; inside a string the Java
and Kotlin plugins cancel it, because a string is usually prose. That is right for a log message and
wrong for `addFilterQuery("cat`. The plugin answers "do not skip" exactly where a field name goes and
stays out of every other string — including the value half of a clause, where after `category:`
there is nothing to offer.

**What it leaves out.** Dynamic patterns, since `*_t` inserted into a query is a wildcard field Solr
rejects; and of the declared fields, those that cannot do what the call asks — a sort is offered
sortable fields, a facet facetable ones, a query searchable ones. What is left out is exactly what C2's
check would underline once written. In a field position it also stops the contributors after it,
which would otherwise fill the popup with the words and file paths the platform offers in any string.

**Source.** `SolrCodeFieldCompletionContributor`, `SolrCodeFieldCompletionConfidence`.

---

## C3 · One implementation, two languages

**Claim.** Java and Kotlin are read by one recognizer, because it is written against UAST rather
than either language's own syntax tree.

**Where it stopped being free.** A Kotlin property's annotation is not in the UAST tree at all — it
attaches to the property, which has no Java counterpart, so neither the visitor nor the light class
offers it. Asking the platform which PSI classes convert to an annotation is what fixed it; guessing
at the `@` that opens one silently missed Kotlin's `@[Foo Bar]` list form.

**Why it belongs in the deck.** It is the honest version of "write it once and it works everywhere":
mostly true, and the exception cost a day.

**Source.** `SolrJRecognizer`; the plan's Step 16 note on why Groovy is excluded.

---

## C4 · The boundary nothing else crosses

**Claim.** <kbd>Ctrl-click</kbd> a field name in Java and land on the `<field>` in XML that declares
it.

**What to show.** `28-code-navigate-to-schema.png` — a two-pane split, both ends of the boundary in
one image. This is the act's strongest single slide; give it the room, and resist cropping to the two
lines that matter — the point is that they are in *different files*, which only the full split says.

**What makes it true.** The reference is silent under exactly the conditions the check is silent
under, because both read the same recognizer. Two answers about one name would be worse than one.

---

## C5 · A query is a string, until it is not

**Claim.** The fields and the operators in a query are told apart, so the structure is visible
without reading it character by character.

**What to show.** `27-code-query-colour.png`, default light theme — the two fields in one colour,
`AND` in another, and the terms left in the editor's ordinary string colour.

**What to admit, and it is the point of the slide.** This is **colour, not a parser**. There is no
grammar, no injected language and no folding. The scope was chosen: a parser is larger than this, and
larger than the payoff for a string that is usually one clause long. Say so plainly — an audience
that discovers it later stops believing the rest of the deck.

**The detail worth one sentence.** A lowercase `and` is not coloured, because Solr reads it as a term
rather than an operator. Colouring it would tell a reader their query combines two clauses when it
searches for three.

---

## C6 · Running it from where it is written

**Claim.** A gutter icon beside the query runs it against the selected connection, and the answer
appears in the file.

**What to show.** `29-code-run-from-gutter.png` — the collection chooser open, not the result. The
chooser is what shows the interesting problem.

**A wrinkle to know before the slide goes up.** The chooser opens at the editor's top-right corner
rather than beside the icon, so the image has the gesture in one corner and its answer in the other.
Nobody in the room will ask, but it is why the crop is wide, and it is a fair thing to fix before
this deck is given.

**The interesting problem.** A query in code **names no collection**. `setQuery("category:books")`
says what to match and nothing about where; the collection is chosen when the client is built, often
in another file, often from configuration. So the plugin asks, from the list the server actually
holds.

**What it declines.** Only the main query carries the icon. Running a filter query alone answers a
different question from the one the code asks — a filter narrows and scores nothing, so its matches
are not what the program would see.

---

## C7 · The one dependency, and why it is allowed

**Claim.** Nothing on the editor path may reach a running server. The gutter action reaches one, and
that is sanctioned rather than overlooked.

**Why it is safe.** It runs when a user presses something. The rule protects the editor path — the
checks, the completion, the colour, none of which ever contact a server — and a test enforces it by
allowlist, so a package that reaches for a server fails the build until someone adds it in a diff a
reviewer sees.

**Source.** `SolrServerBoundaryContractTest`; the plan names this as the Code track's one dependency
on the Server track.

---

## C8 · What the track taught

**Claim.** Every defect this track shipped and then fixed had one shape: **a rule written down
twice**, agreeing by luck until a case arrived that the copies had each decided separately.

**The examples, if there is time for one.** A test asserting that a request template carried the
request text, when the platform reads that field as a key — the test and the code agreed with each
other, and the only party that disagreed was the platform, which nothing in the suite had ever asked.
And a field name scanner where `-` was both a separator and a legal name character, so a hyphenated
field was reported undeclared; Solr settled it in one query.

**The close.** Three surfaces, all shipping in 0.2.0: the files you edit, the server you talk to, and
the code that names fields. One model underneath, and one question asked five ways — the check,
completion, navigation, colour and the gutter run — *does this field exist?* What the code track
cannot yet see is a client a framework builds for you, from configuration rather than a constructor
call; that is the next release.
