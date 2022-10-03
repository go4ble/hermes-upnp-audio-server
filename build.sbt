import Dependencies._

ThisBuild / scalaVersion := "2.13.8"
ThisBuild / version := "0.1.0-SNAPSHOT"
ThisBuild / organization := "io.github.go4le"

val AkkaVersion = "2.6.20"
val AkkaHttpVersion = "10.2.10"

lazy val root = (project in file("."))
  .settings(
    name := "hermes-upnp-audio-server",
    libraryDependencies += scalaTest % Test,
    libraryDependencies += "com.typesafe.akka" %% "akka-actor-typed" % AkkaVersion,
    libraryDependencies += "com.typesafe.akka" %% "akka-stream-typed" % AkkaVersion,
    libraryDependencies += "com.typesafe.akka" %% "akka-http" % AkkaHttpVersion,
    libraryDependencies += "com.typesafe.akka" %% "akka-http-xml" % AkkaHttpVersion,
    libraryDependencies += "com.lightbend.akka" %% "akka-stream-alpakka-mqtt" % "4.0.0",
    libraryDependencies += "ch.qos.logback" % "logback-classic" % "1.4.1"
  )

// See https://www.scala-sbt.org/1.x/docs/Using-Sonatype.html for instructions on how to publish to Sonatype.
// ef0aa3ce-8cbf-498f-a396-a4dcb3054b6b
