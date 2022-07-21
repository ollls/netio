import Dependencies._

ThisBuild / scalaVersion     := "3.1.3"
ThisBuild / version          := "0.1.0-SNAPSHOT"
ThisBuild / organization     := "com.example"
ThisBuild / organizationName := "example"

lazy val root = (project in file("."))
  .settings(
    name := "iorun",
    libraryDependencies += scalaTest % Test,
    libraryDependencies += "org.typelevel" %% "cats-effect" % "3.3.14",
    libraryDependencies += "org.typelevel" %% "munit-cats-effect-3" % "1.0.6" % Test,
    libraryDependencies += "co.fs2" %% "fs2-core" % "3.2.7",
  )

  scalacOptions ++= Seq(
  "-Xfatal-warnings",
  "-deprecation", 
)

// See https://www.scala-sbt.org/1.x/docs/Using-Sonatype.html for instructions on how to publish to Sonatype.


