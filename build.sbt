ThisBuild / version := "0.1.0-SNAPSHOT"

ThisBuild / scalaVersion := "2.13.14"

enablePlugins(AssemblyPlugin)

lazy val root = (project in file("."))
  .settings(
    name := "ISCRacer-Server",
    Compile / mainClass := Some("Server")
  )

resolvers += "jitpack" at "https://jitpack.io"

libraryDependencies ++= Seq(
  "co.fs2" %% "fs2-io" % "3.11.0",
  "co.fs2" %% "fs2-core" % "3.11.0"
)