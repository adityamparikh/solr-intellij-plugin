# Demo runbook and acceptance harness

A complete runbook for a 45-minute talk on the plugin — what to build beforehand, what to
say, what to click, and what to do when it breaks. Working title: *"Your search index has
no compiler."*

**It has a second use, and probably the more valuable one.** The demo steps are
acceptance criteria written in the user's terms. *"Ctrl-click `name` inside the `qf` line
and land in the schema"* is a sharper definition of done than *"request-handler
parameters resolve to schema fields"*, because it can only pass when the whole path works
— detection, model, reference resolution, and the platform registration that unit tests
routinely miss.

Three consequences worth knowing before you read further:

- **The fixtures in Step 2 are also the test fixtures.** The demo project doubles as the
  inspection fixtures and the code fixtures for the SolrJ recogniser. It is not quite all
  of them — the golden-file gate runs against the two configsets Solr ships, which come
  from the distribution rather than from here. Build once; demo the thing CI checks.
- **A green test suite is not this.** A reference contributor can pass its unit tests and
  still never fire in the editor. The demo step catches that.

Steps are numbered straight through. Nothing here refers to anything you have to look up
somewhere else.

---

# Part one — before the day

## Step 1. Decide which demos you can actually give

Every demo below needs code that does not exist yet. The implementation plan is the
authority on what is built and in what order — check its step status before you promise
anyone a date.

What this section decides is different: given what happens to be ready, which demos add up
to a talk. Cut from the bottom.

**Essential — the talk does not exist without this.** Navigation between configuration
files, inspections, and the match-capability hints. Runs entirely offline, so nothing on
stage can break it.

**Second — it gives the talk an ending.** Field names checked inside Java, and the gutter
action that runs a query. This closes the story the talk opens with. The gutter action
needs the query console, so it arrives with the server work even though the rest of this
group does not.

**Third — the most impressive and the most fragile.** The server connection, collections
browser, query console and the repository-versus-server comparison.

**Last — nice, not memorable.** Completion and quick documentation, which need the
generated factory catalogue.

If only the first group is ready, you still have a good forty-five minutes. If only the
first and second are ready, you have a better talk than first and third, because the
ending matters more than the spectacle.

## Step 2. Build the demo project

**This now exists and is committed at [`demo/`](../../demo/).** `./gradlew runIde` opens it by
default, so there is nothing to type — skip to step 3 unless you want to know what is in it or you
are rebuilding it from scratch. Committing it is what lets you recover from a demo gone wrong with
`git checkout .` in about two seconds.

Two things differ from the listings below, which are excerpts rather than literal file contents:
the Java sources live in a `com.example.demo` package with their imports, and they are a standalone
Gradle build (`demo/build.gradle.kts`) so that Spring and SolrJ resolve. To open something other
than the demo, pass `-PrunIdeProject=/path/to/project`, or `-PrunIdeProject=` to open nothing.

`demo/solr/conf/managed-schema.xml`:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<schema name="products" version="1.6">

  <fieldType name="string" class="solr.StrField" sortMissingLast="true" docValues="true"/>

  <fieldType name="text_general" class="solr.TextField" positionIncrementGap="100">
    <analyzer type="index">
      <tokenizer class="solr.StandardTokenizerFactory"/>
      <filter class="solr.LowerCaseFilterFactory"/>
    </analyzer>
    <analyzer type="query">
      <tokenizer class="solr.StandardTokenizerFactory"/>
      <filter class="solr.LowerCaseFilterFactory"/>
    </analyzer>
  </fieldType>

  <fieldType name="text_prefix" class="solr.TextField" positionIncrementGap="100">
    <analyzer type="index">
      <tokenizer class="solr.StandardTokenizerFactory"/>
      <filter class="solr.LowerCaseFilterFactory"/>
      <filter class="solr.EdgeNGramFilterFactory" minGramSize="2" maxGramSize="15"/>
    </analyzer>
    <analyzer type="query">
      <tokenizer class="solr.StandardTokenizerFactory"/>
      <filter class="solr.LowerCaseFilterFactory"/>
    </analyzer>
  </fieldType>

  <field name="id"          type="string"       indexed="true"  stored="true" required="true"/>
  <field name="sku"         type="string"       indexed="true"  stored="true"/>
  <field name="name"        type="text_general" indexed="true"  stored="true"/>
  <field name="name_prefix" type="text_prefix"  indexed="true"  stored="false"/>
  <field name="category"    type="string"       indexed="true"  stored="true"/>
  <field name="description" type="text_general" indexed="true"  stored="true"/>
  <field name="text"        type="text_general" indexed="true"  stored="false" multiValued="true"/>

  <uniqueKey>id</uniqueKey>

  <copyField source="name"         dest="text"/>
  <copyField source="description"  dest="text"/>
  <copyField source="name"         dest="name_prefix"/>
  <copyField source="manufacturer" dest="text"/>
