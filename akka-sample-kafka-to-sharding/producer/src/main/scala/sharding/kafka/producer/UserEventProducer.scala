package sharding.kafka.producer

import java.nio.charset.StandardCharsets

import akka.Done
import akka.actor.ActorSystem
import akka.event.Logging
import akka.kafka.ProducerSettings
import akka.kafka.scaladsl.Producer
import akka.stream.scaladsl.Source
import com.typesafe.config.ConfigFactory
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.common.serialization.ByteArraySerializer
import org.apache.kafka.common.serialization.StringSerializer
import org.apache.kafka.common.utils.Utils
import sample.sharding.kafka.serialization.user_events.UserPurchaseProto

import scala.concurrent.Future
import scala.concurrent.duration._
import scala.util.Random

object UserEventProducer extends App {

  implicit val system: ActorSystem = ActorSystem(
    "UserEventProducer",
    ConfigFactory.parseString("""
      akka.actor.provider = "local" 
     """.stripMargin).withFallback(ConfigFactory.load()).resolve())

  val log = Logging(system, "UserEventProducer")

  val config = system.settings.config.getConfig("akka.kafka.producer")

  val producerConfig = ProducerConfig(system.settings.config.getConfig("kafka-to-sharding-producer"))

  val producerSettings: ProducerSettings[String, Array[Byte]] =
    ProducerSettings(config, new StringSerializer, new ByteArraySerializer)
      .withBootstrapServers(producerConfig.bootstrapServers)

  val nrUsers = 100
  val maxPrice = 10000
  val maxQuatity = 5
  val products = List("cat t-shirt", "akka t-shirt", "skis", "climbing shoes", "rope")

  val done: Future[Done] =
    Source
      .tick(1000.millis, 1000.millis, "tick")
      .map(_ => {
        val randomEntityId = Random.nextInt(nrUsers).toString
        val price = Random.nextInt(maxPrice)
        val quantity = Random.nextInt(maxQuatity)
        val product = products(Random.nextInt(products.size))
        val message = UserPurchaseProto(randomEntityId, product, quantity, price).toByteArray
        log.info("Sending message to user {}", randomEntityId)
        producerRecord(randomEntityId, message)

      })
      .runWith(Producer.plainSink(producerSettings))

  def producerRecord(entityId: String, message: Array[Byte]): ProducerRecord[String, Array[Byte]] = {
    producerConfig.partitioning match {
      case Default =>
        // rely on the default kafka partitioner to hash the key and distribute among shards
        // the logic of the default partitionor must be replicated in MessageExtractor entityId -> shardId function
        new ProducerRecord[String, Array[Byte]](producerConfig.topic, entityId, message)
      case Explicit =>
        // this logic MUST be replicated in the MessageExtractor entityId -> shardId function!
        val shardAndPartition = (Utils.toPositive(Utils.murmur2(entityId.getBytes(StandardCharsets.US_ASCII))) % producerConfig.nrPartitions)
        new ProducerRecord[String, Array[Byte]](producerConfig.topic, shardAndPartition, entityId, message)
    }
  }

}
