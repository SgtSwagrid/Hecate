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
  Groups are joined by invitation, by a request the owner admits (only of a group the asker can see:
  public, or nested in one they belong to), or by an invite link; `LinkStore`/`LinkService` keep one link
  per group or resource, each a random `InviteCode` (five of a-z0-9, any case, always a digit), and a link
  to a resource grants its level through `GrantStore.raise`, under the row lock the host's `resources` hook takes.
- `client` (`com.alecdorrington.hecate.client`) - headless Laminar state (`AuthState`, `GroupsState`).

The library tells the host whom each change it makes concerns, never what changed, through the `affected:
Affected => IO[Unit]` hook of `GroupService`, `LinkService` and `AuthService` (default: tell nobody), once the
change is committed, so that a host with a socket can tell them to read again. A change to who is in a group
(or to its link) concerns its owner, its members (who see one another) and that person; a change to the group itself concerns everyone who saw
anything of it before or after (`GroupStore.surroundings`, asked before and after), and `Audience.Everyone`
when it was or is public; a followed link to a resource is `Affected.Grants`, whose audience the host knows;
signing out, a new password or codes and recovery are `Affected.Account`; deleting an account concerns
everyone. A failure to work out an audience is reported, never failing the request. `Permissions.holders` is
the reverse of `access`: everyone a grant over a resource reaches. `AuthState.refresh` reads the account again
without disturbing a user still signed in (`recheck` re-sets the user, which clears codes being shown).
`/api/auth/me` answers an empty body when nobody is signed in (how `jsonBody[Option[_]]` sends `None`), which
`AuthState.signedIn` reads as signed out; decoding it as JSON fails, and once made every recheck ignore a
session that had ended.

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
