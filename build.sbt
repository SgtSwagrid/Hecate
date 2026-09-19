import IdeSettings.packagePrefix
import org.scalajs.sbtplugin.ScalaJSPlugin
import sbt._
import sbt.Keys._
import sbtunidoc.BaseUnidocPlugin.autoImport.*
import sbtunidoc.ScalaUnidocPlugin

// This build is developed as part of a larger private project,
// which includes it by reference and from which it is automatically synchronised.
// Every project is prefixed with the library's name, so that none clashes with a host's own.

val scala3 = "3.8.4"

ThisBuild / scalaVersion := scala3

ThisBuild / scalacOptions ++= Seq(
  "-explain",
  "-explain-types",
  "-explain-cyclic",
)

/** The base package prefix shared across all subprojects. */
val projectRoot = "com.alecdorrington.hecate"

/**
  * The model and the API endpoint definitions, shared by server and client.
  * Cross-compiled for JVM and JS.
  */
lazy val hecateCore = projectMatrix
  .in(file("core"))
  .settings(
    name          := "hecate-core",
    packagePrefix := projectRoot,

    // A matrix resolves its sources against the working directory, which is
    // not this build's own when a host includes it by reference:
    sourceDirectory := (ThisBuild / baseDirectory).value / "core" / "src",
    Dependencies.tapir,
    Dependencies.circe,
    Dependencies.munitCatsEffect,
  )
  .jvmPlatform(scalaVersions = Seq(scala3))
  .jsPlatform(scalaVersions = Seq(scala3))

/**
  * The server half: password hashing, persistence and the API endpoint
  * implementations. Storage is Slick over any JDBC profile, chosen by the host
  * application rather than fixed here.
  */
lazy val hecateServer = project
  .in(file("server"))
  .dependsOn(hecateCore.jvm(scala3))
  .settings(
    name          := "hecate-server",
    packagePrefix := s"$projectRoot.server",
    Dependencies.tapir,
    Dependencies.circe,
    Dependencies.catsEffect,
    Dependencies.database,
    Dependencies.munitCatsEffect,
  )

/**
  * The client half: the browser-side sign-in and group state, driving the
  * endpoints the server half implements. Headless, so that the host application
  * owns all rendering and styling.
  */
lazy val hecateClient = project
  .in(file("client"))
  .dependsOn(hecateCore.js(scala3))
  .enablePlugins(ScalaJSPlugin)
  .settings(
    name          := "hecate-client",
    packagePrefix := s"$projectRoot.client",
    Dependencies.scalajs,
    Dependencies.laminar,
    Dependencies.circe,
    Dependencies.munitCatsEffect,
  )

lazy val hecate = project
  .in(file("."))
  .enablePlugins(ScalaUnidocPlugin)
  .aggregate(
    (hecateCore.projectRefs ++
      Seq[ProjectReference](hecateServer, hecateClient)) *,
  )
  .settings(
    publish / skip := true,

    // Scaladoc is aggregated from the JVM side alone, as the JS side would
    // only document the shared sources a second time:
    ScalaUnidoc / unidoc / unidocProjectFilter :=
      inProjects(hecateCore.jvm(scala3), hecateServer),
    ScalaUnidoc / unidoc / scalacOptions ++= Seq("-project", "Hecate"),
  )
