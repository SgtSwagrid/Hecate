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
val links  = LinkService(LinkStore(tables), GroupStore(tables, db), GrantStore(tables, db), db, auth)

// On startup, alongside your own schema creation:
db.run(tables.createIfNotExists)

// Serve them with the rest of your endpoints:
val endpoints = auth.api ++ groups.api ++ links.api ++ myOwnEndpoints
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

Groups are owned by the user who creates them and nest to arbitrary depth through
`Group.parent`. Only the owner sees a group whole. Its members see it, its owner and one
another (`GET /api/groups/mine`, a `Membership` each), but not who is invited to it or asking
to join; anyone else sees a group only by name, and only if they are invited to it or may ask
to join it (see *Joining a group* below). Membership propagates *upwards*: a member of a
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

### Joining a group

Nobody joins a group without consenting, and nobody joins one without its owner's agreement
either. Whichever side agrees first, the other completes it:

- **The owner** joins their own group at once (`PUT /api/groups/{group}/membership`).
- **An invitation**: the owner invites a user by name, and the user accepts. The group
  reaches them in no way until then: `groupIdsOf` counts memberships alone.
- **A request**: a user asks to join a group they can see, and the owner admits or declines
  them. A user can see a group that is *public* (`Group.public`, set by its owner), and any
  group nested inside one they are a member of. `GET /api/groups/joinable` lists them.
- **An invite link**: the owner makes a link, and whoever follows it joins (see *Invite
  links* below).

Asking to join a group you are invited to accepts the invitation, and inviting someone who
has asked admits them, since both sides have then agreed. Declining either deletes it, and
either side may ask again; leaving a group just ends the membership.

Changes to who is in, invited to or asking to join a group lock that group's row first
(`SELECT … FOR UPDATE`), so a double-clicked invite or two tabs accepting at once cannot
duplicate a row. The lock is skipped on SQLite, which admits one writer at a time anyway.

Inviting a username that does not exist is reported as such. That discloses whether an
account exists, and is a deliberate trade so that someone inviting a list of names knows
which they typed wrongly.

### Invite links

An invite link is known by a short random code alone (`InviteCode`: five letters or digits,
at least one a digit so that a code never spells a word, read without regard to case, and never
chosen by anyone), so that a host can put it anywhere in
its own URLs, as short as `https://example.com/k3x9q`. It leads either to a group, which
following it joins, or to a resource, over which following it grants the access the link
carries, never lowering any the follower already holds. `LinkApi` shows where a link leads
(`GET /api/invite-links/{code}`), so that nobody follows one blind, and follows it
(`POST /api/invite-links/{code}`).

Each group and each resource has at most one link, which its owners may replace with a new
code, ending the old one, or turn off. A group's owner manages its link through `GroupApi`.
A resource's owners manage its link through your own endpoints, since only you know who may
share one: compose `LinkStore.ensure`, `renew` and `remove` into a transaction that locks the
resource's row, as for a grant. Tell `LinkService` what your resources are called, locking the
row in the same way, so that a link never grants access to something deleted meanwhile:

```scala
LinkService(links, groups, grants, db, auth, resources = {
  case Resource("document", id) => documents.filter(_.id === id).forUpdate.map(_.title).result.headOption
  case _                        => DBIO.successful(None)
})
```

`GrantStore.revokeAll`, which you already call when deleting a resource, turns its link off
too. Codes are short enough to type, which means they can be guessed: there are some fifty
million, so a host with many live links should limit how fast anyone may try them.

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
those groups' members, invitations, requests, links and grants), their memberships,
invitations and requests elsewhere, the invite links they made, the grants they hold, and whatever the host application attaches to them.
It is refused while the user, with the groups they own, is the only owner of some resource,
which would otherwise be left owned by nobody; they must first pass it on or delete it. The
host's cascade runs before that check, in the same transaction, so a resource it deletes with
the account (one nobody else has any use for, say), grants and all, is no reason to refuse,
and a refusal rolls the cascade back with everything else. Another
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

### Telling people what changed

