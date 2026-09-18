# Code-quality environment for Peashoot (Kotlin 2.4.x / Gradle 9.7 / JDK 21)

Researched 2026-09-12 against primary sources only (the authors' own posts, podcast transcripts on the host's site, tool repos, release notes, official docs). Every claim carries a citation; items with no primary source are marked **unverified** and collected at the end. Secondary write-ups were used only to locate primary sources and are never cited as evidence.

Purpose: decide, before ticket 1, which quality tools a solo developer (~10 h/week, code mostly written by AI agents from tickets, human review) installs for a three-module Gradle/Kotlin project (core, proxy, app) with CI on Ubuntu and Windows, under a lazy rule: no tool that does not pay for itself.

---

## Part 1 — What DHH and Uncle Bob actually said (2025–2026)

### 1.1 DHH (David Heinemeier Hansson)

**On agents and how he works with them now**

- Blog, "Promoting AI agents", 2026-01-07 ([world.hey.com/dhh/promoting-ai-agents-3ee04945](https://world.hey.com/dhh/promoting-ai-agents-3ee04945)):
  - "Now coding agents are controlling the terminal, running tests to validate their work, searching the web for documentation, and using web services with skills we taught them in plain English."
  - "With a team of agents, they're doing their work autonomously, and I just review the final outcome, offer guidance when asked, and marvel at how this is possible at all."
  - "Yet pure vibe coding remains an aspirational dream for professional work for me, for now. Supervised collaboration, though, is here today."
  - "I'm nowhere close to the claims of having agents write 90%+ of the code, as I see some boast about online. I don't know what code they're writing to hit those rates, but that's way off what I'm able to achieve, if I hold the line on quality and cohesion."
- Blog, "Endless execution", 2026-08-09 ([world.hey.com/dhh/endless-execution-4157e065](https://world.hey.com/dhh/endless-execution-4157e065)): "The age of agents has brought us endless execution. Every idea, every hunch, every experiment is now within immediate reach."
- Blog, "Let the agents democratize open source", 2026-06-01 ([world.hey.com/dhh/let-the-agents-democratize-open-source-9fd630a9](https://world.hey.com/dhh/let-the-agents-democratize-open-source-9fd630a9)): "giving more people the power to enjoy malleable computers is undoubtedly a huge win for the founding vision of open source."

**On tests as the gate for agent code (his own X posts, full text read from the permalinks)**

- 2026-05-24 ([x.com/dhh/status/2058648789703963111](https://x.com/dhh/status/2058648789703963111)): "Agents don't need types. They're perfectly capable of pulling off incredible refactorings without. Give them a linter and a test suite, and you have all you need. Token efficiency is where it's at."
- 2026-03-06 ([x.com/dhh/status/2029957655490404476](https://x.com/dhh/status/2029957655490404476)): "Exactly as you would with a human: automated testing. Unit tests, functional tests, the rest. If you let agents produce a mountain of slop without any testing, you'll get the same buggy crap as when humans try to do that."
- 2026-01-03 ([x.com/dhh/status/2007587167640367231](https://x.com/dhh/status/2007587167640367231)): "Haven't found that to be true at all. I've been using the agents with Ruby, and as long as you're using a harness where they have access to LSP and unit tests, I'm never seeing them produce invalid code."
- 2026-01-28 ([x.com/dhh/status/2016471801803473159](https://x.com/dhh/status/2016471801803473159)): "Controller, model, job tests. Not system tests. We've virtually given up on those."
- 2026-01-28 ([x.com/dhh/status/2016533248294174928](https://x.com/dhh/status/2016533248294174928)): "Stick with vanilla minitest + fixtures! Avoid system tests, except for a tiny smoke run. No secret sauce."

  (All five are replies; the messages they answer were not captured.)

- Lex Fridman Podcast #474, published 2025-07-12 ([episode page](https://lexfridman.com/dhh-david-heinemeier-hansson), [official transcript](https://lexfridman.com/dhh-david-heinemeier-hansson-transcript/)):
  - [01:11:19] "I'm not saying I don't have bugs, of course I do, but I catch those bugs with unit testing, with integration testing."
  - [01:29:03] "I actually love collaborating with AI too. I love chiseling my code, and the way I use AI is in a separate window. I don't let it drive my code. I've tried that. I've tried the Cursors and the Windsurfs and I don't enjoy that way of writing." and "One of the reasons I don't enjoy that way of writing is, I can literally feel competence draining out of my fingers."
  - The strings "TDD", "test-driven", "Rubocop", "linter", "style guide", "Uncle Bob" do not occur in this transcript.

**On reviewing agent code and keeping architecture coherent** — Lex Fridman Podcast #501, published 2026-08-26 ([episode page](https://lexfridman.com/dhh-2), [official transcript](https://lexfridman.com/dhh-2-transcript/)). Quotes below were extracted by tool from the transcript; where "…" appears the extractor elided words — check at the timestamp before quoting onward.

- [00:51:26] "Vibe coding, if we define it here, is you tell an agent to build software for you. You do not look at the implementation."
- [00:15:43] "I've reviewed the shape of all of it…I've reviewed the individual lines of anything critical."
- [00:34:32] "I'm not reviewing every pull request anymore. I haven't been reviewing them for quite some time now. I have the agents review them for me and then they will give me a summary"
- [00:17:25] (Basecamp) "we ended up with a lot of PRs that individually perhaps could have been justified for a hot moment, but taken all together, destroyed the architecture of the system. And we actually had to clean up manually, mop it up by hand"
- [00:32:11] "Agents, if you tell them to [maintain quality], they're very diligent at following your instructions." (bracket inserted by the extractor)
- [00:58:13] "You should resist the temptation to be overly specific upfront. Be as vague as you can."
- [01:02:03] "At this moment in time, we are all token limited…there is great payoff to writing systems that agents have an easier time dealing with."
- The transcript contains no discussion of tests, TDD, Rubocop, or linting.

**On style: "omakase", no bikeshedding**

- rubocop-rails-omakase README ([github.com/rails/rubocop-rails-omakase/blob/main/README.md](https://github.com/rails/rubocop-rails-omakase/blob/main/README.md), undated):
  - "This collection of RuboCop styles is for those who haven't committed to any specific dialect already. Who would just like to have a reasonable starting point, and who will benefit from some default rules to at least start a consistent approach to Ruby styling."
  - "These specific rules aren't right or wrong, but merely represent the idiosyncratic aesthetic sensibilities of Rails' creator."
  - "These omakase styles are not intended as an invitation to bikeshed code style preferences. If you disagree with most of the rules, you should just create your own style guide from scratch."
- Rails PR #50508 "Default to creating GitHub CI files", author dhh, merged 2023-12-31 ([github.com/rails/rails/pull/50508](https://github.com/rails/rails/pull/50508), body via the GitHub API): "Now that we have rubocop and brakeman by default, it makes sense to add a default GitHub CI workflow file for new applications. This will get especially newcomers off to a good start with automated scanning, linting, and testing. That's a natural continuation for the modern age of what we've done since the start with unit tests." and "So we'll add `.github/workflows/ci.yml` and `.github/dependabot.yml` with good defaults."
- Rails 7.2 release post, 2024-08-10 ([rubyonrails.org/2024/8/10/Rails-7-2-0-has-been-released](https://rubyonrails.org/2024/8/10/Rails-7-2-0-has-been-released), posted by rafaelfranca): bullets "Add omakase RuboCop rules by default.", "Add GitHub CI workflow by default to new applications.", "Add Brakeman by default to new applications."
- Rails 8.1 release post, 2025-10-22 ([rubyonrails.org/2025/10/22/rails-8-1](https://rubyonrails.org/2025/10/22/rails-8-1), posted by rafaelfranca): "Developer machines have gotten incredibly quick with loads of cores, which make them great local runners of even relatively large test suites." and "This makes getting rid of a cloud-setup for all of CI not just feasible but desirable for many small-to-mid-sized applications, and Rails has therefore added a default CI declaration DSL, which is defined in `config/ci.rb` and run by `bin/ci`."
- Omakase as DHH's general tooling stance, X 2026-02-17 ([x.com/dhh/status/2023729203896545542](https://x.com/dhh/status/2023729203896545542)): "The "oma" in Omarchy literally stands for "Omakase" aka Chef's Choice. That's the entire premise of this distro. You'll find a gazillion alternatives where it's all a construction kit you put together yourself. This isn't that." And 2025-11-04 ([x.com/dhh/status/1985681741151961527](https://x.com/dhh/status/1985681741151961527)): "The default menu is omakase. But you're free to remove anything you don't want after the fact."

**Talks**

- Rails World 2025 Opening Keynote, 2025-09-04, Amsterdam. Official session page: [rubyonrails.org/world/2025/day-1/david-hansson](https://rubyonrails.org/world/2025/day-1/david-hansson) ("DHH will kick off the third edition of Rails World in Amsterdam with an Opening Keynote highlighting what is new in Rails today, and where the framework is headed tomorrow."). Video: [youtube.com/watch?v=gcwzWzC7gUA](https://www.youtube.com/watch?v=gcwzWzC7gUA). No official transcript exists; the widely repeated line that system tests are "always brittle, always broken, always slow" is **unverified** here (only secondary write-ups). His X posts of 2026-01-28 above state the same position in his own words.
- Rails World 2026 (upcoming): 2026-09-23 "Rails World 2026 Opening Keynote" and 2026-09-24 "AI and the future of Ruby & Rails — a chat with Matz & DHH" ([rubyonrails.org/world/2026/speakers/dhh](https://rubyonrails.org/world/2026/speakers/dhh)).

**Background (pre-2025, included because it is the root of the disagreement below)** — "TDD is dead. Long live testing.", 2014-04-23 ([dhh.dk/2014/tdd-is-dead-long-live-testing.html](https://dhh.dk/2014/tdd-is-dead-long-live-testing.html)): "Test-first fundamentalism is like abstinence-only sex ed: An unrealistic, ineffective morality campaign for self-loathing and shaming."; "I rarely unit test in the traditional sense of the word, where all dependencies are mocked out."; "Yes, test-first is dead to me."

### 1.2 Robert C. Martin (Uncle Bob)

**Where he publishes now.** The Clean Coder blog index ([blog.cleancoder.com](https://blog.cleancoder.com/)) has no post after January 2023. His 2025–2026 positions live on X (@unclebobmartin), on cleancoders.com, and in live-event listings. All tweet texts below were read in full from the permalinks (dates are the local times X displayed; UTC from the tweet IDs is within a day).

**On not reading agent code and measuring instead**

- 2026-04-15, reply to @wookash_podcast ([x.com/unclebobmartin/status/2044114698451476492](https://x.com/unclebobmartin/status/2044114698451476492)): "I don't review code written by agents. I measure things like test coverage, dependency structure, cyclomatic complexity, module sizes, mutation testing, etc. / Much can be inferred about the quality of the code from those metrics. The code itself I leave to the AI. / Humans are slow at code. To get productivity we humans need to disengage from code and manage from a higher level."
- 2026-07-23 ([x.com/unclebobmartin/status/2080257779395154409](https://x.com/unclebobmartin/status/2080257779395154409)): "I'm significantly older than you. I started coding in the late 60s. My current strategy is to not read any of the code written by my agents. That's the only way I can take advantage of their productivity. What I do instead is to surround the agents with extreme constraints. Unit tests, gherkin tests, QA procedures, quality metrics, mutation testing, test coverage, and a plethora of others. In the end, I have very high confidence in the code they produce because they've had to run the gauntlet of all of my constraints and tests."
- 2026-07-30 ([x.com/unclebobmartin/status/2082497764223492161](https://x.com/unclebobmartin/status/2082497764223492161)): "People keep on telling me that my message about AI is undercutting my own books. Those people do not understand how agents work and who actually controls them. You can't tell an agent to be clean. You have to measure the cleanliness that they produce and have them correct failures of cleanliness. / Without such constraints agents are more than happy to build big balls of mud that they can't maintain."

**On which tools, and the thresholds he uses**

- 2026-08-27, reply ([x.com/unclebobmartin/status/2092979362572181797](https://x.com/unclebobmartin/status/2092979362572181797)): "Yes. Linters, CRAP, Mutation tests, Dependency checkers, Acceptance testers, handoff policies, etc." (the question he answered was not captured)
- 2026-08-31, reply ([x.com/unclebobmartin/status/2094469156598870449](https://x.com/unclebobmartin/status/2094469156598870449)): "CRAP <= 6 (implies cyclomatic complexity) / Test Coverage: approaching 100% (asymptotic) / Mutations per file: <=100 (proxy for file size) / Surviving code mutants: 0 / Surviving gherkin mutants: 0 / Dead code: 0 / Duplicated code: ~0 (depends on how close) / Gherkin duplicates: ~0 (the obvious ones) / Dependency deviations: 0 / Introverted Tests: ~0 (the obvious ones)"
- 2026-08-17 ([x.com/unclebobmartin/status/2089140250773696905](https://x.com/unclebobmartin/status/2089140250773696905)): "TDD tests are intentional. They are are "positive". Mutations tests are reactions to testing gaps. They are negative. / Is there an advantage to a wholly negative test suite?"

**The September 2026 rethink (most recent, and it changes the picture)**

- 2026-09-07 ([x.com/unclebobmartin/status/2096994914185662851](https://x.com/unclebobmartin/status/2096994914185662851)): "For the past several months I've been working on this hypothesis that agents need to be constrained by a harness of deterministic tools that force them to follow a strict process. Gherkin, Code, Unit Tests, Crap, Mutation Tests, etc. / But while I've been hammering away on that, the models have gotten astronomically better. I'm starting to think that I'm over constraining them. Perhaps vastly over constraining them."
- 2026-09-08, reply ([x.com/unclebobmartin/status/2097319391566205039](https://x.com/unclebobmartin/status/2097319391566205039)): "No they're still useful. They are a way of ensuring that every expression is tested. The think that _might_ be wasted is the amount of ceremony I've imposed in order to ensure that mutation, crap, acceptance, qa, and unit tests are done properly."
- 2026-09-11 ([x.com/unclebobmartin/status/2098432570887217520](https://x.com/unclebobmartin/status/2098432570887217520)): "OK. It's time to rethink this. / I've spend the last several weeks working on a harness that tightly constrains the agents to work the way that I want them to work. I set up all kinds of gates, and tests, and tools, and protocols, and ... / And while I was heads-down getting that to work, the agents got a LOT better. So much so that when I came up for air, the need for my harness was obviated. Indeed, the need for _any_ but the most liberal of harnesses may be obviated. / [...] / I have not given up on constraints and tooling. Unit testing is still important. So is CRAP and Mutation testing. These tools still find bugs and offer useful constraints, though they can leave scars. / However, the agents have gotten so good that I can now give one a very significant task with a few guidelines and it will faithfully implement it. I can walk away for 40 minutes and when I return it will be done. CRAP will be satisfied, Coverage will be high, and Mutation testing complete. The architecture will be clean, and the code will be very good. / The end result may not behave perfectly, but it's so close that a couple of tweaks usually puts it into place. / What does this mean going forward? I'm not sure. But I'm beginning to think that harnesses should not treat agents as components within a software design." (The elided paragraph is about debating system structure with grok and codex.)
- 2026-09-12 ([x.com/unclebobmartin/status/2098745490007261251](https://x.com/unclebobmartin/status/2098745490007261251)): "Right now, I'm trying to figure out a way of triaging the mutation sites. Some aren't worth the trouble of closing, others are critical. It might be a good idea to have the mutation tester, create a list of candidate mutation sites, and then let an agent choose the critical ones."
- 2026-08-25 ([x.com/unclebobmartin/status/2092262702550835420](https://x.com/unclebobmartin/status/2092262702550835420)): "It was 18 months ago that I started getting serious about AI. At first it was just a few simple tasks and translations. But soon I was working hard with agents and tools. / It's astounding how quickly things have moved, and how much better the models have become."

**Courses / talks**

- cleancoders.com, "Clean AI: Agentic Discipline, Episode 1", by Justin Martin and Robert "Uncle Bob" Martin, released 2026-02-24 ([cleancoders.com/episode/agentic-discipline-1](https://cleancoders.com/episode/agentic-discipline-1)): "The greatest danger of using AI agents is to use an undisciplined approach. These tools are so powerful that undisciplined use can wreak havoc that is difficult to unwind."
- O'Reilly live event, "AI Agents for Clean Code with 'Uncle Bob' Martin" ([oreilly.com/live-events/…/0642572376765/](https://www.oreilly.com/live-events/ai-agents-for-clean-code-with-uncle-bob-martin/0642572376765/)): "Discover the disciplines of acceptance testing, unit testing, mutation testing, and code quality analysis" (event date not captured).
- No podcast with an official transcript was found for 2025–2026 (see "Could not verify").

**Linters / static analysis / formatters.** His only explicit mentions are "Linters" and "Dependency checkers" in the 2026-08-27 list and "cyclomatic complexity", "dependency structure", "module sizes", "CRAP" as measured metrics. No primary statement on code formatters was found.

### 1.3 Where they disagree, and where they now agree

| Topic | DHH | Uncle Bob |
|---|---|---|
| Test-first | "test-first is dead to me" (2014); catches bugs "with unit testing, with integration testing" (#474, 2025) | TDD is the intentional, "positive" suite; mutation tests fill gaps (2026-08-17) |
| Kind of tests | Model/controller/job tests; "Avoid system tests, except for a tiny smoke run" (2026-01-28) | Unit tests plus Gherkin acceptance tests, QA procedures (2026-07-23) |
| Reading agent code | Reviews "the shape of all of it" and "the individual lines of anything critical"; agents review PRs for him (#501) | "not read any of the code written by my agents" (2026-07-23); measure instead |
| Numeric gates | None stated. "Give them a linter and a test suite, and you have all you need." (2026-05-24) | Coverage → 100%, 0 surviving mutants, CRAP ≤ 6, 0 dependency deviations (2026-08-31) — then "I'm over constraining them. Perhaps vastly" (2026-09-07) |
| Instructions to agents | "Be as vague as you can." (#501) | "You can't tell an agent to be clean. You have to measure" (2026-07-30) |
| Architecture | Basecamp "let them vibe" PRs "destroyed the architecture" and were mopped up by hand (#501); "hold the line on quality and cohesion" (2026-01) | "Dependency deviations: 0" as a measured gate |
| Style | Omakase defaults, "not an invitation to bikeshed" | No primary statement found |

Convergence as of September 2026: both say automated tests are the gate for agent output (DHH 2026-03-06; Uncle Bob 2026-09-11 "Unit testing is still important"), and both have moved toward lighter harnesses (DHH "be as vague as you can"; Uncle Bob "the need for _any_ but the most liberal of harnesses may be obviated"). The live difference is whether to add measured gates beyond tests-plus-linter: Uncle Bob still keeps CRAP and mutation testing ("they can leave scars"); DHH does not mention either.

---

## Part 2 — Kotlin/Gradle tool landscape, verified for Kotlin 2.4.x / Gradle 9.7 / JDK 21

### 2.0 Baseline versions

- Kotlin: 2.4.0 released 2026-06-03, 2.4.10 on 2026-07-14, 2.4.20 on 2026-09-07 ([kotlinlang.org/docs/releases.html](https://kotlinlang.org/docs/releases.html)). 2.4.0 "promotes context parameters, explicit backing fields, and annotation use-site targets features to Stable" and "the compiler can generate classes containing Java 26 bytecode" ([whatsnew24](https://kotlinlang.org/docs/whatsnew24.html)). "Kotlin 2.4.20 is fully compatible with Gradle 7.6.3 through 9.7.0. You can also use Gradle versions up to the latest Gradle release. However, be aware that doing so may result in deprecation warnings, and some new Gradle features might not work." ([whatsnew2420](https://kotlinlang.org/docs/whatsnew2420.html)).
- Gradle: 9.7.1 released 2026-08-19; 9.8.0-RC1 on 2026-09-08 ([releases](https://github.com/gradle/gradle/releases)). Compatibility page (9.7.1): Java 21 supported for toolchains since 8.4 and for running Gradle since 8.5; Java 25 needs 9.1.0; the embedded Kotlin 2.4.0 requires Gradle 9.7.0 with language version 2.2; Gradle "is tested with Kotlin 2.0.0 through 2.4.20-Beta1" ([compatibility.html](https://docs.gradle.org/current/userguide/compatibility.html)).
- kotlin-test on JVM: "For Kotlin/JVM, Gradle uses JUnit 4 by default. Therefore, the `kotlin("test")` dependency resolves to the variant for JUnit 4"; "You can choose JUnit 5 or TestNG by calling `useJUnitPlatform()` […] in the test task of your build script." ([gradle-configure-project](https://kotlinlang.org/docs/gradle-configure-project.html)).

### 2.1 Formatting

| Tool | Latest, date | Built against | Config surface | Gradle plugin |
|---|---|---|---|---|
| ktfmt | v0.64, 2026-06-24 ([releases](https://github.com/Kotlin/ktfmt/releases)) — repo now lives at github.com/Kotlin/ktfmt (facebook/ktfmt redirects; Maven coordinates remain `com.facebook:ktfmt`) | Kotlin 2.4.10 ([gradle/libs.versions.toml](https://github.com/Kotlin/ktfmt/blob/main/gradle/libs.versions.toml)); "minimum supported runtime version is JDK 11" | "`ktfmt` exposes no configuration options that govern formatting behavior." Two styles: default 2-space, `--kotlinlang-style` 4-space ([README](https://github.com/Kotlin/ktfmt)) | `com.ncorti.ktfmt.gradle` 0.27.0, 2026-08-03: bundles ktfmt 0.64, Kotlin 2.4.10, Gradle 9.6.1, "Declare Configuration Cache compatibility" ([releases](https://github.com/cortinico/ktfmt-gradle/releases), [libs.versions.toml@0.27.0](https://github.com/cortinico/ktfmt-gradle/blob/0.27.0/gradle/libs.versions.toml)) |
| ktlint | 1.8.0, 2025-11-14 (stable); 2.0.0-ALPHA-4, 2026-08-21 ([releases](https://github.com/ktlint/ktlint/releases)) | 1.8.0: "Set languageVersion to KOTLIN_2_0"; embedded Kotlin 2.2.21 per the compose-rules matrix; 2.0.0-ALPHA-4: "Update kotlin to v2.4.0 / set java compilation to 26", "Drop support for Java 8 and 11", coordinates moved from `com.pinterest.ktlint` to `io.github.ktlint` ("This project is no longer maintained by Pinterest") | README: "An anti-bikeshedding Kotlin linter with built-in formatter", "No configuration required"; "Starting from version `1.0`, `ktlint_official` is the default code style" ([code-styles](https://ktlint.github.io/ktlint/latest/rules/code-styles/)); `.editorconfig` can still override | `org.jlleitschuh.gradle.ktlint` 14.2.0, 2026-03-12 (14.0.1: "Update build to work with gradle 9.1 and Java 25") ([releases](https://github.com/JLLeitschuh/ktlint-gradle/releases)) |
| detekt's ktlint wrapper | see detekt below | wraps ktlint 1.8.0 in 2.0.0-alpha.6 ([libs.versions.toml@v2.0.0-alpha.6](https://github.com/detekt/detekt/blob/v2.0.0-alpha.6/gradle/libs.versions.toml)) | "This rule set provides wrappers for rules implemented by ktlint"; "not included in the detekt-cli or Gradle plugin"; "can only be suppressed on file level" ([rules/ktlint](https://detekt.dev/docs/rules/ktlint/)) | artifact `dev.detekt:detekt-rules-ktlint-wrapper` (2.0) / `io.gitlab.arturbosch.detekt:detekt-formatting:1.23.8` (1.x) |
| Spotless (wrapper for either) | `gradle/8.10.2`, 2026-09-04 ([releases](https://github.com/diffplug/spotless/releases)) | — | — | `com.diffplug.spotless` |

Zero-config "one true style": ktfmt is the only one whose engine has no formatting knobs by design (google-java-format lineage: "its output is deterministic and is independent of the input code"). ktlint is opinionated by default but configurable through `.editorconfig`. The only ktfmt decision is 2-space (default) vs 4-space (`--kotlinlang-style`).

### 2.2 Static analysis

**detekt** ([releases](https://github.com/detekt/detekt/releases), [compatibility](https://detekt.dev/docs/introduction/compatibility/))
- 2.0.0-alpha.6, 2026-08-04: "built against Kotlin 2.4.10, Gradle 9.6.1, AGP 9.3.1 and is tested against JDK 25"; Gradle plugin id `dev.detekt` ([gradle](https://detekt.dev/docs/gettingstarted/gradle/)); minimum Kotlin Gradle plugin API 2.1.0 (libs.versions.toml). Earlier alphas: alpha.4 (2026-06-13) Kotlin 2.4.0; alpha.3 (2026-04-24) Kotlin 2.3.21.
- 1.23.8, 2025-02-21 (last stable): "Kotlin 2.0.21", Gradle 8.12.1, JDK 21; plugin id `io.gitlab.arturbosch.detekt`. No stable release for 19 months; the 1.x parser predates Kotlin 2.4's stable context parameters (whether it parses them is **unverified**).
- Baseline: "With the cli option `--baseline` or the detekt-gradle-plugin closure-property `baseline` you can specify a file which is used to generate a `baseline.xml`. It is a file where ignored findings are defined." Generate with the `detektBaseline` task ([baseline](https://detekt.dev/docs/introduction/baseline/)).
- Type resolution: the plain `detekt` task "Runs detekt WITHOUT type resolution"; `detektMain`/`detektTest` run "with type resolution on the `main` source set" and test set; rules needing it are marked `@RequiresFullAnalysis` ([type-resolution](https://detekt.dev/docs/gettingstarted/type-resolution/)).

**Kotlin compiler flags** ([gradle-compiler-options](https://kotlinlang.org/docs/gradle-compiler-options.html), [whatsnew21](https://kotlinlang.org/docs/whatsnew21.html), [whatsnew14](https://kotlinlang.org/docs/whatsnew14.html), [java-interop](https://kotlinlang.org/docs/java-interop.html))
- `allWarningsAsErrors` — "Report an error if there are any warnings", default `false`.
- `extraWarnings` (`-Wextra`) — default `false`; enables K2's extra checks: REDUNDANT_NULLABLE, PLATFORM_CLASS_MAPPED_TO_KOTLIN, ARRAY_EQUALITY_OPERATOR_CAN_BE_REPLACED_WITH_EQUALS, REDUNDANT_CALL_OF_CONVERSION_METHOD, USELESS_CALL_ON_NOT_NULL, REDUNDANT_SINGLE_EXPRESSION_STRING_TEMPLATE, UNUSED_ANONYMOUS_PARAMETER, REDUNDANT_VISIBILITY_MODIFIER, REDUNDANT_MODALITY_MODIFIER, REDUNDANT_SETTER_PARAMETER_TYPE, CAN_BE_VAL, ASSIGNED_VALUE_IS_NEVER_READ, UNUSED_VARIABLE, REDUNDANT_RETURN_UNIT_TYPE, UNREACHABLE_CODE.
- Explicit API mode: "Visibility modifiers are required for declarations if the default visibility exposes them to the public API" and "Explicit type specifications are required for properties and functions that are exposed to the public API"; "Explicit API mode analyzes only the production sources of a module." Gradle: `kotlin { explicitApi() }` or `explicitApiWarning()`; CLI `-Xexplicit-api={strict|warning}`.
- JSR-305: `-Xjsr305={strict|warn|ignore}`; "The default behavior is the same to `-Xjsr305=warn`. The `strict` value should be considered experimental (more checks may be added to it in the future)."

**Compose rules**
- mrmans0n/compose-rules v0.6.6, 2026-09-01: dependency matrix "detekt 2.0.0-alpha.6 / Kotlin 2.4.10" and "ktlint 1.8.0 / Kotlin 2.2.21" ([releases](https://github.com/mrmans0n/compose-rules/releases)). "Compose Rules is a set of custom ktlint / detekt rules to ensure that your composables don't fall into common pitfalls that might be easy to miss in code reviews." ([docs](https://mrmans0n.github.io/compose-rules/latest/)). detekt usage: `detektPlugins("io.nlopez.compose.rules:detekt:<VERSION>")` plus a `Compose:` block in `detekt.yml` ([detekt page](https://mrmans0n.github.io/compose-rules/latest/detekt/)). The docs do not mention Compose Multiplatform or desktop specifically (**unverified** whether any rule assumes Android).
- JetBrains' own Compose lint checks ship as Android Lint checks ("Compose 1.9 requires Android Gradle Plugin (AGP) / Lint version 8.8.2 or higher", [developer.android.com/develop/ui/compose/tooling/lint](https://developer.android.com/develop/ui/compose/tooling/lint)) — not applicable to a desktop-only Gradle module. No JetBrains-provided lint for Compose Desktop was found (**unverified** absence).

### 2.3 Architecture rules

| | Konsist | ArchUnit |
|---|---|---|
| Latest | v0.17.3, 2024-12-08 ([releases](https://github.com/LemonAppDev/konsist/releases)); `main` still pins `kotlinVersion = "2.0.21"` ([libs.versions.toml](https://github.com/LemonAppDev/konsist/blob/main/gradle/libs.versions.toml)) | v1.5.0, 2026-08-04: "Support Java 27", "`archunit-junit6` supports ArchUnit with JUnit 6", "Update Gradle from 8 to 9; build & test with JDK 25" ([releases](https://github.com/TNG/ArchUnit/releases)) |
| How it sees code | Parses Kotlin source with the embedded compiler ("Kotlin compiler embeddable 2.0.20" on the [compatibility page](https://docs.konsist.lemonappdev.com/help/compatibility)); "Konsist is backwards compatible with Kotlin `1.8.x`" | "It does so by analyzing given Java bytecode, importing all classes into a Java code structure" ([user guide](https://www.archunit.org/userguide/html/000_Index.html)) — Kotlin 2.4 syntax is irrelevant to it |
| Runs as | "first-class support for JUni4, JUnit5, and Kotest"; API `Layer("Presentation", "com.myapp.presentation..")`, `assertArchitecture { presentation.dependsOn(data); data.dependsOnNothing() }`, `doesNotDependOn` ([architecture-assert](https://docs.konsist.lemonappdev.com/writing-tests/architecture-assert)) | "Using the JUnit support with Kotlin is quite similar to Java"; `com.tngtech.archunit:archunit-junit5:1.5.0`, test scope |

Fit for Kotlin 2.4: ArchUnit, because it never parses Kotlin source. Konsist has had no release in 21 months and its parser is Kotlin 2.0.x. For the two rules named in the brief, no library is needed at all: "core must not depend on Ktor server" is enforced by not declaring the dependency in `core/build.gradle.kts`, and "reducer must not import Compose" is enforced by placing the reducer in `core` (which has no Compose dependency). ArchUnit only earns its place for a rule inside one module.

### 2.4 Coverage

- Kover 0.9.9, 2026-07-17 ([releases](https://github.com/Kotlin/kotlinx-kover/releases)); README badge "Kotlin Beta"; plugin `org.jetbrains.kotlinx.kover`; "Minimum supported version of `Gradle` is `6.8.3`"; "Verification rules with bounds in the Gradle plugin to keep track of coverage"; "Using JaCoCo library in Gradle plugin as an alternative for coverage measuring and report generation" ([README](https://github.com/Kotlin/kotlinx-kover)). 0.9.8: "declare configuration cache compatibility"; 0.9.9: "Fixed Gradle 9.6.0 deprecation warning" ([CHANGELOG](https://github.com/Kotlin/kotlinx-kover/blob/main/CHANGELOG.md)). Rule DSL: `kover { reports { verify { rule { minBound(80) } } } }`; `koverVerify` fails the build on violation ([gradle-plugin docs](https://kotlin.github.io/kotlinx-kover/gradle-plugin/)). Instrumentation note: "during the running tests, the classes are modified (instrumented) when loaded into the JVM which may lead to some performance degradation, or affect concurrent tests."
- JaCoCo: available as Kover's engine (above) or via Gradle's built-in `jacoco` plugin; no separate verification done here.

### 2.5 Mutation testing

- gradle-pitest-plugin 1.19.0, 2026-03-29: "Java 17+ and Gradle 8.4+ are the minimal supported versions", "Initial support for Gradle 9 (no warnings with 9.0-m9)", "PIT 1.22.1 by default" ([releases](https://github.com/szpak/gradle-pitest-plugin/releases)).
- pitest-kotlin (open source): "UPDATE - This plugin is not maintained. If you are interested in mutating kotlin a commercial plugin is now available from arcmutate." ([README](https://github.com/pitest/pitest-kotlin)).
- Arcmutate Kotlin plugin 1.5.1: "The kotlin plugin filters out these junk mutations and removes confusing noise from mutant descriptions."; "Before you can use the integration, you must first acquire a licence." with `arcmutate-licence.txt` at the project root ([docs](https://docs.arcmutate.com/docs/kotlin.html)). Pricing page returned 404 — **unverified**.
- Without the commercial plugin, PIT on Kotlin bytecode reports mutations in compiler-generated code (the "junk mutations" the plugin exists to filter). Uncle Bob's own current position (2026-09-11) keeps mutation testing but calls the surrounding ceremony possibly wasted.

### 2.6 Dependency hygiene

- Gradle dependency verification ([dependency_verification](https://docs.gradle.org/current/userguide/dependency_verification.html), 9.7.1): checksums "Verify the integrity of the artifact" and signatures "Verify the provenance of the artifact"; bootstrap `./gradlew --write-verification-metadata sha256,pgp`; file `gradle/verification-metadata.xml`; "Gradle will not automatically remove _unused_ entries from this file". Real maintenance cost example: gradle/actions v6.4.0-rc.1 (2026-09-08): "If your build has dependency verification enabled, you must add a second trusted key before upgrading, or Dependency Graph generation will fail signature verification." ([release](https://github.com/gradle/actions/releases/tag/v6.4.0-rc.1)); gradle-versions-plugin had to "Exempt the plugin's version lookups from the build's dependency verification, where the task failed on Gradle 8.7 and later in a build with verification enabled" (v0.62.0).
- Version catalog: `gradle/libs.versions.toml` is the default Gradle convention and the only path Dependabot reads (below).
- gradle-versions-plugin v0.62.0, 2026-09-12: "Test against Gradle 9.7.1 from a single pin", built-in `rejectPreReleases` ([releases](https://github.com/ben-manes/gradle-versions-plugin/releases)); plugin `com.github.ben-manes.versions`.
- Dependabot: supported-ecosystems table lists Gradle with version updates and security updates supported, private registries not supported ([docs](https://docs.github.com/en/code-security/reference/supply-chain-security/supported-ecosystems-and-repositories)). "Dependabot version updates keeps Gradle version catalogs up-to-date" (GitHub changelog, 2023-03-13, [link](https://github.blog/changelog/2023-03-13-dependabot-version-updates-keeps-gradle-version-catalogs-up-to-date/)). Limitation, open issue dependabot-core #8079: "it'll look at `gradle/libs.versions.toml`" only ([issue](https://github.com/dependabot/dependabot-core/issues/8079)). Config value: `package-ecosystem: gradle` ([options reference](https://docs.github.com/en/code-security/dependabot/working-with-dependabot/dependabot-options-reference)).
- GitHub dependency graph for Gradle: "Static transitive dependencies: Not supported", "Automatic dependency submission: Supported" ([dependency-graph ecosystems](https://docs.github.com/en/code-security/supply-chain-security/understanding-your-software-supply-chain/dependency-graph-supported-package-ecosystems)); i.e. GitHub does not parse Gradle build files, so Dependabot alerts require a submitted graph. `gradle/actions/dependency-submission@v6` "provides the simplest (and recommended) way to generate a dependency graph for your project" and needs `permissions: contents: write` ([docs](https://github.com/gradle/actions/blob/main/docs/dependency-submission.md)).
- Renovate: gradle manager default `managerFilePatterns` include `/(^|/)gradle/.+\.toml$/` and `/\.versions\.toml$/`; "This manager supports `lockFileMaintenance` for the following file(s): `gradle.lockfile`"; running the Gradle wrapper needs `allowedUnsafeExecutions` ([docs](https://docs.renovatebot.com/modules/manager/gradle/)).
- OWASP dependency-check-gradle 13.0.0, 2026-08-03 (default output moved to `build/reports/dependency-check`) ([releases](https://github.com/dependency-check/dependency-check-gradle/releases)); plugin `org.owasp.dependencycheck`; "the first time this task is executed it may take 5-20 minutes as it downloads and processes the data from the National Vulnerability Database" ([plugin docs](https://jeremylong.github.io/DependencyCheck/dependency-check-gradle/index.html)); "Users of dependency-check are **highly** encouraged to obtain an NVD API Key […] Without an NVD API Key dependency-check's updates will be **extremely slow**." and OSS Index now requires credentials ([README](https://github.com/dependency-check/DependencyCheck)).
- CycloneDX Gradle plugin 3.4.1, 2026-08-11 (3.4.0 added "Support for CycloneDX 1.7 schema") ([releases](https://github.com/CycloneDX/cyclonedx-gradle-plugin/releases)); plugin `org.cyclonedx.bom`.

### 2.7 Security scanning of code

- CodeQL: "Kotlin 1.8.0 to 2.4.20", build system kotlinc, under the Java/Kotlin row ([supported languages](https://codeql.github.com/docs/codeql-overview/supported-languages-and-frameworks/)); GitHub changelog: 2.26.0 (2026-07-10) "adds Kotlin 2.4.0 support", 2.26.2 (2026-08-04) "Kotlin 2.4.10 support". Setup note: "If a repository contains Kotlin code in addition to Java code, default setup is enabled with the autobuild process because Kotlin analysis requires a build." and "For Java analysis, if `build-mode` is set to `none` and Kotlin code is found in the repository, the Kotlin code will not be analyzed and a warning will be produced." ([compiled languages](https://docs.github.com/en/code-security/code-scanning/creating-an-advanced-setup-for-code-scanning/codeql-code-scanning-for-compiled-languages)).
- Semgrep: Kotlin row — Semgrep Code "Generally available • Cross-file dataflow analysis • 60+ Pro rules"; Supply Chain "Generally available • Reachability analysis • Can detect open source licenses" ([supported languages](https://docs.semgrep.dev/supported-languages)). Community-edition (free engine) maturity for Kotlin: **unverified**.

### 2.8 Git hooks (dev machine: Windows 11 + Git Bash)

- Git itself: "By default the hooks directory is `$GIT_DIR/hooks`, but that can be changed via the `core.hooksPath` configuration variable"; "Hooks that don't have the executable bit set are ignored."; pre-commit "can be bypassed with the `--no-verify` option" ([githooks](https://git-scm.com/docs/githooks)). A hook is a script; on this machine Git Bash is already present, so a plain `#!/bin/sh` hook needs no extra tool (its execution by Git for Windows' bundled sh is **unverified** as a documented statement).
- ktlint CLI can write the hook for you: `ktlint installGitPreCommitHook` / `ktlint installGitPrePushHook` ([cli docs](https://ktlint.github.io/ktlint/latest/install/cli/)). No equivalent for ktfmt.
- pre-commit 4.6.2, 2026-08-10 ([releases](https://github.com/pre-commit/pre-commit/releases)): "A framework for managing and maintaining multi-language pre-commit hooks"; requires Python (`pip install pre-commit`); CI via `pre-commit run --all-files` ([pre-commit.com](https://pre-commit.com/)).
- lefthook 2.1.12, 2026-08-28 ([releases](https://github.com/evilmartians/lefthook/releases)): "It is single dependency-free binary which can work in any environment"; installs via winget, scoop, brew, npm, go; `lefthook install`; jobs with `glob` and `{staged_files}` ([README](https://github.com/evilmartians/lefthook), [lefthook.dev](https://lefthook.dev/)).
- Gradle-native git hooks: none exist in Gradle itself (no primary source found; **unverified** absence).

### 2.9 AI-agent-specific gates

- Claude Code hooks ([code.claude.com/docs/en/hooks](https://code.claude.com/docs/en/hooks)): events include `SessionStart`, `UserPromptSubmit`, `PreToolUse` ("Before a tool call executes. Can block it"), `PostToolUse` ("After a tool call succeeds"), `PostToolUseFailure`, `Stop` ("When Claude finishes responding"), `SubagentStop`, `SessionEnd`. Matchers: "Comma separators and `|` separate alternatives. Example matcher values: `Bash`, `Edit|Write`, `mcp__.*`". Exit-code-2 table: `PreToolUse` — "Blocks the tool call"; `PostToolUse` — can block: No — "Shows stderr to Claude; the tool already ran"; `Stop` — "Prevents Claude from stopping, continues the conversation"; `UserPromptSubmit` — "Blocks prompt processing and erases the prompt". PostToolUse also accepts JSON `decision: "block"` with `reason`. "To surface a warning to Claude from a `PostToolUse` or `PostToolUseFailure` hook, exit 2 instead so Claude sees the stderr even though the tool already ran." Config lives in `.claude/settings.json` (project, committable), `.claude/settings.local.json`, or `~/.claude/settings.json`; default `command` hook timeout 600 s. Windows: "On Windows, exec form requires `command` to resolve to a real executable such as a `.exe`. The `.cmd` and `.bat` shims that npm, npx, eslint, and other tools install in `node_modules/.bin` are not executables and can't be spawned without a shell."
- GitHub rulesets: "Rulesets are available in public repositories with GitHub Free and GitHub Free for organizations, and in public and private repositories with GitHub Pro, GitHub Team, and GitHub Enterprise Cloud." ([about rulesets](https://docs.github.com/en/repositories/configuring-branches-and-merges-in-your-repository/managing-rulesets/about-rulesets)). "Required status checks ensure that all required CI tests are passing before collaborators can make changes to a branch or tag targeted by your ruleset."; strict mode: "The topic branch **must** be up to date with the base branch before merging." ([available rules](https://docs.github.com/en/repositories/configuring-branches-and-merges-in-your-repository/managing-rulesets/available-rules-for-rulesets)).

### 2.10 CI on GitHub Actions

- gradle/actions v6.3.0, 2026-08-02 (stable; v6.4.0-rc.1 on 2026-09-08) ([releases](https://github.com/gradle/actions/releases)); usage `uses: gradle/actions/setup-gradle@v6`. Caching: "The action will only write to the cache from Jobs on the default (`main`/`master`) branch. Jobs on other branches will read entries from the cache but will not write updated entries."; `cache-cleanup: on-success` by default; build scans via `build-scan-publish: true` plus `build-scan-terms-of-use-url` and `build-scan-terms-of-use-agree: 'yes'` ([setup-gradle.md](https://github.com/gradle/actions/blob/main/docs/setup-gradle.md)). v6.3.0 fixed "Cache entries failed to store at all on Windows" and "Cache cleanup deleted instrumented jars that were in use" — update to ≥ 6.3.0 if a Windows job is in the matrix.
- Billing: "GitHub Actions usage is free for self-hosted runners and for public repositories that use standard GitHub-hosted runners."; GitHub Free includes 2,000 minutes/month; per-minute rates Linux 2-core $0.006, Windows 2-core $0.010 ([billing](https://docs.github.com/en/billing/managing-billing-for-your-products/managing-billing-for-github-actions/about-billing-for-github-actions)).
- Compose rules without type resolution, measured on this repo 2026-09-19: the rules that turn on what a composable returns are blind to an expression-bodied one. `@Composable private fun HealthLines(h: Health?) = Column { … }` is not read by `ComposableNaming` at all; the same function with a block body is. Nothing warns that a rule declined to judge a declaration, so the composables in `app` are written with block bodies to stay inside the gate. `UnstableCollections` is off for the same reason, and says so in `config/detekt/compose.yml`.
- Compose Desktop tests: add `implementation(compose.desktop.uiTestJUnit4)` and `implementation(compose.desktop.currentOs)` to the desktop test source set; tests use `createComposeRule()` (a JUnit 4 rule) ([compose-desktop-ui-testing](https://kotlinlang.org/docs/multiplatform/compose-desktop-ui-testing.html)). The docs say nothing about headless mode or a display server; whether Ubuntu runners need xvfb is **unverified** (a 2022 issue, [compose-multiplatform#2398](https://github.com/JetBrains/compose-multiplatform/issues/2398), shows `HeadlessException` from `java.awt.MouseInfo`, not from the test rule). Peashoot's planned tests (HTTP seam with fake upstream, pure reducer) do not need it.

### 2.11 Build hygiene

- Configuration cache: enable with `org.gradle.configuration-cache=true` (or `-Dorg.gradle.configuration-cache=true`); "This feature _will be enabled by default in Gradle 10_"; `org.gradle.configuration-cache.problems=warn` while adopting ([configuration_cache](https://docs.gradle.org/current/userguide/configuration_cache.html)). "The Kotlin plugin uses the Gradle configuration cache" ([gradle-compilation-and-caches](https://kotlinlang.org/docs/gradle-compilation-and-caches.html)).
- Build cache: `org.gradle.caching=true` in `gradle.properties`; "the local build cache is pre-configured to be a DirectoryBuildCache and enabled by default" ([build_cache](https://docs.gradle.org/current/userguide/build_cache.html)); "The Kotlin plugin uses the Gradle build cache".
- Kotlin incremental compilation: "enabled by default for Kotlin/JVM and Kotlin/JS projects".
- Reproducible archives: "archives are not reproducible by default"; set `isPreserveFileTimestamps = false` and `isReproducibleFileOrder = true` on archive tasks ([working_with_files](https://docs.gradle.org/current/userguide/working_with_files.html)).

### 2.12 Test frameworks and property testing

- JUnit 5 via `kotlin("test")` + `useJUnitPlatform()` (2.0 above). No extra plugin.
- Kotest 6.2.5, 2026-09-10 ([releases](https://github.com/kotest/kotest/releases)); its build pins `kotlin-gradle-plugin = "2.2.21"` "pinned to latest 2.2.x patch to produce 2.2-compatible KLIBs", `kotlin-language-version = "2.2.0"`, JUnit Platform 6.0.3 / 5.13.4, `jvmMaxTarget = "21"` ([libs.versions.toml@v6.2.5](https://github.com/kotest/kotest/blob/v6.2.5/gradle/libs.versions.toml)). Property testing is separable: "You do **not** need to be using Kotest as your test framework (although you should!) to benefit from the property test support." — artifact `io.kotest:kotest-property` ([docs](https://kotest.io/docs/proptest/property-based-testing.html)).
- jqwik 1.10.1, 2026-05-29: "Starting with version 1.10 jqwik comes with an **Anti-AI Usage Clause**! Usage with any "AI" agent is strongly discouraged."; "**This project is not meant to be used by any "AI" coding agents at all.**"; "This will probably be the last release of Jqwik using JUnit Platform version 1.x." ([release](https://github.com/jqwik-team/jqwik/releases/tag/1.10.1)).

---

## Part 3 — Recommendation for Peashoot (lazy tiers)

Ground rules taken from Part 1: the gate is a linter plus a test suite (DHH 2026-05-24), style is a chef's choice nobody argues about (rubocop-rails-omakase README), and "You can't tell an agent to be clean. You have to measure" (Uncle Bob 2026-07-30) — but measure with the lightest harness that still fails the build, because the harness author himself now says "I'm over constraining them" (2026-09-07).

The repo today has only `CLAUDE.md` and `docs/`; no Gradle files exist yet, so Tier 1 is written into the initial skeleton.

### Tier 1 — install before ticket 1 (pays for itself on the first agent-written PR)

| # | Item | Exact coordinates (verified in Part 2) | Why (one line) | Gate |
|---|---|---|---|---|
| 1 | Formatter: ktfmt, kotlinlang style | plugin `com.ncorti.ktfmt.gradle` **0.27.0** (bundles ktfmt 0.64, Kotlin 2.4.10, config-cache compatible); `ktfmt { kotlinLangStyle() }` | "not an invitation to bikeshed" — ktfmt "exposes no configuration options"; agents and human never argue about layout, diffs stay semantic | `ktfmtCheck` runs under `check` → CI fails; local `ktfmtFormat` via the Claude hook in row 5 |
| 2 | Compiler as linter | in each module: `kotlin { compilerOptions { allWarningsAsErrors = true; extraWarnings = true } }` (Kotlin 2.4.20; Gradle 9.7.0 is the last "fully compatible" version, 9.7.1 works with possible deprecation warnings) | Zero-install static analysis: `-Wextra` catches unused variables, `CAN_BE_VAL`, `UNREACHABLE_CODE`, redundant modifiers — exactly the noise agents emit; DHH: "Give them a linter and a test suite" | compile fails |
| 3 | Tests: kotlin-test on JUnit 5 | `testImplementation(kotlin("test"))` + `tasks.test { useJUnitPlatform() }`; tests at the HTTP seam against a fake upstream + pure reducer, as planned | DHH: "Unit tests, functional tests, the rest. If you let agents produce a mountain of slop without any testing, you'll get the same buggy crap"; Uncle Bob: "Unit testing is still important." | `test` under `check` → CI fails |
| 4 | CI + merge gate | `.github/workflows/ci.yml`: matrix `ubuntu-latest`/`windows-latest`, `actions/setup-java` (Temurin 21), `gradle/actions/setup-gradle@v6` (≥ 6.3.0 for the Windows cache fix), `./gradlew build`; repository ruleset on `main` requiring both matrix checks (free on a public repo) | DHH's own default: "a default GitHub CI workflow file […] automated scanning, linting, and testing" (PR #50508); the brief already says "CI from the first commit" | red CI blocks merge |
| 5 | Claude Code hooks (project `.claude/settings.json`, committed) | `PostToolUse` matcher `Edit\|Write` → run ktfmt on the touched file (fast, deterministic); `Stop` → `./gradlew check -q`, exit 2 on failure | `Stop` exit 2 "Prevents Claude from stopping" — the agent cannot declare a ticket done with red tests or unformatted code; this is Uncle Bob's gauntlet in ~15 lines of JSON, without a hook manager | agent is bounced back before a PR exists |
| 6 | Gradle hygiene, two lines | `gradle.properties`: `org.gradle.configuration-cache=true`, `org.gradle.caching=true`; `gradle/libs.versions.toml` as the single version source | Makes the `Stop` hook cheap on re-runs (config cache skips configuration; build cache skips unchanged tests); Kotlin plugin supports both | none (speed) |
| 7 | Dependency updates + alerts | `.github/dependabot.yml` with `package-ecosystem: gradle` (reads `gradle/libs.versions.toml`) and `github-actions`; `.github/workflows/dependency-submission.yml` with `gradle/actions/dependency-submission@v6` on push to `main` (`permissions: contents: write`) | Dependabot ships in DHH's default CI files; GitHub cannot see Gradle deps without submission ("Static transitive dependencies: Not supported"), and a proxy that terminates TLS wants Ktor/Netty CVE alerts | PRs + alerts (report, not fail) |

Estimated setup: 2–3 hours total — Gradle skeleton with catalog and three modules ~60 min, ktfmt + compiler flags ~20 min, CI workflow + ruleset ~40 min, hooks ~20 min, Dependabot + submission ~15 min. Everything in Tier 1 is committed config; nothing needs installing on the Windows machine beyond JDK 21 and Git Bash.

What runs where: on every agent edit — ktfmt on the file (sub-second). On every agent `Stop` and every human commit that gets pushed — `./gradlew check` (compile with warnings-as-errors, ktfmtCheck, tests; seconds with caches warm). CI only — the same `build` on both OSes, dependency submission, and later CodeQL.

### Tier 2 — add when the trigger fires (each 5–30 min)

| Item | Coordinates | Trigger | Gate |
|---|---|---|---|
| detekt | `id("dev.detekt") version "2.0.0-alpha.6"` — the only line built against Kotlin 2.4.x (1.23.8 is Kotlin 2.0.21, Feb 2025); no type resolution; `detektBaseline` once, then `detekt` under `check` | First review where the human finds an over-long function, a swallowed exception, or a god-file the compiler cannot see (Uncle Bob's "cyclomatic complexity, module sizes"). Alpha status is the reason it is not Tier 1 | fail CI (after a two-week report-only run) |
| Compose rules | **Done in #15.** `detektPlugins(libs.compose.rules.detekt)` on `:app` alone, and `config/detekt/compose.yml` added to that module's detekt config; the upstream `Compose:` block verbatim but for `UnstableCollections`, off because it needs the type resolution this build's `detekt` task does not run. The trigger fired as written: `app` exists and detekt is on | fail CI |
| Explicit API mode | Was `kotlin { explicitApi() }` in `core` only; removed 2026-09-13 | Tried in #4, dropped in #5: a written `public` and return type on every declaration cost more than the accidental-API risk with both consumers in one repo | off |
| Module boundaries | none: `core` declares no Ktor-server and no Compose dependency; the reducer lives in `core` | Already true in the skeleton — Gradle's dependency graph is the architecture test | compile fails |
| ArchUnit | `testImplementation("com.tngtech.archunit:archunit-junit5:1.5.0")` (bytecode-based, unaffected by Kotlin 2.4 syntax) | Only if a rule *inside* one module is needed (e.g. `proxy..store` must not call `proxy..http`). Skip Konsist (no release since 2024-12, parser pinned to Kotlin 2.0.x) | plain JUnit test |
| Kover | `id("org.jetbrains.kotlinx.kover") version "0.9.9"`; `koverHtmlReport` only, no `minBound` | When the human wants to see which reducer branches agents never exercised; add a threshold only for `core` and only if coverage regresses | report; optional `koverVerify` |
| Plain pre-commit hook | five-line `#!/bin/sh` in `.git/hooks/pre-commit` (Git Bash runs it) calling `./gradlew ktfmtCheck -q`; no lefthook, no pre-commit framework | Only if human edits keep failing CI on formatting (agents are already covered by the hook) | local |
| CodeQL default setup | repo Settings → Code security; autobuild is chosen automatically because Kotlin "requires a build" | At the three-month public milestone (free on public repos) | alerts (report) |
| Renovate | replace Dependabot with the Renovate app, gradle manager | Only if catalog PR conflicts become a weekly annoyance | PRs |
| Kotest property tests | `testImplementation("io.kotest:kotest-property:6.2.5")` under JUnit 5, no Kotest runner | When the reducer or the cassette matcher gets a normalization rule worth generating inputs for | `test` |

### Tier 3 — skip, and why

- **Mutation testing (PIT + arcmutate).** The open-source Kotlin plugin "is not maintained"; the working one is commercial with an unpublished price and a licence file in the repo; PIT on raw Kotlin bytecode reports junk mutants. The only person in Part 1 who uses it wrote on 2026-09-08 that "the amount of ceremony I've imposed […] _might_ be wasted" and on 2026-09-11 that such tools "can leave scars". Revisit at v3 when regression assertions with CI thresholds are in scope.
- **jqwik.** Its 1.10 licence adds an "Anti-AI Usage Clause": "This project is not meant to be used by any "AI" coding agents at all." Peashoot's code is agent-written; use kotest-property if property tests are wanted.
- **Kotest as the test framework.** Compiled against Kotlin 2.2.21 with a separate runner and IDE plugin; `kotlin("test")` + JUnit 5 is already in the toolchain. (Its property library alone is fine — Tier 2.)
- **Konsist.** Last release 2024-12-08; `main` pins Kotlin 2.0.21; ArchUnit or Gradle module deps cover the need.
- **Gradle dependency verification.** "Gradle will not automatically remove _unused_ entries"; a key rotation in gradle/actions (v6.4.0-rc.1) already breaks builds that enabled it. Solo project, Maven Central only: not worth the churn. Dependabot alerts cover known CVEs.
- **OWASP dependency-check.** Needs an NVD API key or is "extremely slow", 5–20 min first run; the submitted dependency graph plus Dependabot alerts give the same CVE signal for free.
- **CycloneDX SBOM.** No consumer exists yet; two lines when a downstream asks for one.
- **gradle-versions-plugin.** Redundant with Dependabot PRs.
- **Semgrep.** Kotlin is GA in the paid product; community-engine status unverified; CodeQL (Tier 2) is free on the public repo.
- **Build scans.** Publishing requires agreeing to gradle.com terms per run; add only when a CI job is slow or flaky and needs a timeline.
- **Reproducible archives.** No artifact consumers until the first JAR release; then two lines on the `jar` task.
- **lefthook / pre-commit framework.** Both fine tools, both a second runtime or binary on a Windows machine; the Claude `Stop` hook covers agent commits and a plain sh hook covers human ones.
- **ktlint (as the formatter).** Also acceptable, but its stable 1.8.0 embeds a Kotlin 2.2.21 parser and 2.0 is alpha; ktfmt 0.64 is built on Kotlin 2.4.10 with no knobs. Do not run both formatters.
- **xvfb on Ubuntu runners.** No Compose UI tests are planned for v1; the tests sit at the HTTP seam and the reducer. Revisit if a Compose test is added and fails headless.
- **Dropping the Windows runner.** Keep it: the dev machine and the shipped JAR are Windows-first, and public-repo minutes are free.

---

## Could not verify

- DHH's Rails World 2025 keynote wording on system tests ("always brittle, always broken, always slow…") — no official transcript; only secondary summaries. Video: youtube.com/watch?v=gcwzWzC7gUA. His equivalent position is verified from his X posts of 2026-01-28.
- Claim (from a search-engine summary) that Rails 8.1 scaffolds no longer generate system tests by default — not found in the fetched 8.1 release post or release notes.
- Lex Fridman #501 quotes containing "…" or brackets were tool-extracted from the official transcript; verify the exact wording at the timestamps before quoting onward.
- The messages Uncle Bob and DHH were replying to in their X replies (context not captured); the Aug 27 and Aug 31 lists are answers to unknown questions.
- Any 2025–2026 podcast/interview with Uncle Bob that has an official transcript (the epicproduct.engineer episode page returned 404; YouTube pages could not be fetched); the O'Reilly event date.
- Uncle Bob's stance on code formatters specifically (none found).
- Semgrep Community Edition maturity for Kotlin (only the Semgrep Code / Supply Chain rows were on the page).
- Arcmutate pricing (page returned 404).
- Whether Compose Desktop `createComposeRule` tests run without a display server on Ubuntu runners (docs silent).
- Existence of a JetBrains-provided lint for Compose Desktop (none found; the JetBrains/Google Compose lint checks are Android Lint checks).
- Whether detekt 1.23.8, ktlint 1.8.0, or Konsist 0.17.3 parse Kotlin 2.4-only syntax such as stable context parameters — inferred from their embedded compiler versions, not tested.
- Any Gradle-native git-hook mechanism (none found).
- That Git for Windows executes `.git/hooks/*` via its bundled `sh` (behavior is well known but not cited from a Git document here).
- Dependabot *security update* PRs for Gradle when the graph comes from the submission API (the ecosystems table says supported; not exercised).
- Kotlin 2.4.20 on Gradle 9.7.1 specifically: JetBrains documents full compatibility "through 9.7.0" and says newer Gradle "may result in deprecation warnings".
