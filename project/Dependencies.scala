/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * license agreements; and to You under the Apache License, version 2.0:
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * This file is part of the Apache Pekko project, which was derived from pekko.
 */

import sbt._
import Keys._

object Dependencies {
  // keep in sync with .github/workflows/unit-tests.yml
  val scala212Version = "2.12.18"
  val scala213Version = "2.13.11"
  val scala3Version = "3.1.2" // not yet enabled - missing pekko-http/pekko-management Scala 3 artifacts
  val scalaVersions = Seq(scala212Version, scala213Version)

  val pekkoVersion = System.getProperty("override.pekko.version", "0.0.0+26656-898c6970-SNAPSHOT")
  val pekkoVersionInDocs = "current"
  val cassandraVersionInDocs = "4.0"

  // Should be sync with the version of the driver in Pekko Connectors Cassandra
  val driverVersion = "4.15.0"
  val driverVersionInDocs = "4.14"

  val pekkoConnectorsVersion = "0.0.0+85-a82f3c3c-SNAPSHOT"
  val pekkoConnectorsVersionInDocs = "current"
  // for example
  val pekkoManagementVersion = "0.0.0+724-41d3b29c-SNAPSHOT"

  val logback = "ch.qos.logback" % "logback-classic" % "1.2.10"

  val reconcilerDependencies = Seq(
    "org.apache.pekko" %% "pekko-actor-testkit-typed" % pekkoVersion % Test,
    "org.apache.pekko" %% "pekko-stream-testkit" % pekkoVersion % Test)

  val pekkoTestDeps = Seq(
    "org.apache.pekko" %% "pekko-persistence",
    "org.apache.pekko" %% "pekko-persistence-typed",
    "org.apache.pekko" %% "pekko-persistence-query",
    "org.apache.pekko" %% "pekko-cluster-typed",
    "org.apache.pekko" %% "pekko-actor-testkit-typed",
    "org.apache.pekko" %% "pekko-persistence-tck",
    "org.apache.pekko" %% "pekko-stream-testkit",
    "org.apache.pekko" %% "pekko-multi-node-testkit",
    "org.apache.pekko" %% "pekko-cluster-sharding")

  val pekkoPersistenceCassandraDependencies = Seq(
    "org.apache.pekko" %% "pekko-connectors-cassandra" % pekkoConnectorsVersion,
    "org.apache.pekko" %% "pekko-persistence" % pekkoVersion,
    "org.apache.pekko" %% "pekko-persistence-query" % pekkoVersion,
    "org.apache.pekko" %% "pekko-stream" % pekkoVersion,
    "org.apache.pekko" %% "pekko-cluster-tools" % pekkoVersion,
    "com.datastax.oss" % "java-driver-core" % driverVersion,
    "org.scala-lang.modules" %% "scala-collection-compat" % "2.7.0",
    logback % Test,
    "org.scalatest" %% "scalatest" % "3.2.14" % Test,
    "org.pegdown" % "pegdown" % "1.6.0" % Test,
    "org.osgi" % "org.osgi.core" % "5.0.0" % Provided) ++ pekkoTestDeps.map(_ % pekkoVersion % Test)

  val exampleDependencies = Seq(
    logback,
    "org.apache.pekko" %% "pekko-persistence-typed" % pekkoVersion,
    "org.apache.pekko" %% "pekko-discovery" % pekkoVersion,
    "org.apache.pekko" %% "pekko-serialization-jackson" % pekkoVersion,
    "org.apache.pekko" %% "pekko-cluster-sharding-typed" % pekkoVersion,
    "org.apache.pekko" %% "pekko-management" % pekkoManagementVersion,
    "org.apache.pekko" %% "pekko-management-cluster-bootstrap" % pekkoManagementVersion,
    "org.apache.pekko" %% "pekko-management-cluster-http" % pekkoManagementVersion,
    "org.apache.pekko" %% "pekko-discovery-kubernetes-api" % pekkoManagementVersion,
    "org.hdrhistogram" % "HdrHistogram" % "2.1.12")

  val dseTestDependencies = Seq(
    "com.datastax.dse" % "dse-java-driver-core" % "2.3.0" % Test,
    "org.apache.pekko" %% "pekko-persistence-tck" % pekkoVersion % Test,
    "org.apache.pekko" %% "pekko-actor-testkit-typed" % pekkoVersion % Test,
    "org.apache.pekko" %% "pekko-stream-testkit" % pekkoVersion % Test,
    logback % Test)
}
