ThisBuild / description :=
  "User accounts, sessions, groups and permissions for full stack Scala websites."

ThisBuild / homepage := Some(uri("https://github.com/SgtSwagrid/Hecate"))

// Stated rather than read from the git remote, which is the host's when built there:
ThisBuild / scmInfo := Some(ScmInfo(
  uri("https://github.com/SgtSwagrid/Hecate"),
  "scm:git:https://github.com/SgtSwagrid/Hecate.git",
))

ThisBuild / organization         := "com.alecdorrington"
ThisBuild / organizationName     := "SgtSwagrid"
ThisBuild / organizationHomepage := Some(uri("https://github.com/SgtSwagrid"))

// Still in beta: anything may change between minor versions until 1.0.0.
ThisBuild / versionScheme := Some("early-semver")

ThisBuild / licenses :=
  List("MIT" -> uri("https://opensource.org/licenses/MIT"))

ThisBuild / developers := List(Developer(
  id = "SgtSwagrid",
  name = "Alec Dorrington",
  email = "alecdorrington@gmail.com",
  url = uri("https://github.com/SgtSwagrid"),
))
