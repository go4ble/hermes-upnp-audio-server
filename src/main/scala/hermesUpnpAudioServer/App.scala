package hermesUpnpAudioServer

import akka.Done
import akka.actor.CoordinatedShutdown
import akka.actor.typed.scaladsl.AskPattern.Askable
import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.{ActorRef, ActorSystem}
import hermesUpnpAudioServer.utils.audio
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence
import org.eclipse.paho.client.mqttv3.{IMqttMessageListener, MqttClient, MqttConnectOptions, MqttMessage}

import java.net.URL
import scala.concurrent.Await
import scala.concurrent.duration._
import scala.util.chaining.scalaUtilChainingOps
import scala.util.{Failure, Success, Try}

/*
 * MqttMessageReceived
 *   ▼
 * AudioServer.PublishAudio
 *   ▼
 * SetAVTransportURI ──► DeviceManager.Send
 *   ▼
 * Play ──► DeviceManager.Send
 *   ▼
 * WaitForAudioDuration
 *   ▼
 * PlayFinished ──► AudioServer.RemoveAudio
 */
object App extends scala.App {
  private val MediaRendererDeviceTypePrefix = "urn:schemas-upnp-org:device:MediaRenderer"
  private val AVTransportServiceTypePrefix = "urn:schemas-upnp-org:service:AVTransport"
  private val SetAVTransportURIAction = "SetAVTransportURI"
  private val PlayAction = "Play"

  private val SiteConfigRegex = "^HERMES_UPNP_AUDIO_SERVER_SITE_(.+)$".r
  private val MqttConfigBroker = "HERMES_UPNP_AUDIO_SERVER_MQTT_BROKER"

  private sealed trait AppMessage
  private final case class MqttMessageReceived(topic: String, message: MqttMessage) extends AppMessage
  private final case class SetAVTransportURI(siteId: String, requestId: String, url: URL, duration: FiniteDuration) extends AppMessage
  private final case class Play(siteId: String, requestId: String, duration: FiniteDuration) extends AppMessage
  private final case class WaitForAudioDuration(siteId: String, requestId: String, duration: FiniteDuration) extends AppMessage
  private final case class PlayFinished(siteId: String, requestId: String) extends AppMessage
  private final case class ActionRequestError(siteId: String, action: String, error: Throwable) extends AppMessage
  private final case class Shutdown(replyTo: ActorRef[Done]) extends AppMessage

