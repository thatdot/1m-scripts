import akka.{Done, NotUsed}
import akka.util.ByteString
import akka.actor.ActorSystem
import akka.kafka._
import akka.kafka.scaladsl.Producer
import akka.stream.scaladsl._
import com.google.common.hash.BloomFilter
import com.google.common.hash.Funnel
import com.google.common.hash.Funnels
import com.google.common.hash.PrimitiveSink
import com.google.protobuf.DescriptorProtos.FileDescriptorSet
import com.google.protobuf.Descriptors.FileDescriptor
import com.google.protobuf.DynamicMessage
import process.schema
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.common.serialization.ByteArraySerializer
import org.apache.kafka.common.serialization.StringSerializer
import scalapb.GeneratedMessage
import scalapb.json4s.JsonFormat

import java.io.BufferedInputStream
import java.io.FileInputStream
import java.nio.file.Paths
import java.time.Instant
import java.util.concurrent.atomic.AtomicLong
import scala.collection.mutable
import scala.concurrent.{ExecutionContext, Future}
import scala.util.Random
import scala.util.Try

// Stores a (bounded) number of Ts, and can retrieve an arbitrary one, given a Random instance
// Implementations should use bounded memory
trait Sampleable[T] {
  def isEmpty: Boolean

  def add(x: T): Unit

  def sample(rand: Random): T
}
class RandomArrayDequeue[T](averageBranching: Int, maxElements: Int)
    extends Sampleable[T] {
  private val cache = mutable.ArrayDeque[T]()

  def isEmpty = cache.isEmpty

  def add(x: T): Unit = {
    if (cache.size >= maxElements) {
      cache.removeHead(resizeInternalRepr = false)
    }
    cache.addOne(x)
  }

  def sample(random: Random): T = {
    val willRemove =
      cache.size > maxElements || // remove if we need to free up the memory
        random.nextInt(
          averageBranching
        ) == 0 // ... or to keep the average children per parent around `averageBranching`
    if (willRemove) cache.remove(random.nextInt(cache.length))
    else cache(random.nextInt(cache.length))
  }
}

