# Quality gates

What checks the code, where it runs, where it is configured. Why each exists and what was skipped: `research/code-quality-environment.md`.

| Check | Catches | Runs | Configured in |
|---|---|---|---|
| Kotlin compiler, warnings as errors plus extra warnings | Every warning: unused values, redundant code, unreachable code | `compileKotlin`, part of `build` | `build.gradle.kts` (`compilerOptions`) |
| ktfmt, kotlinlang style | Layout; the tool has no options | `ktfmtCheck` under `check`; `ktfmtFormat` by the edit hook and pre-commit | `build.gradle.kts`, `.claude/settings.json`, `.githooks/pre-commit` |
| detekt 2.0, default rules, no baseline | Complexity, size, swallowed exceptions, utility-class smells | `detekt` under `check` | `build.gradle.kts` (plugin only, no `detekt.yml`) |
| Explicit API mode | Accidental public API in `core` | `core:compileKotlin` | `core/build.gradle.kts` |
| ArchUnit | `core` depending on Ktor server or Compose | `core:test` | `core/src/test/kotlin/dev/peashoot/core/ArchitectureTest.kt` |
| Tests, kotlin-test on JUnit 5 | Behavior at the HTTP seam and in the reducer | `test` under `check` | `build.gradle.kts`; each module's `src/test` |
| Kover | Untested branches; report only, no threshold | `koverHtmlReport` on demand, output in `build/kover/html` | `build.gradle.kts` |
| Stop hook | An agent stopping while `check` is red; three bounces, then it lets go | Claude Code `Stop` event | `.claude/hooks/check.sh`, `.claude/settings.json` |
| Pre-commit hook | A human committing unformatted or red code | `git commit`, after `git config core.hooksPath .githooks` | `.githooks/pre-commit` |
| CI | The same `build` on Ubuntu and Windows, every PR and push to `main` | GitHub Actions | `.github/workflows/ci.yml` |
| Dependabot and dependency submission | Stale or vulnerable dependencies | Weekly PRs; security alerts | `.github/dependabot.yml`, `.github/workflows/dependency-submission.yml` |

Not gates: the Kotlin LSP plugin, installed per machine, gives agents navigation and diagnostics. CodeQL and a required-checks merge gate wait for the repo to go public. Every version lives in `gradle/libs.versions.toml`.