  private val actorSystem = ActorSystem(
    Behaviors.withTimers[AppMessage] { timerScheduler =>
      Behaviors.setup[AppMessage] { context =>
        val logger = context.log

        val audioServer = context.spawn(AudioServerBehavior(), "AudioServer")
        context.watch(audioServer)
        val deviceManager = context.spawn(DeviceManagerBehavior(), "DeviceManager")
        context.watch(deviceManager)

        val sites = sys.env.collect { case (SiteConfigRegex(siteId), UrlExtractor(deviceLocation)) => siteId -> deviceLocation }
        require(sites.nonEmpty, "no site configurations were found")
        sites.foreach { case (siteId, deviceLocation) =>
          deviceManager ! DeviceManagerBehavior.AddDeviceMessage(
            siteId,
            deviceLocation,
            MediaRendererDeviceTypePrefix,
            Map.empty // no subscriptions
          )
        }

        val mqttClient = new MqttClient(
          sys.env.getOrElse(MqttConfigBroker, "tcp://localhost:1883"),
          MqttClient.generateClientId(),
          new MemoryPersistence
        )
        mqttClient.connect((new MqttConnectOptions).tap(_.setCleanSession(true)))

        val (topics, qos, messageListeners) = sites
          .map { case (siteId, _) =>
            (
              PlayBytesTopic(siteId),
              2,
              ((topic: String, message: MqttMessage) => { context.self ! MqttMessageReceived(topic, message) }): IMqttMessageListener
            )
          }
          .toArray
          .unzip3
        mqttClient.subscribe(topics, qos, messageListeners)

        Behaviors.receiveMessage {
          case MqttMessageReceived(topic @ PlayBytesTopic(siteId, requestId), message) =>
            logger.debug(s"play bytes (${message.getPayload.length}): $topic")
            val estimatedDuration = audio.estimatedDuration(message.getPayload)
            val replyTo = context.messageAdapter[URL](SetAVTransportURI(siteId, requestId, _, estimatedDuration))
            audioServer ! AudioServerBehavior.PublishAudioMessage(siteId, requestId, message.getPayload, replyTo)
            Behaviors.same

          case MqttMessageReceived(unexpectedTopic, _) =>
            context.log.error(s"unexpected topic received: $unexpectedTopic")
            Behaviors.same

          case SetAVTransportURI(siteId, requestId, url, duration) =>
            val setUriProperties = Map("InstanceID" -> Some("0"), "CurrentURI" -> Some(url.toString), "CurrentURIMetaData" -> None)
            val replyTo = context.messageAdapter[DeviceBehavior.ActionResponse] {
              case DeviceBehavior.ActionResponse(_, _, Success(_))                  => Play(siteId, requestId, duration)
              case DeviceBehavior.ActionResponse(_, actionName, Failure(exception)) => ActionRequestError(siteId, actionName, exception)
            }
            val setUriMessage = DeviceBehavior.ActionRequestMessage(AVTransportServiceTypePrefix, SetAVTransportURIAction, setUriProperties, replyTo)
            deviceManager ! DeviceManagerBehavior.SendMessage(siteId, setUriMessage)
            Behaviors.same

          case Play(siteId, requestId, duration) =>
            val playProperties = Map("InstanceID" -> Some("0"), "Speed" -> Some("1"))
            val replyTo = context.messageAdapter[DeviceBehavior.ActionResponse] {
              case DeviceBehavior.ActionResponse(_, _, Success(_))                  => WaitForAudioDuration(siteId, requestId, duration)
              case DeviceBehavior.ActionResponse(_, actionName, Failure(exception)) => ActionRequestError(siteId, actionName, exception)
            }
            val playMessage = DeviceBehavior.ActionRequestMessage(AVTransportServiceTypePrefix, PlayAction, playProperties, replyTo)
            deviceManager ! DeviceManagerBehavior.SendMessage(siteId, playMessage)
            Behaviors.same

          case WaitForAudioDuration(siteId, requestId, duration) =>
            timerScheduler.startSingleTimer(PlayFinished(siteId, requestId), duration)
            Behaviors.same

          case PlayFinished(siteId, requestId) =>
            audioServer ! AudioServerBehavior.RemoveAudioMessage(siteId, requestId)
            val playFinishedMessage = new MqttMessage(s"""{"id":"$requestId"}""".getBytes)
            mqttClient.publish(PlayFinishedTopic(siteId), playFinishedMessage)
            Behaviors.same

          case ActionRequestError(siteId, actionName, exception) =>
            context.log.error(s"Error submitting action ($actionName) for $siteId", exception)
            Behaviors.same

          case Shutdown(replyTo) =>
            context.log.info("Shutting down application...")
            mqttClient.disconnect()
            replyTo ! Done
            Behaviors.same
        }
      }
    },
    "App"
  )

  CoordinatedShutdown(actorSystem).addTask(CoordinatedShutdown.PhaseBeforeServiceUnbind, "shutdown-task") { () =>
    actorSystem.ask(Shutdown)(15.seconds, actorSystem.scheduler)
  }

  Await.result(actorSystem.whenTerminated, Duration.Inf)

  private object PlayBytesTopic {
    def apply(siteId: String): String = s"hermes/audioServer/$siteId/playBytes/+"

    def unapply(topic: String): Option[(String, String)] = topic.split('/') match {
      case Array("hermes", "audioServer", siteId, "playBytes", requestId) => Some((siteId, requestId))
      case _                                                              => None
    }
  }

  private object PlayFinishedTopic {
    def apply(siteId: String): String = s"hermes/audioServer/$siteId/playFinished"
  }

  private object UrlExtractor {
    def unapply(url: String): Option[URL] = Try(new URL(url)).toOption
  }
}
