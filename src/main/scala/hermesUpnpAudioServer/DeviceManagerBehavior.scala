package hermesUpnpAudioServer

import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.{ActorRef, ActorSystem, Behavior}
import akka.pattern.after
import hermesUpnpAudioServer.models.Device

import java.net.URL
import scala.concurrent.duration.DurationInt
import scala.concurrent.{ExecutionContext, Future, TimeoutException}
import scala.util.{Failure, Success, Try}

object DeviceManagerBehavior {
  sealed trait DeviceManagerMessage

  final case class AddDeviceMessage(siteId: String, deviceLocation: URL, deviceTypePrefix: String, eventSubscriber: Map[String, ActorRef[DeviceBehavior.Event]])
      extends DeviceManagerMessage
  private final case class DeviceAddedMessage(siteId: String, device: Try[(Device, Behavior[DeviceBehavior.DeviceMessage])]) extends DeviceManagerMessage

  final case class SendMessage(siteId: String, deviceMessage: DeviceBehavior.DeviceMessage) extends DeviceManagerMessage

  def apply(): Behavior[DeviceManagerMessage] = DeviceManagerBehavior(Map[String, (Device, ActorRef[DeviceBehavior.DeviceMessage])]())

  def apply(devices: Map[String, (Device, ActorRef[DeviceBehavior.DeviceMessage])]): Behavior[DeviceManagerMessage] = Behaviors.setup { context =>
    implicit val system: ActorSystem[_] = context.system
    implicit val ec: ExecutionContext = context.executionContext

    Behaviors.receiveMessage {
      case AddDeviceMessage(siteId, deviceLocation, deviceTypePrefix, eventSubscriber) =>
        val behaviorF = DeviceBehavior(deviceLocation, deviceTypePrefix, eventSubscriber)
        val timeoutF = after[(Device, Behavior[DeviceBehavior.DeviceMessage])](5.seconds) {
          Future.failed(new TimeoutException(s"Timed out waiting for device: $deviceLocation"))
        }
        context.pipeToSelf(Future.firstCompletedOf(Seq(behaviorF, timeoutF)))(DeviceAddedMessage(siteId, _))
        Behaviors.same

      case DeviceAddedMessage(siteId, Success((device, behavior))) =>
        context.log.info(s"added ${device.friendlyName} for $siteId")
        val actorRef = context.spawn(behavior, siteId + "_device")
        context.watch(actorRef)
        DeviceManagerBehavior(devices.updated(siteId, (device, actorRef)))

      case DeviceAddedMessage(siteId, Failure(exception)) =>
        context.log.error(s"failed to add device for $siteId", exception)
        Behaviors.stopped

      case SendMessage(siteId, deviceMessage) =>
        devices.get(siteId) match {
          case Some((_, deviceBehavior)) => deviceBehavior ! deviceMessage
          case _                         => context.log.error(s"unknown device: $siteId")
        }
        Behaviors.same
    }
  }
}
