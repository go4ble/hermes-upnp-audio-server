import Dependencies._

ThisBuild / scalaVersion := "2.13.8"
ThisBuild / version := "0.1.0-SNAPSHOT"
ThisBuild / organization := "io.github.go4le"
ThisBuild / scalacOptions += "-Xlint"

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
    libraryDependencies += "ch.qos.logback" % "logback-classic" % "1.4.3",
    libraryDependencies += "org.eclipse.paho" % "org.eclipse.paho.client.mqttv3" % "1.2.5"
  )

(Compile / compile) := ((Compile / compile) dependsOn scalafmtCheckAll).value

enablePlugins(JavaAppPackaging)
dockerBaseImage := "eclipse-temurin"
dockerRepository := Some("ghcr.io/go4ble")
dockerUpdateLatest := true