</schema>
```

The last line is deliberate. There is no `manufacturer` field, and that dangling
reference is your inspection demo. Leave it in the committed version.

Create `demo/solr/conf/solrconfig.xml` with at least this:

```xml
<requestHandler name="/select" class="solr.SearchHandler">
  <lst name="defaults">
    <str name="defType">edismax</str>
    <str name="df">text</str>
    <str name="qf">name^3 description category</str>
    <str name="rows">10</str>
  </lst>
</requestHandler>
```

The `qf` line is what proves cross-file navigation: those three names live in a different
file from the schema that defines them, and nothing in either file connects them.

Create `demo/src/main/java/SolrConfig.java`. This is the Spring half, and it matters:
plain SolrJ, wired by Spring, **not** Spring Data Solr — which is unmaintained upstream
and out of scope.

```java
@Configuration
public class SolrConfig {

    @Bean
    SolrClient solrClient(@Value("${app.solr.url}") String url) {
        return new Http2SolrClient.Builder(url).build();
    }
}
```

This is the shape almost every Spring Boot service using Solr actually has, and it is
what makes connection discovery a real feature rather than a parlour trick: the URL is
not a literal in the builder, it is a property reference resolved against whichever
profile is active. Finding it means following `${app.solr.url}` back into the profile
that supplies it.

Create `demo/src/main/java/ProductSearch.java`:

```java
@Service
public class ProductSearch {

    private final SolrClient solr;

    ProductSearch(SolrClient solr) { this.solr = solr; }

    public QueryResponse findBooks() throws Exception {
        SolrQuery q = new SolrQuery("*:*");
        q.addFilterQuery("categry:books");
        q.setFields("id,name,price");
        return solr.query("products", q);
    }
}
```

Two deliberate bugs. `categry` is a typo for `category`. `price` is a field that has
never existed in this schema. Both compile. Note the client arrives by injection, so
nothing in this file names a server — which is the normal case, and the reason endpoint
discovery cannot just scan for URL literals.

Create `demo/src/main/java/Product.java`:

```java
public class Product {
    @Field("id")   private String id;
    @Field("name") private String name;
    @Field("prce") private Double price;
}
```

A third deliberate typo, in an annotation rather than a string.

Create `demo/src/main/resources/application.yml`:

```yaml
spring:
  profiles:
    active: dev
---
spring:
  config:
    activate:
      on-profile: dev
app:
  solr:
    url: http://localhost:8983/solr
```

## Step 3. Stand up a local Solr, if you are doing the server demos

```bash
docker compose -f demo/compose.yaml up -d      # Solr 10 on :8983, core `products`
docker compose -f demo/compose.yaml down -v    # afterwards, including the data volume
```

Equivalent to `docker run -d -p 8983:8983 --name solr-demo solr:10` followed by
`docker exec solr-demo solr create -c products`, which also works if you prefer it.

The core is created from Solr's **default** configset, and `demo/solr/conf` is deliberately
not mounted into the container. That is what makes the drift demo possible: bind-mounting
the configset would make the server and the repository identical by construction, and the
comparison would have nothing to show. Getting your configset onto the server is itself a
demo step.

Then index a handful of documents so queries return something. Deliberately **do not**
deploy the `sku` field to the server — you will add it to the repository copy on stage
and let the comparison catch the difference.

## Step 4. Record a backup

Screen-record the offline demos and the Java demos, end to end, with narration. Keep the
file open in a tab behind your slides.

This is not pessimism. A sandbox IDE that decides to re-index during your talk cannot be
argued with, and five minutes of you fighting it loses the room permanently. Playing a
recording and talking over it costs nothing — most audiences will not even register it as
a fallback.

## Step 5. Rehearse the awkward parts

Three things reliably go wrong, and all three are fixable by practice rather than luck.

Undo after every edit you make on stage, and check the file is clean before moving on.
The rename demo in particular leaves the fixture modified, and the next demo depends on
it being pristine.

Practise saying the surprise line without over-selling it. If you oversell and someone in
the room knows Solr well, you lose them.

Time the code walkthrough separately. It is the part that always overruns.

---

# Part two — the last ten minutes before you speak

## Step 6. Start the sandbox IDE and let it finish indexing

`./gradlew runIde`, open the demo project, and wait until indexing is genuinely finished.
A cold start plus indexing in front of an audience is two or three minutes of dead air,
and it will happen at the worst moment.

## Step 7. Make it readable from the back

Editor font at presentation size. Walk to the back of the room and check. Pick a colour
scheme where the error underline is actually visible through a projector — the default
red squiggle is thin and often disappears.

## Step 8. Turn off everything that can interrupt

IDE notifications, plugin update checks, operating system notifications, calendar alerts,
and anything that shows a badge. A "Windows Update is ready" popup during the drift demo
is the only thing anyone will remember.

## Step 9. Confirm the server, if you need it

`curl http://localhost:8983/solr/admin/collections?action=LIST` should return your
collection. Do this even if you checked an hour ago. Everything must be on localhost —
assume the conference wifi does not work, because often it does not.

