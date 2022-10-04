package hermesUpnpAudioServer

import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.{ActorRef, ActorSystem, Behavior}
import hermesUpnpAudioServer.models.Device

import java.net.URL
import scala.util.{Failure, Success, Try}

object DeviceManagerBehavior {
  sealed trait DeviceManagerMessage

  final case class AddDeviceMessage(siteId: String, deviceLocation: URL, deviceType: String, eventSubscriber: Map[String, ActorRef[DeviceBehavior.Event]])
      extends DeviceManagerMessage
  private final case class DeviceAddedMessage(siteId: String, device: Try[(Device, Behavior[DeviceBehavior.DeviceMessage])]) extends DeviceManagerMessage

  final case class SendMessage(siteId: String, deviceMessage: DeviceBehavior.DeviceMessage) extends DeviceManagerMessage

  def apply(): Behavior[DeviceManagerMessage] = DeviceManagerBehavior(Map[String, ActorRef[DeviceBehavior.DeviceMessage]]())

  // TODO children monitoring
  def apply(devices: Map[String, ActorRef[DeviceBehavior.DeviceMessage]]): Behavior[DeviceManagerMessage] = Behaviors.setup { context =>
    implicit val system: ActorSystem[_] = context.system

    Behaviors.receiveMessage {
      case AddDeviceMessage(siteId, deviceLocation, deviceType, eventSubscriber) =>
        val behaviorF = DeviceBehavior(deviceLocation, deviceType, eventSubscriber)
        context.pipeToSelf(behaviorF)(DeviceAddedMessage(siteId, _))
        Behaviors.same

      case DeviceAddedMessage(siteId, Success((device, behavior))) =>
        context.log.info(s"added ${device.friendlyName} for $siteId")
        val actorRef = context.spawn(behavior, siteId + "_device")
        DeviceManagerBehavior(devices.updated(siteId, actorRef))

      case DeviceAddedMessage(siteId, Failure(exception)) =>
        context.log.error(s"failed to add device for $siteId", exception)
        Behaviors.stopped

      case SendMessage(siteId, deviceMessage) =>
        devices.get(siteId).foreach(_ ! deviceMessage)
        Behaviors.same
    }
  }
}
