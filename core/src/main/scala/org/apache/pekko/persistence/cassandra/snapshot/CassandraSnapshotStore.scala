/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * license agreements; and to You under the Apache License, version 2.0:
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * This file is part of the Apache Pekko project, which was derived from Akka.
 */

/*
 * Copyright (C) 2016-2020 Lightbend Inc. <https://www.lightbend.com>
 */

package org.apache.pekko.persistence.cassandra.snapshot

import com.datastax.oss.driver.api.core.cql._
import com.datastax.oss.protocol.internal.util.Bytes
import com.typesafe.config.Config
import org.apache.pekko
import pekko.{ Done, NotUsed }
import pekko.actor._
import pekko.annotation.InternalApi
import pekko.dispatch.ExecutionContexts
import pekko.event.Logging
import pekko.pattern.pipe
import pekko.persistence._
import pekko.persistence.cassandra._
import pekko.persistence.serialization.Snapshot
import pekko.persistence.snapshot.SnapshotStore
import pekko.serialization.{ AsyncSerializer, Serialization, SerializationExtension, Serializers }
import pekko.stream.connectors.cassandra.scaladsl.{ CassandraSession, CassandraSessionRegistry }
import pekko.stream.scaladsl.{ Sink, Source }
import pekko.util.{ unused, OptionVal }
import pekko.util.FutureConverters._

import java.lang.{ Long => JLong }
import java.nio.ByteBuffer
import scala.collection.immutable
import scala.concurrent.{ ExecutionContext, Future }
import scala.util.control.NonFatal
import scala.util.{ Failure, Success }

/**
 * INTERNAL API
 */