## Step 10. Open your files in advance

Have the schema, `solrconfig.xml`, and `ProductSearch.java` open as tabs, cursor already
placed. Hunting through a project tree on stage is dead time and makes the tooling look
harder to use than it is.

---

# Part three — the talk

## Minutes 0 to 4 — the failure

### Step 11. Open cold, with no title slide

Put this on screen and say nothing:

```java
SolrQuery q = new SolrQuery("*:*");
q.addFilterQuery("categry:books");
```

### Step 12. Ask what happens

Let the silence sit. Someone will say "an exception" or "a 400."

Then tell them: it returns **zero results and HTTP 200**. No error, no warning, nothing
in the log. It is indistinguishable from "there genuinely are no books."

### Step 13. Land why that is the worst possible outcome

A crash is a good failure — loud, immediate, traced. This is the other kind. It looks
like a correct answer, it passes review, it passes tests written against the same typo,
and it is found weeks later by someone asking why the books category looks empty.

### Step 14. Name the three places it can happen

The same field name lives in three places that have no idea about each other: the
configuration files in your repository, the client code that queries, and the running
server that has its own opinion about what exists. Nothing checks that the three agree.

### Step 15. State the talk in one sentence

"I am going to put a compiler between those three things."

## Minutes 4 to 8 — why Solr in particular

### Step 16. Show the banner and confront it

Solr's default configuration file opens with a comment saying it is managed by an API and
should not be edited by hand. Show it. Say the obvious thing: this looks like it makes
schema tooling pointless.

### Step 17. Explain why it does not

Three reasons, briefly.

The API does not cover `solrconfig.xml`, which is where request handlers and all
relevance tuning live — that file is hand-edited in every Solr deployment that exists.

Analyser chains are not designed through an API. Posting one is possible; deciding what
it should be is holistic work comparing index-time against query-time behaviour, and
that is editing.

Reading is universal. Even when every field arrived through the API, the resulting file
is what sits in version control, what appears in the pull request, and what someone opens
at 2am when a search returns nothing.

### Step 18. Say what the banner is actually warning about

Not the edit. **Drift** — your repository and the running server quietly disagreeing.
Note that you will come back to this, then move on.

### Step 19. Compare the neighbours

Elasticsearch has several maintained JetBrains plugins including an official one. Kafka
has Confluent's. Solr has none. This is a gap, not a solved problem.

## Minutes 8 to 20 — the demo

### Step 20. Show the before

Open the schema in a stock IDE with the plugin disabled. Scroll it. It is grey XML — the
IDE will happily tell you the angle brackets are balanced and nothing else.

Say: "Two thousand lines of this is a normal production schema."

### Step 21. Enable the plugin and reopen

The contrast is the demo. Do not rush past it.

### Step 22. Navigate to a field type

Ctrl-click the `type` attribute on the `name` field. Land on the `text_general`
definition.

### Step 23. Navigate along a copyField

Ctrl-click the destination of the `name` to `text` copy rule. Land on the `text` field.

### Step 24. Cross the file boundary

Open `solrconfig.xml`, Ctrl-click `name` inside the `qf` line, and land back in the
schema.

Say this out loud: those two files have no reference to each other. Solr itself only
connects them when the core loads. If that name is wrong, you find out on deploy.

