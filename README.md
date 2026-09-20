<div align="center">

  <h1>🗝️ Hecate</h1>
  <p>User accounts, sessions, groups and permissions for full stack <a href="https://www.scala-lang.org/">Scala</a> websites.</p>

  <span>
    <a href="https://github.com/SgtSwagrid/Hecate/actions/workflows/build-integrity.yml"><img src="https://github.com/SgtSwagrid/Hecate/actions/workflows/build-integrity.yml/badge.svg" alt="Build status" /></a>
    <a href="https://search.maven.org/artifact/com.alecdorrington/hecate-core_3"><img src="https://img.shields.io/maven-central/v/com.alecdorrington/hecate-core_3.svg" alt="Maven Central" /></a>
    <a href="https://alecdorrington.com/Hecate"><img src="https://img.shields.io/badge/docs-latest-blue.svg" alt="Documentation" /></a>
  </span>

</div>

> [!WARNING]
> Hecate is in beta. It is young, it has one user, and anything may change between minor versions.

A library for user accounts, sign-in sessions, nestable user groups, and the permissions that people
and groups hold over whatever your application calls a resource.
It knows nothing of the application it serves: it opens no database, fixes no JDBC profile, and renders nothing.

Named for [Hecate](https://en.wikipedia.org/wiki/Hecate), keeper of keys and guardian of gates and crossroads.

## ⬇️ Installation

Add whichever halves you need to your `build.sbt`:

```scala
libraryDependencies += "com.alecdorrington" %% "hecate-server" % "0.1.0" // On the JVM.
libraryDependencies += "com.alecdorrington" %% "hecate-client" % "0.1.0" // In the browser.
```

Compiled with Scala `3.8.4`, with no intention to explicitly support older versions.

## 🏯 Layout

| Module | Platform | Contents |
|--------|----------|----------|
| [`hecate-core`](core)     | JVM + JS | The model, and the API endpoint definitions.      |
| [`hecate-server`](server) | JVM      | Password hashing, persistence, and the endpoints. |
| [`hecate-client`](client) | JS       | Headless browser-side state.                      |

A host application uses the server half, the client half, or both;
the shared half comes with either, so the two agree on the wire format by construction.

## Server

Wire it up by handing the library a database to use. It opens nothing itself:

```scala
import com.alecdorrington.hecate.server.*
import slick.jdbc.H2Profile

// Your database handle, implementing `def run[X](action: DBIO[X]): IO[X]`.
class MyDb(...) extends Transactor

val tables = AuthTables(H2Profile) // Or any other JDBC profile.
val auth   = AuthService(UserStore(tables, db))
val groups = GroupService(GroupStore(tables, db), auth)

// On startup, alongside your own schema creation:
db.run(tables.createIfNotExists)

// Serve them with the rest of your endpoints:
val endpoints = auth.api ++ groups.api ++ myOwnEndpoints
```

`AuthTables` is parameterised on the Slick profile, so the library is not bound to any
one database. Pass `prefix` if its tables need to sit in a namespace of their own.
That prefix becomes part of the table names, so it must be a constant of your own choosing
rather than anything a request carries.

The stores expect `READ COMMITTED` or stricter, and use `SELECT … FOR UPDATE` where the
profile has it, to serialise the changes that no table constraint can (see *Groups* below).
On SQLite, which has neither, one writer at a time makes the locks unnecessary.

### Securing your own endpoints

Build an endpoint on `AuthApi.secured` and give it `AuthService.require` as its security
logic. The `Caller` is then the first argument of the endpoint's logic: the signed-in `User`,
with the language their request asked to be answered in.

```scala
import com.alecdorrington.hecate.api.AuthApi

val myEndpoint = AuthApi.secured.get.in("api" / "things").out(jsonBody[List[Thing]])

myEndpoint
  .serverSecurityLogic(auth.require)
  .serverLogic(caller => _ => thingsOwnedBy(caller.id))
```

Nothing here throttles anything. Sign-in costs a PBKDF2 derivation whether the password is
right or wrong, which bounds how fast an attacker can guess, but a library that opens no
socket cannot rate-limit by address: put that in front of these endpoints yourself. Recovery
codes are the one exception, and deliberately so — each is about 49 bits of entropy, which
no amount of guessing gets through.

Sessions live in an HTTP-only, `SameSite=Strict` cookie, and expire in the store as well
as in the browser, so a leaked token cannot outlive its expiry. Nothing clears out
the rows of sessions that have expired, since an expired session is refused whether or
not its row is still there; schedule `UserStore.purgeExpired` if you would rather the
table did not keep them. Passwords are stored as
salted PBKDF2 hashes, compared in constant time; an unknown username is checked against a
decoy hash so that sign-in takes the same time whether or not the account exists.

Usernames are trimmed of surrounding space and then matched exactly: `Alice` and `alice`
are two accounts. If you want them to be one, fold the case yourself before registering
and before signing in.

Tune the session lifetime and the password rules with `AuthPolicy`:

```scala
AuthService(users, AuthPolicy(sessionSeconds = 3600, minPasswordLength = 12))
```

Passwords are derived with PBKDF2-HMAC-SHA256 at `AuthPolicy.hashingRounds` iterations,
which defaults to OWASP's current figure. Each stored hash carries the count it was
derived under, so raising it later leaves every stored password verifiable.

### Speaking the user's language

The library never writes a sentence of its own choosing. Every refusal is an `AuthRefusal`,
and a `Wording` turns one into a sentence; `Wording.english` is the only one the library
carries. To answer in more languages, implement `Wording` once per language and hand
`AuthService` and `GroupService` a function from the request's language to the wording it
should use:

```scala
AuthService(users, wording = locale => myStrings(locale))
GroupService(groups, auth, wording = locale => myStrings(locale))
```

The language reaches you as the value of a `language` cookie, which the client sets for its
whole origin: a language code (`de`) or a locale tag (`de-AT`), or `None` when the request
names none. Endpoints built on `AuthApi.secured` read it alongside the session cookie, and
`register`, `login` and `recover` read it too, so a refusal is worded for its reader even
before anyone is signed in. `Caller.locale` carries it into your own endpoints, for wording
your application's own refusals the same way. `AuthProblem.getMessage` stays English,
for logs.

### Groups

Groups are owned by the user who creates them, visible only to that owner, and nest to
arbitrary depth through `Group.parent`. Membership propagates *upwards*: a member of a
group is effectively a member of every group it is nested inside, so anything addressed
to a department also reaches the members of each team within it. `GroupStore.groupIdsOf`
returns exactly that set, and is the intended basis for "what may this user see?".

Deleting a group deletes every group beneath it. To delete whatever your application
attaches to a group in the same transaction, pass a cascade:

```scala
GroupStore(
  tables,
  db,
  principals =>
    val groups = principals.collect { case Principal.Group(id) => id }
    myTable.filter(_.groupId inSet groups).delete.map(_ => ()),
)
```

The cascade is handed `Principal`s rather than bare identifiers, because the same hook
serves account deletion, where the principal is the user themselves; a user and a group
may share an identifier, so match on the kind you mean. It runs before the groups
themselves are removed, so it may still join on them.

### Accounts

Users can change their password, recover a forgotten one, and delete their account.

**Changing a password** needs the current password. It signs out every other session the
user had and gives this one a fresh session, so anyone who knew the old password is
signed out along with them.

**Recovery** needs no email and no administrator. A signed-in user generates a set of ten
one-time recovery codes, after giving their password again, and writes them down; the
server keeps only their hashes and never shows them again. A user who forgets their
password gives their username, one unused code and a new password, and is signed in.
Each code works once, and generating a new set invalidates the old one. A failed
recovery never says whether the username or the code was wrong.

**Deleting an account** needs the password, and removes everything that belongs to the
user in one transaction: their sessions and recovery codes, the groups they own (with
those groups' members, invitations and grants), their memberships and invitations
elsewhere, the grants they hold, and whatever the host application attaches to them.
It is refused while the user, with the groups they own, is the only owner of some resource,
which would otherwise be left owned by nobody; they must first pass it on or delete it. Another
owner means another principal holding `Own`: a group counts, even one with no members
left in it, so this guarantees that something still owns the resource rather than that
somebody can still open it.
It is off unless the host application passes an `AccountStore`:

```scala
val groups   = GroupStore(tables, db, cascade)
val grants   = GrantStore(tables, db)
val accounts = AccountStore(tables, db, users, groups, grants, cascade)
val auth     = AuthService(users, accounts = Some(accounts))

// One cascade for both: the groups of a deleted subtree, or a deleted user.
def cascade(principals: Seq[Principal]): DBIO[Unit] = ...
```

Pass it only once the cascade removes everything of the host's that points at a user.
Until then the endpoint is not served, and `GET /api/auth/rules` reports
`accountDeletion: false` so that clients hide the option.

`GET /api/auth/rules` also reports the minimum password length, so that a form can state
the rule before a password is refused rather than after.

### Permissions

A grant gives a `Principal` (a `Person` or a `Group`) one level of `Access` over a
`Resource`: `View`, `Edit` or `Own`, each including those below it. Resources are named in
your application's terms, as a kind and an identifier, so the library never knows what they are.

```scala
val grants      = GrantStore(tables, db)
val permissions = Permissions(groups, grants)

// In the transaction that creates the thing, so that it is never left without an owner:
grants.grant(Grant(Resource("document", id), Principal.Person(creator), Access.Own))

permissions.access(user, Resource("document", id)) // The highest access reaching the user.
permissions.visible(user, "document", Access.View)  // Every document they may see.
```

A grant to a group reaches every member of it and of every group nested inside it, but never
the other way round. `GrantStore`'s writes are returned as actions rather than run, so that
they can join the transaction that creates or deletes the resource; lock the resource's own
row before granting or revoking, as nothing else can.

Two small types carry access to the client: `Permitted` pairs a value with the access its
reader holds over it, and `Gated` marks one part of a resource as shown, absent, or
withheld from this reader, which are never to be conflated.

### Whom a user may address

`GroupStore.addressable(user)` lists the principals a user may give something to (a document,
access): themselves, every group they own, and every member of those groups. Invitees
are excluded, as the relation must be one the recipient consented to and can end.
`GroupStore.mayAddress(user, principal)` answers the same question for one principal in
a single query.

## Client

`AuthState` and `GroupsState` are headless: they expose signals and commands, and your
application owns every pixel.

```scala
import com.alecdorrington.hecate.client.{AuthState, GroupsState}

val auth   = AuthState() // Or AuthState(myWording) to speak another language.
val groups = GroupsState(auth)

auth.signIn("alice", "hunter2222")
div(child <-- auth.user.map {
  case Some(user) => span(s"Signed in as ${ user.username }")
  case None       => signInForm(auth)
})
```

`AuthState.user` changes on startup, sign-in and sign-out, so other state can follow it to
refetch whatever belongs to the user. Until `AuthState.ready` is `true` nothing is known
yet, and a `None` user must not be read as "signed out". `GroupsState.forest` nests the
server's flat group list for rendering. Both states take a `Wording` for the few things they
say themselves; everything the server refuses arrives already worded.

## API

| Method   | Path                                    | Purpose                                   |
|----------|-----------------------------------------|-------------------------------------------|
| `POST`   | `/api/auth/register`                    | Create an account and sign in.            |
| `POST`   | `/api/auth/login`                       | Sign in.                                  |
| `POST`   | `/api/auth/logout`                      | Sign out.                                 |
| `GET`    | `/api/auth/me`                          | Identify the signed-in user.              |
| `GET`    | `/api/auth/rules`                       | Describe the rules for accounts.          |
| `PUT`    | `/api/auth/password`                    | Change your password.                     |
| `GET`    | `/api/auth/recovery-codes`              | Count your unused recovery codes.         |
| `POST`   | `/api/auth/recovery-codes`              | Generate new recovery codes.              |
| `POST`   | `/api/auth/recover`                     | Regain an account with a code.            |
| `POST`   | `/api/auth/account/delete`              | Delete your account.                      |
| `GET`    | `/api/groups`                           | List your groups, members and invitees.   |
| `GET`    | `/api/groups/mine`                      | List the groups you are a member of.      |
| `POST`   | `/api/groups`                           | Create a group.                           |
| `PUT`    | `/api/groups/{group}`                   | Rename or move a group.                   |
| `DELETE` | `/api/groups/{group}`                   | Delete a group and its subgroups.         |
| `POST`   | `/api/groups/{group}/invitations`       | Invite a user.                            |
| `DELETE` | `/api/groups/{group}/members/{user}`    | Remove a member, or cancel an invitation. |
| `DELETE` | `/api/groups/{group}/membership`        | Leave a group yourself.                   |
| `GET`    | `/api/invitations`                      | List the invitations sent to you.         |
| `POST`   | `/api/invitations/{invitation}/accept`  | Accept an invitation.                     |
| `POST`   | `/api/invitations/{invitation}/decline` | Decline an invitation.                    |

Nobody joins a group without consenting. An owner can only invite; the invited user sees
who is asking, and becomes a member when they accept. Until then the group reaches them in
no way: `groupIdsOf` counts memberships alone. Declining deletes the invitation, and leaving
a group just ends the membership; either way, the owner may invite the user again.

Changes to who is in or invited to a group lock that group's row first (`SELECT … FOR
UPDATE`), so a double-clicked invite or two tabs accepting at once cannot duplicate a row.
The lock is skipped on SQLite, which admits one writer at a time anyway.

Inviting a username that does not exist is reported as such. That discloses whether an
account exists, and is a deliberate trade so that someone inviting a list of names knows
which they typed wrongly.

## 🤝 Contributing

Hecate is developed as part of a larger private project, of which this repository is an automatically synchronised
mirror (by [GitHub Graph](https://github.com/SgtSwagrid/github-graph)), so changes made here directly would be overwritten.
Issues are very welcome; for anything more, please open an issue first.

## 👁️ See also

- [Eunomia](https://github.com/SgtSwagrid/Eunomia), a sibling, for filtering, ordering and paging lists.
- [Iris](https://github.com/SgtSwagrid/Iris), a sibling, a provider-agnostic client for large language models.
- This library was made using [Scala Library Template](https://github.com/SgtSwagrid/scala-library-template).