Every service that changes something takes an `affected` hook, which it calls once the change is
committed with whom it concerns, so that a host with a live connection (a websocket, say) can tell
those people to read again. It never says what changed, only whose view of it did: whoever is told
asks again, through the endpoints that check what they may see, so telling them discloses nothing.

```scala
GroupService(groups, auth, affected = {
  case Affected.Groups(Audience.People(ids), _) => tellEach(ids)
  case Affected.Groups(Audience.Everyone, _)    => tellEveryone
  case Affected.Grants(resource)                => tellWhoeverSees(resource)
  case Affected.Account(user)                   => tellEach(Set(user))
})
```

A change to who is in a group, invited to it or asking to join it concerns the group's owner, its
members, who see one another, and that person; a change to the group itself (its name, nesting, visibility or existence)
concerns everyone who saw anything of it before or sees anything of it after, and everyone at all
when it was or is public. `Affected.Groups` also names every group whose members may have changed,
with those enclosing them, as anything the host addressed to one of them may now reach someone else.
Following an invite link to a resource changes the grants over it: the host alone knows who may see
it, and `Permissions.holders` names everyone a grant over it reaches. Signing out, a new password
or recovery codes, and a recovery concern the account's own sessions, which may be open elsewhere;
deleting an account concerns everyone, as it leaves every group and grant it held.

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

When your server says something changed (see *Telling people what changed*), call
`GroupsState.refresh()` to read the groups again, or `AuthState.refresh()` to read the account
again: it signs the user out here if their session ended elsewhere, and otherwise leaves them as
they were, recovery codes still on screen included.

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
| `GET`    | `/api/groups/mine`                      | List groups you are in, with members.     |
| `GET`    | `/api/groups/joinable`                  | List the groups you may ask to join.      |
| `POST`   | `/api/groups`                           | Create a group.                           |
| `PUT`    | `/api/groups/{group}`                   | Rename or move a group.                   |
| `DELETE` | `/api/groups/{group}`                   | Delete a group and its subgroups.         |
| `PUT`    | `/api/groups/{group}/public`            | Make a group public, or private again.    |
| `PUT`    | `/api/groups/{group}/invite-link`       | Give a group an invite link.              |
| `POST`   | `/api/groups/{group}/invite-link`       | Replace a group's invite link.            |
| `DELETE` | `/api/groups/{group}/invite-link`       | Turn off a group's invite link.           |
| `POST`   | `/api/groups/{group}/invitations`       | Invite a user.                            |
| `PUT`    | `/api/groups/{group}/members/{user}`    | Admit someone who asked to join.          |
| `DELETE` | `/api/groups/{group}/members/{user}`    | Remove a member, or cancel or decline.    |
| `PUT`    | `/api/groups/{group}/membership`        | Join a group you own.                     |
| `DELETE` | `/api/groups/{group}/membership`        | Leave a group yourself.                   |
| `PUT`    | `/api/groups/{group}/request`           | Ask to join a group.                      |
| `DELETE` | `/api/groups/{group}/request`           | Withdraw a request to join.               |
| `GET`    | `/api/invitations`                      | List the invitations sent to you.         |
| `POST`   | `/api/invitations/{invitation}/accept`  | Accept an invitation.                     |
| `POST`   | `/api/invitations/{invitation}/decline` | Decline an invitation.                    |
| `GET`    | `/api/invite-links/{code}`              | See where an invite link leads.           |
| `POST`   | `/api/invite-links/{code}`              | Follow an invite link.                    |

## 🤝 Contributing

Hecate is developed as part of a larger private project, of which this repository is an automatically synchronised
mirror (by [GitHub Graph](https://github.com/SgtSwagrid/github-graph)), so changes made here directly would be overwritten.
Issues are very welcome; for anything more, please open an issue first.

## 👁️ See also

- [Eunomia](https://github.com/SgtSwagrid/Eunomia), a sibling, for filtering, ordering and paging lists.
- [Iris](https://github.com/SgtSwagrid/Iris), a sibling, a provider-agnostic client for large language models.
- This library was made using [Scala Library Template](https://github.com/SgtSwagrid/scala-library-template).