@InternalApi private[pekko] class CassandraSnapshotStore(@unused cfg: Config, cfgPath: String)
    extends SnapshotStore
    with ActorLogging {

  import CassandraSnapshotStore._
  implicit val ec: ExecutionContext = context.dispatcher
  implicit val sys: ActorSystem = context.system

  // shared config is one level above the journal specific
  private val sharedConfigPath = cfgPath.replaceAll("""\.snapshot""", "")
  private val sharedConfig = context.system.settings.config.getConfig(sharedConfigPath)
  private val settings = new PluginSettings(context.system, sharedConfig)
  private val snapshotSettings = settings.snapshotSettings
  private val serialization = SerializationExtension(context.system)
  private val snapshotSerialization = new SnapshotSerialization(context.system)
  private val statements = new CassandraStatements(settings)
  import statements.snapshotStatements._

  private val someMaxLoadAttempts = Some(snapshotSettings.maxLoadAttempts)
  private val session: CassandraSession = CassandraSessionRegistry(context.system)
    .sessionFor(sharedConfigPath, ses => statements.executeAllCreateKeyspaceAndTables(ses, log))

  private def preparedWriteSnapshot =
    session.prepare(writeSnapshot(withMeta = false))
  private def preparedWriteSnapshotWithMeta =
    session.prepare(writeSnapshot(withMeta = true))
  private def preparedSelectSnapshot =
    session.prepare(selectSnapshot)
  private def preparedSelectSnapshotMetadata: Future[PreparedStatement] =
    session.prepare(selectSnapshotMetadata(limit = None))
  private def preparedSelectSnapshotMetadataWithMaxLoadAttemptsLimit: Future[PreparedStatement] =
    session.prepare(selectSnapshotMetadata(limit = Some(snapshotSettings.maxLoadAttempts)))

  override def preStart(): Unit =
    // eager initialization, but not from constructor
    self ! CassandraSnapshotStore.Init

  override def receivePluginInternal: Receive = {
    case CassandraSnapshotStore.Init =>
      log.debug("Initializing")
      // try initialize early, to be prepared for first real request
      preparedWriteSnapshot
      preparedWriteSnapshotWithMeta
      preparedDeleteSnapshot
      session.serverMetaData.foreach { meta =>
        if (!meta.isVersion2)
          preparedDeleteAllSnapshotsForPidAndSequenceNrBetween
      }
      preparedSelectSnapshot
      preparedSelectSnapshotMetadata
      preparedSelectSnapshotMetadataWithMaxLoadAttemptsLimit
      log.debug("Initialized")

    case DeleteAllSnapshots(persistenceId) =>
      val result: Future[Done] =
        deleteAsync(persistenceId, SnapshotSelectionCriteria(maxSequenceNr = Long.MaxValue)).map(_ => Done)
      result.pipeTo(sender())
  }

  override def loadAsync(
      persistenceId: String,
      criteria: SnapshotSelectionCriteria): Future[Option[SelectedSnapshot]] = {
    log.debug("loadAsync [{}] [{}]", persistenceId, criteria)
    // The normal case is that timestamp is not specified (Long.MaxValue) in the criteria and then we can
    // use a select stmt with LIMIT if maxLoadAttempts, otherwise the result is iterated and
    // non-matching timestamps are discarded.
    val snapshotMetaPs =
      if (criteria.maxTimestamp == Long.MaxValue)
        preparedSelectSnapshotMetadataWithMaxLoadAttemptsLimit
      else preparedSelectSnapshotMetadata

    for {
      p <- snapshotMetaPs
      mds <- metadata(p, persistenceId, criteria, someMaxLoadAttempts)
      res <- loadNAsync(mds)
    } yield res
  }

  private def loadNAsync(metadata: immutable.Seq[SnapshotMetadata]): Future[Option[SelectedSnapshot]] = metadata match {
    case Seq() => Future.successful(None) // no snapshots stored
    case md +: mds =>
      load1Async(md)
        .map {
          case DeserializedSnapshot(payload, OptionVal.Some(snapshotMeta)) =>
            Some(SelectedSnapshot(md.withMetadata(snapshotMeta), payload))
          case DeserializedSnapshot(payload, OptionVal.None) =>
            Some(SelectedSnapshot(md, payload))
        }
        .recoverWith {
          case _: NoSuchElementException if metadata.size == 1 =>
            // Thrown load1Async when snapshot couldn't be found, which can happen since metadata and the
            // actual snapshot might not be replicated at exactly same time.
            // Treat this as if there were no snapshots.
            Future.successful(None)
          case e =>
            if (mds.isEmpty) {
              log.warning(
                s"Failed to load snapshot [$md] ({} of {}), last attempt. Caused by: [{}: {}]",
                snapshotSettings.maxLoadAttempts,
                snapshotSettings.maxLoadAttempts,
                e.getClass.getName,
                e.getMessage)
              Future.failed(e) // all attempts failed
            } else {
              log.warning(
                s"Failed to load snapshot [$md] ({} of {}), trying older one. Caused by: [{}: {}]",
                snapshotSettings.maxLoadAttempts - mds.size,
                snapshotSettings.maxLoadAttempts,
                e.getClass.getName,
                e.getMessage)
              loadNAsync(mds) // try older snapshot
            }
        }
  }

  private def load1Async(metadata: SnapshotMetadata): Future[DeserializedSnapshot] = {
    val boundSelectSnapshot = preparedSelectSnapshot.map(
      _.bind(metadata.persistenceId, metadata.sequenceNr: JLong).setExecutionProfileName(snapshotSettings.readProfile))
    boundSelectSnapshot.flatMap(session.selectOne).flatMap {
      case None =>
        // Can happen since metadata and the actual snapshot might not be replicated at exactly same time.
        // Handled by loadNAsync.
        throw new NoSuchElementException(
          s"No snapshot for persistenceId [${metadata.persistenceId}] " +
          s"with with sequenceNr [${metadata.sequenceNr}]")
      case Some(row) =>
        row.getByteBuffer("snapshot") match {
          case null =>
            snapshotSerialization.deserializeSnapshot(row)
          case bytes =>
            // for backwards compatibility
            val payload = serialization.deserialize(Bytes.getArray(bytes), classOf[Snapshot]).get.data
            Future.successful(DeserializedSnapshot(payload, OptionVal.None))
        }
    }
  }

  override def saveAsync(metadata: SnapshotMetadata, snapshot: Any): Future[Unit] =
    snapshotSerialization.serialize(snapshot, metadata.metadata).flatMap { ser =>
      // using two separate statements with or without the meta data columns because
      // then users doesn't have to alter table and add the new columns if they don't use
      // the meta data feature
      val stmt =
        if (ser.meta.isDefined) preparedWriteSnapshotWithMeta
        else preparedWriteSnapshot

      stmt.flatMap { ps =>
        val bound = CassandraSnapshotStore.prepareSnapshotWrite(ps, metadata, ser)
        session.executeWrite(bound.setExecutionProfileName(snapshotSettings.writeProfile)).map(_ => ())
      }
    }

  /**
   * Plugin API: deletes all snapshots matching `criteria`. This call is protected with a circuit-breaker.
   *
   * @param persistenceId id of the persistent actor.
   * @param criteria selection criteria for deleting. If no timestamp constraints are specified this routine
   * @note Due to the limitations of Cassandra deletion requests, this routine makes an initial query in order to obtain the
   * records matching the criteria which are then deleted in a batch deletion. Improvements in Cassandra v3.0+ mean a single
   * range deletion on the sequence number is used instead, except if timestamp constraints are specified, which still
   * requires the original two step routine.
   */
  override def deleteAsync(persistenceId: String, criteria: SnapshotSelectionCriteria): Future[Unit] = {
    session.serverMetaData.flatMap { meta =>
      if (meta.isVersion2
        || settings.cosmosDb
        || 0L < criteria.minTimestamp
        || criteria.maxTimestamp < SnapshotSelectionCriteria.latest().maxTimestamp) {
        preparedSelectSnapshotMetadata.flatMap { snapshotMetaPs =>
          // this meta query gets slower than slower if snapshots are deleted without a criteria.minSequenceNr as
          // all previous tombstones are scanned in the meta data query
          metadata(snapshotMetaPs, persistenceId, criteria, limit = None).flatMap {
            mds: immutable.Seq[SnapshotMetadata] =>
              val boundStatementBatches = mds
                .map(md =>
                  preparedDeleteSnapshot.map(_.bind(md.persistenceId, md.sequenceNr: JLong)
                    .setExecutionProfileName(snapshotSettings.writeProfile)))
                .grouped(0xFFFF - 1)
              if (boundStatementBatches.nonEmpty) {
                Future
                  .sequence(
                    boundStatementBatches.map(boundStatements =>
                      Future
                        .sequence(boundStatements)
                        .flatMap(stmts => executeBatch(batch => stmts.foreach(batch.addStatement)))))
                  .map(_ => ())
              } else {
                FutureUnit
              }
          }
        }
      } else {
        val boundDeleteSnapshot = preparedDeleteAllSnapshotsForPidAndSequenceNrBetween.map(
          _.bind(persistenceId, criteria.minSequenceNr: JLong, criteria.maxSequenceNr: JLong)
            .setExecutionProfileName(snapshotSettings.writeProfile))
        boundDeleteSnapshot.flatMap(session.executeWrite(_)).map(_ => ())
      }
    }
  }

  def executeBatch(body: BatchStatementBuilder => Unit): Future[Unit] = {
    val batch =
      new BatchStatementBuilder(BatchType.UNLOGGED).setExecutionProfileName(snapshotSettings.writeProfile)
    body(batch)
    session.underlying().flatMap(_.executeAsync(batch.build()).asScala).map(_ => ())
  }

  private def metadata(
      snapshotMetaPs: PreparedStatement,
      persistenceId: String,
      criteria: SnapshotSelectionCriteria,
      limit: Option[Int]): Future[immutable.Seq[SnapshotMetadata]] = {
    val boundStmt = snapshotMetaPs
      .bind(persistenceId, criteria.maxSequenceNr: JLong, criteria.minSequenceNr: JLong)
      .setExecutionProfileName(snapshotSettings.readProfile)
    log.debug("Executing metadata query")
    val source: Source[SnapshotMetadata, NotUsed] = session
      .select(boundStmt)
      .map(row =>
        SnapshotMetadata(row.getString("persistence_id"), row.getLong("sequence_nr"), row.getLong("timestamp")))
      .dropWhile(_.timestamp > criteria.maxTimestamp)

    limit match {
      case Some(n) => source.take(n.toLong).runWith(Sink.seq)
      case None    => source.runWith(Sink.seq)
    }
  }

  def preparedDeleteSnapshot: Future[PreparedStatement] =
    session.prepare(deleteSnapshot)

  def preparedDeleteAllSnapshotsForPidAndSequenceNrBetween: Future[PreparedStatement] =
    session.prepare(deleteAllSnapshotForPersistenceIdAndSequenceNrBetween)

  def deleteAsync(metadata: SnapshotMetadata): Future[Unit] = {
    val boundDeleteSnapshot = preparedDeleteSnapshot.map(
      _.bind(metadata.persistenceId, metadata.sequenceNr: JLong).setExecutionProfileName(snapshotSettings.writeProfile))
    boundDeleteSnapshot.flatMap(session.executeWrite(_)).map(_ => ())
  }

}