class GeneratorState(
    seed: Long,
    numberCustomers: Int,
    numberSensors: Int,
    rootProcessRarity: Int,
    numberProcesses: Long,
    averageBranching: Int,
    matchesPerThousandProcesses: Int
) {

  type CustomerId = String
  type SensorId = String
  type ProcessId = Long

  val random: Random = new Random(seed)

  private var generated = 0L
  private var sq1matches = 0L

  val browserNames = Array("Chrome", "Safari", "Firefox", "Explorer", "Edge", "Opera", "Brave")
  private val browsers: Map[String, AtomicLong] = Map(browserNames.toSeq.map(_ -> new AtomicLong(0)):_*)
  // gets a frozen copy of `browsers`
  def getBrowsers: Map[String, Long] = browsers.view.mapValues(_.get()).toMap

  val processes: Sampleable[(ProcessId, SensorId, CustomerId, Int)] =
    new RandomArrayDequeue[(ProcessId, SensorId, CustomerId, Int)](
      averageBranching,
      10000
    )

  val usedProcessIds: BloomFilter[Long] =
    BloomFilter.create(
      new Funnel[ProcessId] { // no-op funnel to convert `_ >: scala.Long` to `java.lang.Long`)
        private val underlying = Funnels.longFunnel()

        def funnel(from: ProcessId, into: PrimitiveSink): Unit =
          underlying.funnel(from, into)
      },
      numberProcesses - 1
    )

  // full set of customers (represented as string-formatted 1-indexed integers with no padding zeros)
  val customers: Vector[CustomerId] = Vector.tabulate(numberCustomers) { i => (i+1).toString }

  // full set of sensors
  val sensors: Vector[SensorId] = Vector.fill(numberSensors) {
    randomAlphanumericString(28)
  }

  private def randomAlphanumericString(length: Int): String =
    randomString("abcdefghijklmnopqrstuvwxyz0123456789", length)

  private def randomHexString(length: Int): String =
    randomString("0123456789abcdef", length)

  private def randomString(chars: String, length: Int): String = {
    val builder = new mutable.StringBuilder()
    val hex = "0123456789abcdef"
    for (_ <- 0 to length) {
      builder += hex.charAt(random.nextInt(16))
    }
    builder.result()
  }

  private def randomBrowser(): String = browserNames(random.nextInt(browserNames.length))

  private def customerId(forId: Long): String =
    customers(Math.floorMod(forId.toInt, numberCustomers))

  private def randomCustomerId(): String = customers(
    random.nextInt(numberCustomers)
  )

  private def sensorId(forId: Long): String =
    sensors(Math.floorMod(forId.toInt, numberSensors))

  private def randomSensorId(): String = sensors(random.nextInt(numberSensors))

  def randomFreshProcessId(): Long = {
    val candidate = random.nextLong()
    if (usedProcessIds.mightContain(candidate)) randomFreshProcessId()
    else candidate
  }

  def noParentProcessEvent(processId: ProcessId): schema.ProcessEvent = {
    val customerId = randomCustomerId()
    val sensorId = randomSensorId()
    schema.ProcessEvent(
      customerId,
      sensorId,
      processId,
      0,
      randomAlphanumericString(15) + ".txt",
      "command-line",
      "user_" + randomAlphanumericString(5),
      Instant.now().getEpochSecond(),
      randomHexString(64)
    )
  }

  def nextProcessEvent(): schema.ProcessEvent = {
    generated += 1
    val noParent = processes.isEmpty || random.nextInt(rootProcessRarity) == 0
    val processId = randomFreshProcessId()
    val (process, depth) = if (noParent) {
      (noParentProcessEvent(processId), 1)
    } else {
      val (parentId, sensorId, customerId, parentDepth) =
        processes.sample(random)
      if (
        parentDepth >= 3 && sq1matches * 1000 / generated > matchesPerThousandProcesses
      ) {
        (noParentProcessEvent(processId), 1)
      } else {
        if (parentDepth >= 3) {
          // record that we'd have a match for in SQ1 -- we don't want too many of these for the sake of realism and
          // performance
          sq1matches += 1L
        }

        val process = schema.ProcessEvent(
          customerId,
          sensorId,
          processId,
          parentId,
          randomAlphanumericString(15) + ".txt",
          "command-line",
          "user_" + randomAlphanumericString(5),
          Instant.now().getEpochSecond(),
          randomHexString(64)
        )
        (process, parentDepth + 1)
      }
    }
    usedProcessIds.put(process.processId)
    processes.add(
      process.processId,
      process.sensorId,
      process.customerId,
      depth
    )
    process
  }

  var eventCounter = 0L

  def nextFooEvent(): schema.FooEvent = {
    eventCounter += 1
    schema.FooEvent(
      customerId(eventCounter / numberSensors),
      sensorId(eventCounter),
      randomAlphanumericString(10),
      randomAlphanumericString(10)
    )
  }

  def nextBenchmarkEvent(): schema.BenchmarkEvent = {
    eventCounter += 1
    val browser = randomBrowser()
    browsers(browser).getAndIncrement()
    schema.BenchmarkEvent(
      customerId(random.nextLong()),
      sensorId(random.nextLong()),
      randomAlphanumericString(10),
      randomAlphanumericString(10),
      randomAlphanumericString(10),
      randomAlphanumericString(10),
      randomAlphanumericString(10),
      randomAlphanumericString(10),
      randomAlphanumericString(10),
      randomAlphanumericString(10),
      randomAlphanumericString(10),
      randomAlphanumericString(10),
      randomAlphanumericString(10),
      browser + "-" + randomAlphanumericString(5),
      eventCounter
    )

  }
}

object Main extends App {
  implicit val as = ActorSystem()

  val howManyEvents =
    Try(System.getenv("NUMBER_OF_EVENTS").toLong).getOrElse(100 * 1000 * 1000L)
  val bootstrapServers = System.getenv("KAFKA")
  val numberOfCustomers =
    Try(System.getenv("NUMBER_OF_CUSTOMERS").toInt).getOrElse(5000)
  val numberOfSensors =
    Try(System.getenv("NUMBER_OF_SENSORS").toInt).getOrElse(1000 * 1000)
  val rootProcessRarity =
    Try(System.getenv("ROOT_PROCESS_RARITY").toInt).getOrElse(100)
  val averageBranching =
    Try(System.getenv("AVERAGE_BRANCHING").toInt).getOrElse(3)
  val dataset =
    Option(System.getenv("DATASET")).getOrElse("process")
  val topicName = Option(System.getenv("TOPIC"))
    .getOrElse(dataset + "Events")
  val matchesPerThousandProcesses =
    Try(System.getenv("MATCHES_PER_THOUSAND").toInt).getOrElse(4)
  val output = Option(System.getenv("OUTPUT")).getOrElse("kafka")
  val partitionKey = Option(System.getenv("PARTITION_KEY")).getOrElse("sensorId")

