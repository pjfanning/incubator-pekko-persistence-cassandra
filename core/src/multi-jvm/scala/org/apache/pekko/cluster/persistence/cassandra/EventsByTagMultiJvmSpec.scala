/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * license agreements; and to You under the Apache License, version 2.0:
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * This file is part of the Apache Pekko project, which was derived from Akka.
 */

package org.apache.pekko.cluster.persistence.cassandra

import com.typesafe.config.ConfigFactory
import org.apache.pekko
import pekko.persistence.cassandra.query.scaladsl.CassandraReadJournal
import pekko.persistence.cassandra.testkit.CassandraLauncher
import pekko.persistence.journal.Tagged
import pekko.persistence.query.{ NoOffset, PersistenceQuery }
import pekko.remote.testkit.{ MultiNodeConfig, MultiNodeSpec }
import pekko.stream.ActorMaterializer
import pekko.stream.testkit.TestSubscriber
import pekko.stream.testkit.scaladsl.TestSink
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

import java.io.File

object EventsByTagMultiJvmSpec extends MultiNodeConfig {
  // No way to start and distribute the port so hard coding
  final val CassPort = 9142

  val node1 = role("node-1")
  val node2 = role("node-2")
  val node3 = role("node-3")

  val name = "EventsByTagMuliJvmSpec"

  commonConfig(ConfigFactory.parseString(s"""
      pekko {
        loglevel = INFO 
        actor.provider = cluster
        testconductor.barrier-timeout = 60 s
      }

      pekko.persistence.cassandra {
        journal {
          keyspace = $name
        }
        
        events-by-tag {
          bucket-size = Minute
        }
        
        snapshot {
          keyspace = $name
        }
      }
    """).withFallback(CassandraLifecycle.config))

}

class EventsByTagSpecMultiJvmNode1 extends EventsByTagMultiJvmSpec
class EventsByTagSpecMultiJvmNode2 extends EventsByTagMultiJvmSpec
class EventsByTagSpecMultiJvmNode3 extends EventsByTagMultiJvmSpec

abstract class EventsByTagMultiJvmSpec
    extends MultiNodeSpec(EventsByTagMultiJvmSpec)
    with MultiNodeClusterSpec
    with AnyWordSpecLike
    with Matchers {

  import EventsByTagMultiJvmSpec._

  val nrMessages = 10
  val readersPerNode = 10
  val nrWriterNodes = 2

  override def initialParticipants: Int = roles.size

  "EventsByTag" must {

    "init Cassandra" in {
      val CassHost = node(node1).address.host.get

      runOn(node1) {
        startCassandra(CassHost, CassPort, system.name)
        CassandraLifecycle.awaitPersistenceInit(system)
      }
      enterBarrier("cassandra-init")

      runOn(node2, node3) {
        CassandraLifecycle.awaitPersistenceInit(system)
      }
      enterBarrier("persistence-init")
      system.log.info("Cassandra started")
    }

    "init cluster" in {
      awaitClusterUp(node1, node2, node3)
    }

    "be readable across nodes" in {

      implicit val materializer = ActorMaterializer()(system)
      val queryJournal = PersistenceQuery(system).readJournalFor[CassandraReadJournal](CassandraReadJournal.Identifier)

      var readers: Seq[(Int, TestSubscriber.Probe[(String, Int)])] = Nil

      runOn(node2, node3) {
        readers = (0 until readersPerNode).map { i =>
          i ->
          queryJournal
            .eventsByTag("all", NoOffset)
            .map(e => (e.persistenceId, e.event.asInstanceOf[Int]))
            .runWith(TestSink.probe)
        }
      }
      enterBarrier("query-started")

      runOn(node1) {
        val ta = system.actorOf(TestActor.props("node1Pid"))
        (0 until nrMessages).foreach { i =>
          ta ! Tagged(i, Set("all"))
        }
      }
      runOn(node2) {
        val ta = system.actorOf(TestActor.props("node2Pid"))
        (0 until nrMessages).foreach { i =>
          ta ! Tagged(i, Set("all"))
        }
      }

      runOn(node2, node3) {
        // each event should come twice, from node1 and node2
        // pid to last msg
        var latestValues = Map.empty[(Int, String), Int].withDefault(_ => -1)

        (0 until (nrMessages * nrWriterNodes)).foreach { i =>
          readers.foreach { each =>
            val (readerNr, probe) = each
            val (pid, msg) = probe.requestNext()
            system.log.debug("Received {} from pid {}", msg, pid)
            withClue(s"Pid: [$pid] Latest values: $latestValues") {
              latestValues((readerNr, pid)) shouldEqual (msg - 1)
            }
            latestValues += (readerNr, pid) -> msg
          }

        }
      }

      enterBarrier("all-done")
    }
  }

  def startCassandra(
      host: String,
      port: Int,
      systemName: String,
      cassandraConfigResource: String = CassandraLauncher.DefaultTestConfigResource): Unit = {
    val cassandraDirectory = new File(s"target/$systemName-$port")
    CassandraLauncher.start(
      cassandraDirectory,
      configResource = cassandraConfigResource,
      clean = true,
      port = port,
      CassandraLauncher.classpathForResources("logback-test.xml"),
      Some(host))
  }

  def stopCassandra(): Unit = {
    CassandraLauncher.stop()
  }

}
