ThisBuild / version := "0.1.0-SNAPSHOT"

ThisBuild / scalaVersion := "2.13.14"

lazy val root = (project in file("."))
  .settings(
    name := "ISCRacer-Server"
  )

resolvers += "jitpack" at "https://jitpack.io"

libraryDependencies ++= Seq(
  "com.typesafe.scala-logging" %% "scala-logging" % "3.9.5",
  "ch.qos.logback" % "logback-classic" % "1.5.18",
  "com.github.esotericsoftware" % "kryonet" % "03a135e203"
)