package hermesUpnpAudioServer

import akka.Done
import akka.actor.typed.scaladsl.AskPattern.Askable
import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.{ActorRef, ActorSystem, Scheduler}
import akka.stream.alpakka.mqtt.scaladsl.{MqttSink, MqttSource}
import akka.stream.alpakka.mqtt.{MqttConnectionSettings, MqttMessage, MqttQoS, MqttSubscriptions}
import akka.util.{ByteString, Timeout}
import org.eclipse.paho.client.mqttv3.MqttClient
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence

import java.net.URL
import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.util.{Failure, Success, Try}

object App extends scala.App {
  val MediaRendererDeviceType = "urn:schemas-upnp-org:device:MediaRenderer:1"
  val AVTransportServiceType = "urn:schemas-upnp-org:service:AVTransport:1"
  val SetAVTransportURIAction = "SetAVTransportURI"
  val PlayAction = "Play"

  sealed trait AppMessage
  private final case class StreamCompleted(status: Try[Done]) extends AppMessage

  val actorSystem = ActorSystem(
    Behaviors.setup[AppMessage] { context =>
      implicit val ec: ExecutionContext = context.executionContext
      implicit val system: ActorSystem[_] = context.system
      implicit val scheduler: Scheduler = system.scheduler
      implicit val timeout: Timeout = 5.seconds

      // TODO children monitoring
      val audioServer = context.spawn(AudioServerBehavior(), "AudioServer")
      val deviceManager = context.spawn(DeviceManagerBehavior(), "DeviceManager")

      val mqttBaseClientId = MqttClient.generateClientId()
      val mqttSettings = MqttConnectionSettings(
        broker = "tcp://localhost:1883",
        clientId = "",
        new MemoryPersistence
      )

      val sites = Map(
        "raspi02" -> new URL("http://10.0.0.31:1400/xml/device_description.xml")
      )
      sites.foreach { case (siteId, deviceLocation) =>
        deviceManager ! DeviceManagerBehavior.AddDeviceMessage(siteId, deviceLocation, MediaRendererDeviceType, None) // TODO events
      }

      type Ctx = (String, String)
      val mqttSource = MqttSource.atMostOnce(
        settings = mqttSettings.withClientId(mqttBaseClientId + "_source"),
        subscriptions = MqttSubscriptions(PlayBytesTopic.topic, MqttQoS.atLeastOnce),
        bufferSize = 1
      ) // .asSourceWithContext[Ctx](mqttMessage => PlayBytesTopic.unapply(mqttMessage.topic))

      val mqttSink = MqttSink(
        connectionSettings = mqttSettings.withClientId(mqttBaseClientId + "_sink"),
        defaultQos = MqttQoS.atLeastOnce
      )

//      val publishAudioFlow = ActorFlow.askWithContext[MqttMessage, AudioServerBehavior.AudioServerMessage, URL, Ctx](audioServer) { (mqttMessage, replyTo) =>
//        val PlayBytesTopic(siteId, requestId) = mqttMessage.topic
//        AudioServerBehavior.PublishAudioMessage(siteId, requestId, mqttMessage.payload.toArray, replyTo)
//      }

      val streamStatus = mqttSource
        .filter(_.topic match {
          case PlayBytesTopic(siteId, _) => sites contains siteId
          case _                         => false
        })
//        .asSourceWithContext[Ctx](mqttMessage => PlayBytesTopic.unapply(mqttMessage.topic).get)
        .mapAsync(1) { mqttMessage =>
          val PlayBytesTopic(siteId, requestId) = mqttMessage.topic
          for {
            url <- audioServer.ask(AudioServerBehavior.PublishAudioMessage(siteId, requestId, mqttMessage.payload.toArray, _))
            setUriProperties = Map("InstanceID" -> Some("0"), "CurrentURI" -> Some(url.toString), "CurrentURIMetaData" -> None)
            setUriMessage = DeviceBehavior.ActionRequestMessage(AVTransportServiceType, SetAVTransportURIAction, setUriProperties, _)
            setUriResponse <- deviceManager.ask((ref: ActorRef[DeviceBehavior.ActionResponse]) => DeviceManagerBehavior.SendMessage(siteId, setUriMessage(ref)))
            if setUriResponse.properties.isSuccess
            playProperties = Map("InstanceID" -> Some("0"), "Speed" -> Some("1"))
            playMessage = DeviceBehavior.ActionRequestMessage(AVTransportServiceType, PlayAction, playProperties, _)
            playResponse <- deviceManager.ask((ref: ActorRef[DeviceBehavior.ActionResponse]) => DeviceManagerBehavior.SendMessage(siteId, playMessage(ref)))
            if playResponse.properties.isSuccess
            _ <- Future { Thread.sleep(2500) } // TODO simulating play delay until events are implemented
          } yield (siteId, requestId)
        }
        .map { case (siteId, requestId) =>
          MqttMessage(PlayFinishedTopic(siteId), ByteString(s"""{"id":"$requestId"}"""))
        }
        .runWith(mqttSink)
      context.pipeToSelf(streamStatus)(StreamCompleted)

      Behaviors.receiveMessage {
        case StreamCompleted(Success(_)) =>
          context.log.info("stream stopped expectedly")
          Behaviors.stopped

        case StreamCompleted(Failure(exception)) =>
          context.log.error("stream stopped unexpectedly", exception)
          Behaviors.stopped
      }
    },
    "App"
  )

  Await.result(actorSystem.whenTerminated, Duration.Inf)

  private object PlayBytesTopic {
    val topic: String = "hermes/audioServer/+/playBytes/+"

    def unapply(topic: String): Option[(String, String)] = topic.split('/') match {
      case Array("hermes", "audioServer", siteId, "playBytes", requestId) => Some((siteId, requestId))
      case _                                                              => None
    }
  }

  private object PlayFinishedTopic {
    def apply(siteId: String): String = s"hermes/audioServer/$siteId/playFinished"
  }
}
