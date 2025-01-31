/*
 * Copyright 2022 Spotify AB.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package com.spotify.scio.examples

import com.spotify.scio.ContextAndArgs
import com.spotify.scio.util.ScioUtil
import org.slf4j.LoggerFactory

import java.util.concurrent.TimeUnit
import scala.concurrent.duration.Duration
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.reflect.ClassTag
import scala.util.{Failure, Success, Try}

object RunPreReleaseIT {

  implicit private val ec: ExecutionContext = ExecutionContext.global

  private val defaultArgs = Array(
    "--runner=DataflowRunner",
    "--project=data-integration-test",
    "--region=us-central1",
    "--tempLocation=gs://dataflow-tmp-us-central1/gha"
  )

  private val log = LoggerFactory.getLogger(getClass)

  def main(cmdLineArgs: Array[String]): Unit = {
    val (_, args) = ContextAndArgs(cmdLineArgs)
    val runId = args("runId")

    try {
      Await.result(
        Future
          .sequence(
            parquet(runId) ++ avro(runId) ++ smb(runId) ++ bigquery(runId)
          )
          .map(_ => log.info("All Dataflow jobs ran successfully.")),
        Duration(1, TimeUnit.HOURS)
      )
    } catch {
      case (t: Throwable) =>
        throw new RuntimeException("At least one Dataflow job failed", t)
    }
  }

  private def avro(runId: String): List[Future[Unit]] = {
    import com.spotify.scio.examples.extra.AvroExample

    val out1 = gcsPath[AvroExample.type]("specificOut", runId)
    val out2 = gcsPath[AvroExample.type]("specificIn", runId)

    List(
      Future
        .successful(log.info("Starting Avro IO tests... "))
        .flatMap(_ => invokeJob[AvroExample.type]("--method=specificOut", s"--output=$out1"))
        .flatMap(_ =>
          invokeJob[AvroExample.type]("--method=specificIn", s"--input=$out1/*", s"--output=$out2")
        )
    )
  }

  private def parquet(runId: String): List[Future[Unit]] = {
    import com.spotify.scio.examples.extra.ParquetExample

    val out1 = gcsPath[ParquetExample.type]("avroOut", runId)
    val out2 = gcsPath[ParquetExample.type]("typedIn", runId)
    val out3 = gcsPath[ParquetExample.type]("avroSpecificIn", runId)
    val out4 = gcsPath[ParquetExample.type]("avroGenericIn", runId)
    val out5 = gcsPath[ParquetExample.type]("exampleOut", runId)
    val out6 = gcsPath[ParquetExample.type]("exampleIn", runId)

    val start = Future
      .successful(log.info("Starting Parquet IO tests... "))

    val avroWrite =
      start.flatMap(_ => invokeJob[ParquetExample.type]("--method=avroOut", s"--output=$out1"))
    val exampleWrite =
      start.flatMap(_ => invokeJob[ParquetExample.type]("--method=exampleOut", s"--output=$out5"))

    List(
      avroWrite.flatMap(_ =>
        invokeJob[ParquetExample.type]("--method=typedIn", s"--input=$out1/*", s"--output=$out2")
      ),
      avroWrite.flatMap(_ =>
        invokeJob[ParquetExample.type](
          "--method=avroSpecificIn",
          s"--input=$out1/*",
          s"--output=$out3"
        )
      ),
      avroWrite.flatMap(_ =>
        invokeJob[ParquetExample.type](
          "--method=avroGenericIn",
          s"--input=$out1/*",
          s"--output=$out4"
        )
      ),
      exampleWrite.flatMap(_ =>
        invokeJob[ParquetExample.type](
          "--method=exampleIn",
          s"--input=$out5/*",
          s"--output=$out6"
        )
      )
    )
  }

  private def smb(runId: String): List[Future[Unit]] = {
    import com.spotify.scio.examples.extra.{
      SortMergeBucketWriteExample,
      SortMergeBucketJoinExample,
      SortMergeBucketTransformExample
    }
    val out1 = gcsPath[SortMergeBucketWriteExample.type]("users", runId)
    val out2 = gcsPath[SortMergeBucketWriteExample.type]("accounts", runId)

    val write = Future
      .successful(log.info("Starting SMB IO tests... "))
      .flatMap(_ =>
        invokeJob[SortMergeBucketWriteExample.type](s"--users=$out1", s"--accounts=$out2")
      )

    List(
      write.flatMap(_ =>
        invokeJob[SortMergeBucketJoinExample.type](
          s"--users=$out1",
          s"--accounts=$out2",
          s"--output=${gcsPath[SortMergeBucketJoinExample.type]("join", runId)}"
        )
      ),
      write.flatMap(_ =>
        invokeJob[SortMergeBucketTransformExample.type](
          s"--users=$out1",
          s"--accounts=$out2",
          s"--output=${gcsPath[SortMergeBucketTransformExample.type]("transform", runId)}"
        )
      )
    )
  }

  private def bigquery(runId: String): List[Future[Unit]] = {
    import com.spotify.scio.examples.extra.{TypedBigQueryTornadoes, TypedStorageBigQueryTornadoes}

    val start = Future
      .successful(log.info("Starting BigQuery tests... "))

    List(
      start.flatMap(_ =>
        invokeJob[TypedStorageBigQueryTornadoes.type](
          s"--output=data-integration-test:gha_it_us.typed_storage"
        )
      ),
      start.flatMap(_ =>
        invokeJob[TypedBigQueryTornadoes.type](
          s"--output=data-integration-test:gha_it_us.typed_row"
        )
      )
    )
  }

  private def invokeJob[T: ClassTag](args: String*): Future[Unit] =
    Future {
      val cls = ScioUtil.classOf[T]
      val jobObjName = cls.getName.replaceAll("\\$$", "")
      log.info(s"Running Dataflow job ${cls.getName}...")
      Try(
        Class
          .forName(jobObjName)
          .getMethod("main", classOf[Array[String]])
          .invoke(null, defaultArgs ++ Array(args: _*))
      ) match {
        case Success(_) => log.info(s"Dataflow job ${cls.getName} ran successfully.")
        case Failure(e) =>
          throw new RuntimeException(s"Dataflow job ${cls.getName} failed with ${e.getClass}", e)
      }
    }

  private def gcsPath[T: ClassTag](methodName: String, runId: String): String =
    s"gs://data-integration-test-us/${ScioUtil.classOf[T].getSimpleName}/$methodName/$runId"
}