### Step 25. Show the dangling reference

Scroll to the copy rule referencing `manufacturer`. It is underlined. Hover: the field
does not exist.

Say that this is currently a startup failure, or worse, a rule that silently does
nothing.

### Step 26. Break something live

Rename the `description` field to `descriptions`, or delete it. Watch the copy rule that
references it go red immediately.

Undo. Confirm the file is clean.

### Step 27. Find Usages on a field type

Put the caret on the `<fieldType name="text_general">` **declaration** and invoke Find
Usages. Every field using it appears.

Say: this is the question "what breaks if I change this analyser chain", which today is
answered with grep. Note that the caret is on the declaration — the place a reader
reaches for the gesture, and the place it has to work from.

Then put the caret on `<dynamicField name="*_t">` and invoke it again. The `pf` parameter
in `solrconfig.xml` appears, naming `body_t` — a field no `<field>` declares, whose name
the pattern supplies and never spells. Say: grep for `*_t` never finds this, and neither
does an editor that only matches text.

### Step 28. Show the hint on a string field

Put the cursor on `sku`. The hint reads: matches the whole value only, case-sensitive.

Say that this is not read from a comment. It is derived by walking the analyser chain.

### Step 29. Show the hint on a text field

Put the cursor on `name`. The hint reads: tokenised, case-insensitive.

### Step 30. Ask the room the question — this is the moment

"Who thinks a search for `wid` matches a product named `widget` on this field?"

Wait for hands. Most will go up.

It does not. `text_general` tokenises into whole words and lowercases them. The index
contains `widget`, not `wid`. A leading-substring search matches nothing.

Let that land before continuing.

### Step 31. Show the field that does work

Put the cursor on `name_prefix`. The hint reads: prefix matching supported, through an
edge n-gram filter on the index side.

Explain the difference in one sentence: that field type grinds every term into its
prefixes at index time, so `widget` is stored as `wi`, `wid`, `widg` and so on.

### Step 32. Add the caveat honestly

Someone is about to object that wildcards exist. Get there first: yes, `wid*` works on
any indexed field, and it is slow, because the server expands the term across the whole
dictionary at query time. The hint is about *efficient* matching, not possible matching.

Saying this before you are challenged is worth a lot of credibility.

### Step 33. Generate the fix

Put the cursor on `description`, which has no prefix support. Alt-Enter. Take the
quick-fix. It generates a companion field with the edge n-gram type and the copy rule
that populates it.

Say: that is the standard pattern, and it is the one everybody re-derives from a blog
post every time.

Undo.

### Step 34. Rename across files

Rename `category` to `product_category`. Show that the copy rules updated, and — the
important bit — that the `qf` line in `solrconfig.xml` updated too.

Say: that is a rename that today is a grep across two files and a prayer.

Undo, and check the file is clean.

**Stop here if the remaining code is not built.** This is a coherent ending: the
configuration files now behave like a language rather than like text.

### Step 35. Connect to a server

Open the connections list. Point out that `http://localhost:8983/solr` is already
offered, and that nobody typed it.

Show where it came from, because the chain is the interesting part. The bean in
`SolrConfig.java` does not contain a URL — it contains `${app.solr.url}`. That property
is defined in the `dev` profile, and `dev` is the active one. The plugin followed the
property reference from the SolrJ client construction back into the profile that supplies
it.

Say two things. First: this is plain SolrJ wired by Spring, which is what real services
look like — the plugin does not require, and does not support, Spring Data Solr, which is
unmaintained upstream. Second: the plugin **offers** this and never connects on its own.
An endpoint found in a configuration file is a suggestion, not an instruction.

Worth adding if the room is a Spring room: change the active profile and the offered
connection changes with it.

### Step 36. Browse what is actually there

Show collections, and the fields the server actually has. Note that this list is not the
same as the schema file's list, and that difference is the next demo.

### Step 37. Run a query

In the console, start typing a field name and let it complete from the live schema. Run
it. Results render as a table rather than as raw JSON.

### Step 38. Show why a document scored

Expand the scoring explanation as a tree. Say that this is normally an unreadable nested
blob, and that relevance debugging is most of the job.

### Step 39. The drift demo

Add the `sku` field to the schema in your repository. Do not deploy it. Open the
comparison.

Your repository has `sku`. The server does not. The plugin says so.

