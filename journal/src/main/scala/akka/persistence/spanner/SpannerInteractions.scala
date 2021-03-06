/*
 * Copyright (C) 2020 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.persistence.spanner

import java.util.Base64

import akka.actor.ActorSystem
import akka.annotation.InternalApi
import akka.event.Logging
import akka.persistence.PersistentRepr
import akka.persistence.spanner.SpannerInteractions.SerializedWrite
import akka.persistence.spanner.internal.SpannerGrpcClient
import akka.serialization.Serialization
import com.google.protobuf.struct.Value.Kind.StringValue
import com.google.protobuf.struct.{ListValue, Struct, Value}
import com.google.spanner.v1.{Mutation, Type, TypeCode}

import scala.concurrent.{ExecutionContext, Future}

/**
 * INTERNAL API
 */
@InternalApi
private[spanner] object Schema {
  object Journal {
    val PersistenceId = "persistence_id" -> Type(TypeCode.STRING)
    val SeqNr = "sequence_nr" -> Type(TypeCode.INT64)
    val Event = "event" -> Type(TypeCode.BYTES)
    val SerId = "ser_id" -> Type(TypeCode.INT64)
    val SerManifest = "ser_manifest" -> Type(TypeCode.STRING)
    val WriteTime = "write_time" -> Type(TypeCode.TIMESTAMP)
    val WriterUUID = "writer_uuid" -> Type(TypeCode.STRING)
    val Tags = "tags" -> Type(TypeCode.ARRAY)

    val Columns = List(PersistenceId, SeqNr, Event, SerId, SerManifest, WriteTime, WriterUUID, Tags).map(_._1)
  }

  val ReplayTypes = Map(
    "persistence_id" -> Type(TypeCode.STRING),
    "from_sequence_nr" -> Type(TypeCode.INT64),
    "to_sequence_nr" -> Type(TypeCode.INT64),
    "max" -> Type(TypeCode.INT64)
  )

  val DeleteStatementTypes: Map[String, Type] = Map(
    "persistence_id" -> Type(TypeCode.STRING),
    "sequence_nr" -> Type(TypeCode.INT64)
  )
}

/**
 * INTERNAL API
 */
@InternalApi
private[spanner] object SpannerInteractions {
  case class SerializedWrite(
      persistenceId: String,
      sequenceNr: Long,
      payload: String,
      serId: Long,
      serManifest: String,
      writerUuid: String,
      tags: Set[String]
  )
}

/**
 * INTERNAL API
 *
 * Class for doing spanner interaction outside of an actor to avoid mistakes
 * in future callbacks
 */
