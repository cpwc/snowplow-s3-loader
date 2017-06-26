 /*
 * Copyright (c) 2014-2015 Snowplow Analytics Ltd.
 * All rights reserved.
 *
 * This program is licensed to you under the Apache License Version 2.0,
 * and you may not use this file except in compliance with the Apache
 * License Version 2.0.
 * You may obtain a copy of the Apache License Version 2.0 at
 * http://www.apache.org/licenses/LICENSE-2.0.
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the Apache License Version 2.0 is distributed
 * on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied.
 *
 * See the Apache License Version 2.0 for the specific language
 * governing permissions and limitations there under.
 */

package com.snowplowanalytics.snowplow.storage.kinesis.s3.sinks

// Java
import java.nio.ByteBuffer

// Scala
import scala.util.Random

// Amazon
import com.amazonaws.services.kinesis.model._
import com.amazonaws.auth.AWSCredentialsProvider
import com.amazonaws.services.kinesis.AmazonKinesisClient
import com.amazonaws.services.kinesis.AmazonKinesis
import com.amazonaws.regions._

// Concurrent libraries
import scala.concurrent.{Future,Await,TimeoutException}
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._
import scala.util.{Success, Failure}

// Logging
import org.slf4j.LoggerFactory

// Tracker
import com.snowplowanalytics.snowplow.scalatracker.Tracker

/**
 * Kinesis Sink
 *
 * @param provider AWSCredentialsProvider
 * @param endpoint Kinesis stream endpoint
 * @param name Kinesis stream name
 * @param config Configuration for the Kinesis stream
 */
class KinesisSink(provider: AWSCredentialsProvider, endpoint: String, name: String, tracker: Option[Tracker])
  extends ISink {

  private lazy val log = LoggerFactory.getLogger(getClass())
  import log.{error, debug, info, trace}

  // Explicitly create a client so we can configure the end point
  val client = new AmazonKinesisClient(provider)
  client.setEndpoint(endpoint)

  if (!streamExists(name)) {
    throw new RuntimeException(s"Cannot write because stream $name doesn't exist or is not active")
  }

  /**
   * Checks if a stream exists.
   *
   * @param name Name of the stream to look for
   * @return Whether the stream both exists and is active
   */
  private def streamExists(name: String): Boolean = {
    val exists = try {
      val describeStreamResult = client.describeStream(name)
      describeStreamResult.getStreamDescription.getStreamStatus == "ACTIVE"
    } catch {
      case rnfe: ResourceNotFoundException => false
    }

    if (exists) {
      info(s"Stream $name exists and is active")
    } else {
      info(s"Stream $name doesn't exist or is not active")
    }

    exists
  }

  private def put(name: String, data: ByteBuffer, key: String): Future[PutRecordResult] = Future {
    val putRecordRequest = {
      val p = new PutRecordRequest()
      p.setStreamName(name)
      p.setData(data)
      p.setPartitionKey(key)
      p
    }
    client.putRecord(putRecordRequest)
  }

  /**
   * Write a record to the Kinesis stream
   *
   * @param output The string record to write
   * @param key A hash of the key determines to which shard the
   *            record is assigned. Defaults to a random string.
   * @param good Unused parameter which exists to extend ISink
   */
  def store(output: String, key: Option[String], good: Boolean): Unit =
    put(name, ByteBuffer.wrap(output.getBytes), key.getOrElse(Random.nextInt.toString)) onComplete {
      case Success(result) => {
        info(s"Writing successful")
        info(s"  + ShardId: ${result.getShardId}")
        info(s"  + SequenceNumber: ${result.getSequenceNumber}")
      }
      case Failure(f) => {
        error(s"Writing failed.")
        error(s"  + " + f.getMessage)
      }
    }
}