  val generatorState = new GeneratorState(
    seed = 0,
    numberCustomers = numberOfCustomers,
    numberSensors = numberOfSensors,
    rootProcessRarity = rootProcessRarity,
    numberProcesses = howManyEvents,
    averageBranching = averageBranching,
    matchesPerThousandProcesses = matchesPerThousandProcesses
  )

  def takeAndCount[A]: Flow[A, A, NotUsed] = Flow[A]
    .take(howManyEvents)
    .statefulMapConcat { () =>
      var ctr = 0

      var last_time = Instant.now().toEpochMilli();
      rec => {
        ctr += 1
        if (ctr % (1000 * 10) == 0) {
          var time = Instant.now().toEpochMilli()
          var dt = time - last_time;
          last_time = time;
          println(
            s"${ctr / 1000}k/${howManyEvents / 1000}k (dt=${dt}ms)"
          )
        }
        Seq(rec)
      }
    }

  def writeSink[A <: GeneratedMessage](eventPartition: A => String): Sink[A, NotUsed] = output match {
    case "kafka" =>
      val producerSettings =
        ProducerSettings(as, new StringSerializer, new ByteArraySerializer)
          .withBootstrapServers(bootstrapServers)

      Producer
        .plainSink(producerSettings)
        .contramap { (event: A) =>
          new ProducerRecord[String, Array[Byte]](
            topicName,
            eventPartition(event),
            event.toByteArray
          )
        }
        .mapMaterializedValue(_ => NotUsed)

    case "json-file" =>
      val jsonFormattedPrinter = new scalapb.json4s.Printer(
        includingDefaultValueFields = true,
        preservingProtoFieldNames = true,
        formattingLongAsNumber = false
      )
      FileIO
        .toPath(Paths.get(s"$topicName.jsonl"))
        .contramap((event: A) => ByteString(jsonFormattedPrinter.print(event) + "\n"))
        .mapMaterializedValue(_ => NotUsed)
  }

  println(
    s"TOPIC=${topicName} NUMBER_OF_EVENTS=${howManyEvents} KAFKA=${bootstrapServers} NUMBER_OF_CUSTOMERS=${numberOfCustomers} NUMBER_OF_SENSORS=${numberOfSensors} ROOT_PROCESS_RARITY=${rootProcessRarity} DATASET=${dataset} AVERAGE_BRANCHING=${averageBranching} MATCHES_PER_THOUSAND=${matchesPerThousandProcesses} OUTPUT=${output} PARTITION_KEY=${partitionKey}"
  )


  val runningStream: Future[Done] = dataset match {
    case "process" =>
      val key: schema.ProcessEvent => String = partitionKey match {
        case "sensorId" => _.sensorId
        case "customerId" => _.customerId
        case other => throw new RuntimeException(s"Invalid PARTITION_KEY $other")
      }
      Source
        .unfold(generatorState)(gs => Some((gs, gs.nextProcessEvent())))
        .via(takeAndCount[schema.ProcessEvent])
        .watchTermination()(Keep.right)
        .to(writeSink[schema.ProcessEvent](key))
        .run()

    case "foo" =>
      val key: schema.FooEvent => String = partitionKey match {
        case "sensorId" => _.sensorId
        case "customerId" => _.customerId
        case other => throw new RuntimeException(s"Invalid PARTITION_KEY $other")
      }
      Source
        .unfold(generatorState)(gs => Some((gs, gs.nextFooEvent())))
        .via(takeAndCount[schema.FooEvent])
        .watchTermination()(Keep.right)
        .to(writeSink[schema.FooEvent](key))
        .run()

    case "benchmark" =>
      val key: schema.BenchmarkEvent => String = partitionKey match {
        case "sensorId" => _.sensorId
        case "customerId" => _.customerId
        case other => throw new RuntimeException(s"Invalid PARTITION_KEY $other")
      }
      Source
        .unfold(generatorState)(gs => Some((gs, gs.nextBenchmarkEvent())))
        .via(takeAndCount[schema.BenchmarkEvent])
        .watchTermination()(Keep.right)
        .to(writeSink[schema.BenchmarkEvent](key))
        .run()


    case other =>
      throw new RuntimeException(s"Invalid DATASET $other")
  }

  runningStream.onComplete(_ => {
    println("complete")
    if (dataset == "benchmark") {
      println(s"Generated browser counts (WARNING may be approximate):\n${generatorState.getBrowsers}")
    }
    as.terminate()
  })(ExecutionContext.Implicits.global)
}