Say: this is the drift the banner was warning about, and it is why the plugin does not
try to stop you editing files. Editing is fine. Not knowing is the problem.

### Step 40. Resolve it

Upload the configuration set, reload the collection, and show the comparison come clean.
Note that the plugin named the server it was about to touch and asked first.

### Step 41. Return to the opening bug

Open `ProductSearch.java`. `categry` is now underlined inside a string literal.

Pause on this. The talk opened with this exact line compiling and shipping.

### Step 42. Complete the correct name

Delete the typo and let completion offer the real field names, read from the schema.

### Step 43. Show the second bug in the same method

`price` in the field list is flagged too — not a typo, a field that has never existed in
this schema.

### Step 44. Show the annotation case

Open `Product.java`. The `prce` annotation is flagged. Say: same class of bug, different
syntax, and the mapping layer is where it is hardest to spot by eye.

### Step 45. Show the query as a language

Point out that the query string is syntax-highlighted as a query — field, operator, value
— rather than being one undifferentiated grey string.

### Step 46. Run it from where it lives

Click the gutter icon beside the query. It runs in the console against the selected
connection. You never left the file.

### Step 47. Volunteer the limitation

Say plainly: this is best-effort. Finding which strings are queries means following values
through variables and constants, and it will never be complete. The plugin handles direct
cases and stays silent otherwise, because a false warning on working code is worse than a
missed one.

## Minutes 20 to 24 — the design

### Step 48. Show that all four demos asked one question

The editor asked whether a field exists — that is what navigation, the inspection and
completion all are. The query console asked which fields exist. The Java check asked
whether a field exists. The comparison asked whether two sources agree about which fields
exist.

One question, four askers. That is the spec's architecture, and you just demonstrated it
without naming it.

### Step 49. Draw the architecture

One model of the fields. Two sources feeding it: the repository, and the server. Four
views reading it: the editor, the tool windows, the code inspections, the recognisers.

The model records where every fact came from — which is why the comparison feature
required no new machinery. It is what a source-tracking model produces for free.

### Step 50. Say what this deleted

The earlier design classified every schema file to decide whether writing to it was
allowed, because with no server the only alternative it could offer was a shell command
with a fake URL on the clipboard. That was five classification cases, a cache, and a
contradiction I only found by reading it carefully.

Adding a connection deleted all of it. Files are edited, disagreement is shown, and the
API becomes a button rather than an apology.

The general point, worth stating: **the constraint that made the design complicated was
self-imposed.** Removing it was worth more than solving it.

## Minutes 24 to 40 — the code walkthrough

### Step 51. Set expectations

"If you have never written an IDE plugin, you will be able to follow this. If you have,
the third file is the interesting one."

### Step 52. Explain the inversion

You do not write a program with a main method. You register handlers at extension points
and the platform calls you, on its schedule, on someone else's keystroke.

Show the real `plugin.xml`. It is a list of "when this happens, call me."

### Step 53. Explain the tree

Every open file is a typed tree, not text. Your job is answering questions about that
tree, and occasionally adding edges to it that the platform could not have known about —
which is exactly what "this string names that field" is.

### Step 54. Name the four extension points that cover most plugins

A reference contributor, for links between things. An inspection, for problems. An
annotator, for information. A completion contributor, for suggestions.

Almost everything in the demo was one of those four.

### Step 55. Walk the reference contributor

Show the code that links a copy rule to the field it names. Twenty or so lines.

Then land the payoff: registering that one thing gave you Ctrl-click navigation, Find
Usages, **and** correct renaming. Three features from one implementation, because the
platform builds them all on the same relationship.

### Step 56. Walk the inspection

Show the dangling-reference inspection. It is: visit these elements, ask the model a
question, report a problem, optionally offer a fix.

Show its description file and mention that it is also the published catalogue entry, so
documentation cannot drift from behaviour.

### Step 57. Walk the match analysis — the point of the walkthrough

Save this for last deliberately.

Show that it is plain Kotlin. No platform types. A function from an analyser chain to a
description of what it can match. It is unit-tested in milliseconds with no IDE running.

Say the thing this is here to say: **the most interesting feature in this plugin is not
IDE code.** The IDE layer is thin and boring on purpose. All the thinking lives in pure
functions, which is why it can be tested properly and why it is the only part I am
confident is correct.

If the audience takes one design lesson home, make it this one.

### Step 58. Cover testing and the traps

