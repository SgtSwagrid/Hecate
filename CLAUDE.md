# CLAUDE.md

This file provides guidance to [Claude Code](https://claude.com/product/claude-code) when working with code in this repository.
It is not intended for human eyes.

### Maintenance

You (robot or human) have standing permission to update this file without asking.
Add important patterns, gotchas, or context that would help future sessions.
Keep it concise and actionable.

## Project overview

This is Hecate, a Scala 3 library for user accounts, sign-in sessions, nestable user groups and permissions,
for full stack websites built on Tapir, Slick, Cats Effect and Laminar. It is in beta.

- `core` (`com.alecdorrington.hecate`, JVM + JS) - the model (`User`, `Caller`, `Group`, `Principal`, `Access`,
  `Grant`, `Resource`, `Permitted`, `Gated`, `AuthRefusal`), the wording of what the library says (`i18n/Wording`)
  and the Tapir endpoint definitions (`api/AuthApi`).
- `server` (`com.alecdorrington.hecate.server`) - password hashing, persistence and the endpoint implementations.
  It opens no database but takes a `Transactor`; its tables are defined against a `JdbcProfile` passed to `AuthTables`
  rather than a fixed one, so identifiers go through the `prefix` parameter, never string literals; and it deletes
  nothing of the host's, taking instead a cascade hook run inside its own deletion transactions.
  It creates its own tables (`AuthTables.createIfNotExists`) and has no migrations.
- `client` (`com.alecdorrington.hecate.client`) - headless Laminar state (`AuthState`, `GroupsState`).

The library never picks a language or writes a sentence of its own. A refusal is an `AuthRefusal` value;
`Wording` (one member per phrase, so adding one fails every implementation until it is translated) turns one
into a sentence, and the host passes `wording: Option[String] => Wording` to `AuthService`, `GroupService` and
`AuthState`, defaulting to `Wording.english`. The language arrives as a `language` cookie
(`AuthApi.languageCookie`) read by `AuthApi.secured` and by `register`/`login`/`recover`, so
`AuthApi.Security` is `(session, language)` and `AuthService.require` answers a `Caller` (user plus locale).
`AuthProblem` carries the refusal, and its `getMessage` is the English phrase, for logs and message-based tests.

See [README.md](README.md) for how a host wires it up.

### Where this code lives

This repository is a mirror. The library is developed inside a larger private project, beneath `hecate/`, and every file
here is copied from there by [GitHub Graph](https://github.com/SgtSwagrid/github-graph) whenever that project's `main`
changes, overwriting whatever is here. So make changes there, never here. The shared configuration (workflows, Scalafmt, IDE settings, `project/plugins-*.sbt`
other than `plugins-scalajs.sbt`) comes from further upstream still, in
[Scala Library Config](https://github.com/SgtSwagrid/scala-library-config), which syncs into the private project's `hecate/` first.
`build.sbt`, `release.sbt`, `project/Dependencies.scala`, `README.md` and this file belong to the library.

### Build

- `hecateCore` is a `projectMatrix` (JVM + JS; the JS row is `hecateCoreJS`), `hecateServer` is JVM, `hecateClient` is
  Scala.js, and the root project `hecate` only aggregates them and is never published.
- Project ids are prefixed with the library's name because the private project includes this build by reference
  (`ProjectRef(file("hecate"), ...)`), and its own projects are called `server`, `client` and `common`.
- The matrix pins `sourceDirectory` to `(ThisBuild / baseDirectory) / "core" / "src"`. Don't remove it: sbt 2.0.8
  resolves a matrix's sources against the working directory, which is the host's when the build is included by reference,
  and the library then compiles to an empty JAR without a single error of its own.
- The library must never depend on anything in the project that includes it, and nothing here should assume a host, a database or a JDBC profile.
- Versions come from git tags (`sbt-ci-release`); publishing a GitHub release publishes to Maven Central.

## Instructions

### Compilation and Diagnostics

- When the user asks for help with a compilation or type error, start by running `sbt compile` to see the error for yourself.
  If there are many errors, making it unclear which one the user is referring to, ask them to clarify, and then focus only on that issue.
- IntelliJ MCP integration is active. When a request seems to implicitly refer to something the user is looking at, always check
  `mcp__ide__getDiagnostics` first to see which file(s) are open and get associated diagnostics (errors, warnings, and info hints with line numbers).

#### Testing

- After making code changes, always run `sbt compile` to verify that issues are fixed and no new ones are introduced.
- Repeatedly retry upon failure until the build succeeds. If you are unsure how to fix an issue, ask for help or refer to existing code for examples.
- Before trying to fix an error, make sure you first understand it fully.
- You should never report that a feature is complete without testing it first.

### Code Style

- You must read the [Code Style Guidelines](docs/STYLE_GUIDE.md).

### Pull Requests

When asked to publish the code changes, your task is to open one or more pull requests (PRs) to merge the changes into `main` on GitHub:

- Use `git` to check what has changed as compared to the `main` branch on `origin`.
- If the changes are thematically linked, they can be published as a single PR.
- Otherwise, you'll need to divide the changes into multiple PRs using your own judgement.
- Each PR should have a singular focus, shouldn't break anything, and should be able to be merged independently.
- Ensure that all code is staged, committed and pushed. Ensure no new files are left uncommitted, and no debug code is left in the codebase.
- When creating a PR, ensure that the title and description are clear, informative, and comprehensive.
- All feature/bugfix/etc branch names should be formatted as "feature_<short description>" or "fix_<short description>" or similar.
- All PR titles should be formatted as "[<scope>] <Short summary>", e.g. "[renderer] Fixed colour inversion bug."
- You have GitHub MCP integration that can be used to do the above.
