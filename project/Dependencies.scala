import sbt.*
import sbt.Keys.*

/** External library dependencies. */
object Dependencies:

  /** The version to use for each dependency. */
  object V:

    val tapir           = "1.13.25"
    val circe           = "0.14.16"
    val catsEffect      = "3.7.0"
    val slick           = "3.6.1"
    val h2              = "2.3.232"
    val scalajs         = "2.8.1"
    val laminar         = "17.0.0"
    val laminext        = "0.17.0"
    val munitCatsEffect = "2.2.0"
    val sttpClient      = "3.11.0"

  /** Library dependencies associated with Tapir, for defining API endpoints. */
  lazy val tapir = libraryDependencies ++= Seq(
    "com.softwaremill.sttp.tapir" %% "tapir-core"       % V.tapir,
    "com.softwaremill.sttp.tapir" %% "tapir-json-circe" % V.tapir,
  )

  /**
    * Tapir's stub interpreter, which serves endpoints in memory, for testing
    * what a request to one actually answers without opening a socket.
    */
  lazy val tapirStub = libraryDependencies ++= Seq(
    "com.softwaremill.sttp.tapir" %% "tapir-sttp-stub-server" % V.tapir % Test,
    "com.softwaremill.sttp.client3" %% "cats" % V.sttpClient % Test,
  )

  /** Library dependencies associated with Circe, for JSON parsing. */
  lazy val circe = libraryDependencies ++= Seq(
    "io.circe" %% "circe-core"    % V.circe,
    "io.circe" %% "circe-generic" % V.circe,
    "io.circe" %% "circe-parser"  % V.circe,
  )

  /** Library dependencies associated with Cats Effect, for side effects. */
  lazy val catsEffect = libraryDependencies ++=
    Seq("org.typelevel" %% "cats-effect" % V.catsEffect)

  /**
    * Library dependencies for database access, using
    * [Slick](https://scala-slick.org/) over whichever JDBC profile the host
    * application chooses. [H2](https://www.h2database.com/) is for tests alone.
    */
  lazy val database = libraryDependencies ++= Seq(
    "com.typesafe.slick" %% "slick" % V.slick,
    "com.h2database"      % "h2"    % V.h2 % Test,
  )

  /** Library dependencies associated with Scala.js, for JS interop. */
  lazy val scalajs = libraryDependencies ++=
    Seq("org.scala-js" %% "scalajs-dom" % V.scalajs)

  /** Library dependencies associated with Laminar, for client-side rendering. */
  lazy val laminar = libraryDependencies ++= Seq(
    "com.raquo"   %% "laminar"     % V.laminar,
    "io.laminext" %% "core"        % V.laminext,
    "io.laminext" %% "fetch"       % V.laminext,
    "io.laminext" %% "fetch-circe" % V.laminext,
  )

  /** Library dependencies for testing with MUnit and Cats Effect. */
  lazy val munitCatsEffect = libraryDependencies ++=
    Seq("org.typelevel" %% "munit-cats-effect" % V.munitCatsEffect % Test)