The platform's test base class is JUnit 3 in disguise: methods must be named starting
with `test`, and the `@Test` annotation does nothing. Everyone hits this once.

Fixtures are files — an input and an expected output, with one assertion between them.

Project state is shared across test classes, so persistent settings leak between tests,
which is why there is a base class whose only job is resetting them.

### Step 59. Close the walkthrough with the traps list

Pick three from the tutorial in the repository — the write-lock rules, the read-action
rules, and never blocking the editor thread. These are the ones that will bite anyone who
goes home and tries it.

## Minutes 40 to 45 — honesty and the ask

### Step 60. Say what it does not do

The Java analysis is best-effort and always will be.

The match hints read the *declared* analyser chain, not what is actually in the index. A
field indexed before someone changed its chain will not behave the way the hint says. The
plugin cannot know that without reindexing.

It is a development tool. It is not a monitoring console and does not want to become one.

### Step 61. Say what is not built

Be specific about which of the demos were real and which parts of the design are still
only a document. An audience that just watched working software will believe you about
the rest — and will not forgive finding out later.

### Step 62. Make the ask

Apache-licensed, unpublished, and Solr has no maintained JetBrains plugin at all. The
specification and plan are in the repository, the steps are ordered, and the tracks are
independent enough for someone to pick one up without coordinating.

This lands far better after Step 60 and Step 61 than it would have before them.

### Step 63. Take questions

The three you will get, and short answers:

*Why not just use the Admin UI?* It cannot see your repository. Everything interesting in
this talk came from comparing two things, and the Admin UI only has one of them.

*Does this work with Elasticsearch?* No. The model would generalise; nothing else would.

*How does it handle a Solr version you have never seen?* Ignores fields it does not
recognise, reports rather than refuses an unknown version, and reads the analyser
catalogue from the version the project actually declares.

---

# Part four — when it breaks

### Step 64. If the sandbox IDE stalls

Switch to the recording and narrate it. Do not debug on stage. Say "I have this recorded,
let me talk you through it" and keep the pace up — most people will not register that
anything went wrong.

### Step 65. If a fixture ends up in a bad state

`git checkout .` in the demo project and reopen the file. This is why the fixtures are
committed. Practise this until it takes two seconds.

### Step 66. If the server is unreachable

Skip Steps 35 to 40 entirely and go straight to the Java demos at Step 41. Say the
comparison demo needs a server and you will show it afterwards to anyone interested. The
Java section is a better ending anyway.

### Step 67. If you are running long

Cut Steps 35 to 40 — the server block. Never cut Steps 41 to 47, because they close the
story you opened with, and never cut the walkthrough, because it is what people take
home.

---

# Part five — acceptance steps that are not in the talk

Completion is expected rather than surprising and indexing is hard to show to an
audience, so neither is in the forty-five minutes. Both are still user-visible, so both
still need a step saying what "done" looks like.

Run these the same way as the rest: in a sandbox IDE, against the fixtures from Step 2.

**Spring is the only framework with demo coverage, and that is a decision.** It is the
common case, and its fixture already exercises the hard part — following a property
reference from a SolrJ client construction into the active profile. Quarkus, Micronaut,
MicroProfile and Apache Camel are specified and will be built, but each would need its
own fixture project and runtime to show what the Spring demo already shows. Their
acceptance is the fixture tests in their plan steps, not a step here.

Quarkus is the one to test most carefully: its profiles are inline key prefixes in a
single file rather than separate profile files, so an implementation that works for
Spring finds nothing at all in a Quarkus project — and finds it silently.

### Step 68. Completion inside an analyser chain

Put the cursor inside an `<analyzer>` block, start a new `<filter class="solr.` and
invoke completion. Filter factories appear, from the catalogue for the version this
configset targets.

### Step 69. Attribute completion on a factory with many options

Complete attributes on `solr.WordDelimiterGraphFilterFactory`. All twelve appear.

This one is worth checking by hand rather than trusting a green test: that factory packs
its options into a single integer field internally, so any implementation that derives
attribute names from field names silently produces a short list that looks plausible.

### Step 70. Quick documentation on a factory

Ctrl-Q on `solr.ASCIIFoldingFilterFactory`. Documentation appears inline, and it is
discoverable which source answered — the connected server, the configset's declared
version, or the bundled default.

### Step 71. Author and index a test document

Write a document against the demo schema with completion offering real field names,
index it into the local collection, and find it with a query. The action names the target
server and asks before writing.

