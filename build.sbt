ThisBuild / organization := "org.chiparts"
ThisBuild / version := "0.1.0-SNAPSHOT"
ThisBuild / scalaVersion := "2.13.18"

val spinalVersion = "1.15.0"

lazy val difftest = (project in file(".")).settings(
  name := "difftest",
  libraryDependencies ++= Seq(
    "com.github.spinalhdl" %% "spinalhdl-core" % spinalVersion,
    "com.github.spinalhdl" %% "spinalhdl-lib" % spinalVersion,
    compilerPlugin("com.github.spinalhdl" %% "spinalhdl-idsl-plugin" % spinalVersion),
    "org.scalatest" %% "scalatest" % "3.2.19" % Test
  ),
  scalacOptions ++= Seq("-deprecation", "-feature", "-unchecked"),
  fork := true,
  Test / parallelExecution := false
)