@InternalApi
private[spanner] class SpannerInteractions(spannerGrpcClient: SpannerGrpcClient, journalSettings: SpannerSettings)(
    implicit ec: ExecutionContext,
    system: ActorSystem
) {
  import Schema._

  val log = Logging(system, classOf[SpannerInteractions])

  val HighestDeleteSelectSql =
    s"SELECT deleted_to FROM ${journalSettings.deletionsTable} WHERE persistence_id = @persistence_id"

  val HighestSequenceNrSql =
    s"SELECT sequence_nr FROM ${journalSettings.table} WHERE persistence_id = @persistence_id AND sequence_nr >= @sequence_nr ORDER BY sequence_nr DESC LIMIT 1"

  val ReplaySql =
    s"SELECT ${Journal.Columns.mkString(",")} FROM ${journalSettings.table} WHERE persistence_id = @persistence_id AND sequence_nr >= @from_sequence_Nr AND sequence_nr <= @to_sequence_nr ORDER BY sequence_nr limit @max"

  val SqlDeleteInsertToDeletions =
    s"INSERT INTO ${journalSettings.deletionsTable}(persistence_id, deleted_to) VALUES (@persistence_id, @sequence_nr)"

  val SqlDelete =
    s"DELETE FROM ${journalSettings.table} where persistence_id = @persistence_id AND sequence_nr <= @sequence_nr"

  def writeEvents(events: Seq[SerializedWrite]): Future[Unit] = {
    val mutations = events.map { sw =>
      val serializedTags: List[Value] = sw.tags.map(s => Value(StringValue(s))).toList
      Mutation(
        Mutation.Operation.Insert(
          Mutation.Write(
            journalSettings.table,
            Journal.Columns,
            List(
              ListValue(
                List(
                  Value(StringValue(sw.persistenceId)),
                  Value(StringValue(sw.sequenceNr.toString)),
                  Value(StringValue(sw.payload)),
                  Value(StringValue(sw.serId.toString)),
                  Value(StringValue(sw.serManifest)),
                  // special value for a timestamp that gets the write timestamp
                  Value(StringValue("spanner.commit_timestamp()")),
                  Value(StringValue(sw.writerUuid)),
                  Value(Value.Kind.ListValue(ListValue(serializedTags)))
                )
              )
            )
          )
        )
      )
    }

    spannerGrpcClient.write(mutations)
  }

  def findHighestDeletedTo(persistenceId: String): Future[Long] =
    spannerGrpcClient
      .executeQuery(
        HighestDeleteSelectSql,
        Struct(
          Map(
            Journal.PersistenceId._1 -> Value(StringValue(persistenceId))
          )
        ),
        Map(Journal.PersistenceId)
      )
      .map { resultSet =>
        if (resultSet.rows.isEmpty) 0L
        else resultSet.rows.head.values.head.getStringValue.toLong
      }

  def readHighestSequenceNr(persistenceId: String, fromSequenceNr: Long): Future[Long] = {
    val maxDeletedTo: Future[Long] = findHighestDeletedTo(persistenceId)
    val maxSequenceNr: Future[Long] = spannerGrpcClient
      .executeQuery(
        HighestSequenceNrSql,
        Struct(
          Map(
            Journal.PersistenceId._1 -> Value(StringValue(persistenceId)),
            Journal.SeqNr._1 -> Value(StringValue(fromSequenceNr.toString))
          )
        ),
        Map(Journal.PersistenceId, Journal.SeqNr)
      )
      .map(
        resultSet =>
          resultSet.rows.size match {
            case 0 =>
              log.info("No rows for persistence id [{}], using fromSequenceNr [{}]", persistenceId, fromSequenceNr)
              fromSequenceNr
            case 1 =>
              val sequenceNr = resultSet.rows.head.values.head.getStringValue.toLong
              log.info("Single row. {}", sequenceNr)
              sequenceNr
            case _ => throw new RuntimeException("More than one row returned from a limit 1 query. " + resultSet)
          }
      )

    for {
      deletedTo <- maxDeletedTo
      max <- maxSequenceNr
    } yield {
      log.debug("Max deleted to [{}] max sequence nr [{}]", deletedTo, max)
      math.max(deletedTo, max)
    }
  }

  def deleteMessagesTo(persistenceId: String, toSequenceNr: Long): Future[Unit] = {
    def params(to: Long) =
      Struct(
        Map(
          "persistence_id" -> Value(StringValue(persistenceId)),
          "sequence_nr" -> Value(StringValue(to.toString))
        )
      )
    for {
      highestDeletedTo <- findHighestDeletedTo(persistenceId) // user may have passed in a smaller value than previously deleted
      toDeleteTo <- {
        if (toSequenceNr == Long.MaxValue) { // special to delete all but don't set max deleted to it
          readHighestSequenceNr(persistenceId, highestDeletedTo)
        } else {
          Future.successful(math.max(highestDeletedTo, toSequenceNr))
        }
      }
      _ <- spannerGrpcClient.executeBatchDml(
        List(
          (SqlDelete, params(toDeleteTo), DeleteStatementTypes),
          (SqlDeleteInsertToDeletions, params(toDeleteTo), DeleteStatementTypes)
        )
      )
    } yield ()
  }

  def streamJournal(
      serialization: Serialization,
      persistenceId: String,
      fromSequenceNr: Long,
      toSequenceNr: Long,
      max: Long
  )(replay: PersistentRepr => Unit): Future[Unit] = {
    def replayRow(values: Seq[Value]): Unit = {
      val persistenceId = values.head.getStringValue
      val sequenceNr = values(1).getStringValue.toLong
      val payloadAsString = values(2).getStringValue
      val serId = values(3).getStringValue.toInt
      val serManifest = values(4).getStringValue
      val writerUuid = values(6).getStringValue

      val payloadAsBytes = Base64.getDecoder.decode(payloadAsString)
      val payload = serialization.deserialize(payloadAsBytes, serId, serManifest).get
      val repr = PersistentRepr(
        payload,
        sequenceNr,
        persistenceId,
        writerUuid = writerUuid
      )

      log.debug("replaying {}", repr)
      replay(repr)
    }

    spannerGrpcClient
      .streamingQuery(
        ReplaySql,
        params = Struct(
          fields = Map(
            Journal.PersistenceId._1 -> Value(StringValue(persistenceId)),
            "from_sequence_nr" -> Value(StringValue(fromSequenceNr.toString)),
            "to_sequence_nr" -> Value(StringValue(toSequenceNr.toString)),
            "max" -> Value(StringValue(max.toString))
          )
        ),
        paramTypes = Schema.ReplayTypes
      )
      .runForeach(row => replayRow(row))
      .map(_ => ())
  }
}