/**
 * INTERNAL API
 */
@InternalApi private[pekko] object CassandraSnapshotStore {
  private case object Init

  sealed trait CleanupCommand
  final case class DeleteAllSnapshots(persistenceId: String) extends CleanupCommand

  final case class Serialized(serialized: ByteBuffer, serManifest: String, serId: Int, meta: Option[SerializedMeta])

  final case class SerializedMeta(serialized: ByteBuffer, serManifest: String, serId: Int)

  final case class DeserializedSnapshot(payload: Any, meta: OptionVal[Any])

  /**
   * INTERNAL API
   */
  @InternalApi
  private[pekko] class SnapshotSerialization(system: ActorSystem)(implicit val ec: ExecutionContext) {

    private val log = Logging(system, this.getClass)

    private val serialization = SerializationExtension(system)

    // cache to avoid repeated check via ColumnDefinitions
    @volatile private var _hasMetaColumns: Option[Boolean] = None

    def hasMetaColumns(row: Row): Boolean = _hasMetaColumns match {
      case Some(b) => b
      case None =>
        val b = row.getColumnDefinitions.contains("meta")
        _hasMetaColumns = Some(b)
        b
    }

    def serialize(payload: Any, meta: Option[Any]): Future[Serialized] =
      try {
        def serializeMeta(): Option[SerializedMeta] =
          meta.map { m =>
            val m2 = m.asInstanceOf[AnyRef]
            val serializer = serialization.findSerializerFor(m2)
            val serManifest = Serializers.manifestFor(serializer, m2)
            val metaBuf = ByteBuffer.wrap(serialization.serialize(m2).get)
            SerializedMeta(metaBuf, serManifest, serializer.identifier)
          }

        val p: AnyRef = payload.asInstanceOf[AnyRef]
        val serializer = serialization.findSerializerFor(p)
        val serManifest = Serializers.manifestFor(serializer, p)
        serializer match {
          case asyncSer: AsyncSerializer =>
            Serialization.withTransportInformation(system.asInstanceOf[ExtendedActorSystem]) { () =>
              asyncSer.toBinaryAsync(p).map { bytes =>
                val serPayload = ByteBuffer.wrap(bytes)
                Serialized(serPayload, serManifest, serializer.identifier, serializeMeta())
              }
            }
          case _ =>
            Future {
              // Serialization.serialize adds transport info
              val serPayload = ByteBuffer.wrap(serialization.serialize(p).get)
              Serialized(serPayload, serManifest, serializer.identifier, serializeMeta())
            }
        }

      } catch {
        case NonFatal(e) => Future.failed(e)
      }

    def deserializeSnapshot(row: Row): Future[DeserializedSnapshot] =
      try {

        def meta: OptionVal[AnyRef] =
          if (hasMetaColumns(row)) {
            row.getByteBuffer("meta") match {
              case null =>
                OptionVal.None // no meta data
              case metaBytes =>
                // has meta data, wrap in EventWithMetaData
                val metaSerId = row.getInt("meta_ser_id")
                val metaSerManifest = row.getString("meta_ser_manifest")
                serialization.deserialize(Bytes.getArray(metaBytes), metaSerId, metaSerManifest) match {
                  case Success(m) => OptionVal.Some(m)
                  case Failure(ex) =>
                    log.warning(
                      "Deserialization of snapshot metadata failed (pid: [{}], seq_nr: [{}], meta_ser_id: [{}], meta_ser_manifest: [{}], ignoring metadata content. Exception: {}",
                      Array(
                        row.getString("persistence_id"),
                        row.getLong("sequence_nr"),
                        metaSerId,
                        metaSerManifest,
                        ex.toString))
                    OptionVal.None
                }
            }
          } else {
            // for backwards compatibility, when table was not altered, meta columns not added
            OptionVal.None // no meta data
          }

        val bytes = Bytes.getArray(row.getByteBuffer("snapshot_data"))
        val serId = row.getInt("ser_id")
        val manifest = row.getString("ser_manifest")
        (serialization.serializerByIdentity.get(serId) match {
          case Some(asyncSerializer: AsyncSerializer) =>
            Serialization.withTransportInformation(system.asInstanceOf[ExtendedActorSystem]) { () =>
              asyncSerializer.fromBinaryAsync(bytes, manifest)
            }

          case _ =>
            Future.successful {
              // Serialization.deserialize adds transport info
              serialization.deserialize(bytes, serId, manifest).get
            }
        }).map(payload => DeserializedSnapshot(payload, meta))(ExecutionContexts.parasitic)

      } catch {
        case NonFatal(e) => Future.failed(e)
      }
  }

  /**
   * INTERNAL API
   */
  private[pekko] def prepareSnapshotWrite(
      ps: PreparedStatement,
      metadata: SnapshotMetadata,
      ser: Serialized): BoundStatement = {
    val bs = ps
      .bind()
      .setString("persistence_id", metadata.persistenceId)
      .setLong("sequence_nr", metadata.sequenceNr)
      .setLong("timestamp", metadata.timestamp)
      .setInt("ser_id", ser.serId)
      .setString("ser_manifest", ser.serManifest)
      .setByteBuffer("snapshot_data", ser.serialized)

    // meta data, if any
    ser.meta match {
      case Some(meta) =>
        bs.setInt("meta_ser_id", meta.serId)
          .setString("meta_ser_manifest", meta.serManifest)
          .setByteBuffer("meta", meta.serialized)
      case None =>
        bs
    }
  }
}
