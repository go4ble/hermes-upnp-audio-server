package hermesUpnpAudioServer

import akka.Done
import akka.actor.typed.scaladsl.AskPattern._
import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.{ActorRef, ActorSystem, Behavior}
import akka.http.scaladsl.Http
import akka.http.scaladsl.marshallers.xml.ScalaXmlSupport._
import akka.http.scaladsl.model._
import akka.http.scaladsl.settings.{ParserSettings, ServerSettings}
import akka.http.scaladsl.unmarshalling.Unmarshal
import akka.stream.{Materializer, SystemMaterializer}
import hermesUpnpAudioServer.models.Device
import hermesUpnpAudioServer.utils.options._
import hermesUpnpAudioServer.utils.server._
import hermesUpnpAudioServer.utils.soap._
import hermesUpnpAudioServer.utils.upnp

import java.net.URL
import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success, Try}
import scala.xml._

object DeviceBehavior {
  type ServiceType = String
  type SubscriptionId = String
  type ActionName = String
  type Properties = Map[String, String]

  final case class Event(serviceType: ServiceType, properties: Properties)
  final case class ActionResponse(serviceType: ServiceType, actionName: ActionName, properties: Try[Properties])

  sealed trait DeviceMessage

  final case class ActionRequestMessage(
      serviceType: ServiceType,
      actionName: ActionName,
      properties: Map[String, Option[String]],
      replyTo: ActorRef[ActionResponse]
  ) extends DeviceMessage

  private final case class ActionResponseMessage(
      serviceType: ServiceType,
      actionName: ActionName,
      response: Try[NodeSeq],
      replyTo: ActorRef[ActionResponse]
  ) extends DeviceMessage
  private final case class HttpRequestMessage(request: HttpRequest, replyTo: ActorRef[HttpResponse]) extends DeviceMessage
  private final case class ServiceSubscriptionMessage(serviceType: ServiceType, response: Try[HttpResponse]) extends DeviceMessage
  private final case class EventReceivedMessage(serviceType: ServiceType, seq: Long, propertySetXml: Try[NodeSeq]) extends DeviceMessage
  private final case class ServerCeasedMessage(result: Try[Done]) extends DeviceMessage

  def apply(deviceLocation: URL, deviceType: String, eventSubscriber: Option[ActorRef[Event]])(implicit
      system: ActorSystem[_]
  ): Future[(Device, Behavior[DeviceMessage])] = {
    implicit val ec: ExecutionContext = system.executionContext
    for {
      response <- Http().singleRequest(HttpRequest(uri = deviceLocation.toString))
      _ = require(response.status.isSuccess())
      responseXml <- Unmarshal(response).to[NodeSeq]
      deviceXmlOpt = (responseXml \\ "device").find(node => (node \ "deviceType").headOption.map(_.text).contains(deviceType))
      deviceXml = deviceXmlOpt.getOrElseError(s"could not locate device $deviceType at $deviceLocation")
      device = Device(deviceXml)
    } yield (device, DeviceBehavior(deviceLocation, device, eventSubscriber))
  }

  def apply(deviceLocation: URL, device: Device, eventSubscriber: Option[ActorRef[Event]]): Behavior[DeviceMessage] = Behaviors.setup { context =>
    implicit val system: ActorSystem[_] = context.system
    implicit val ec: ExecutionContext = context.executionContext

    eventSubscriber.foreach { _ =>
      context.log.info(s"subscribing to ${device.serviceList.length} events")
      val serverPort = getAvailablePort
      context.log.info(s"starting device server at http://$defaultInterface:$serverPort")
      val parserSettings = ParserSettings.forServer.withCustomMethods(upnp.Methods.NOTIFY)
      val serverSettings = ServerSettings(system).withParserSettings(parserSettings)
      val server = Http().newServerAt(defaultInterface, serverPort).withSettings(serverSettings).connectionSource().runForeach { connection =>
        connection.handleWithAsyncHandler(request => context.self.ask(HttpRequestMessage(request, _))(5.seconds, system.scheduler))
      }
      context.pipeToSelf(server)(ServerCeasedMessage)
      device.serviceList.foreach { service =>
        val subscribeRequest = HttpRequest(
          method = upnp.Methods.SUBSCRIBE,
          uri = new URL(deviceLocation.getProtocol, deviceLocation.getHost, deviceLocation.getPort, service.eventSubURL).toString,
          headers = Seq(
            customHeader("CALLBACK", s"<http://$defaultInterface:$serverPort/events>"),
            customHeader("NT", "upnp:event")
          )
        )
        context.pipeToSelf(Http().singleRequest(subscribeRequest).filter(_.status.isSuccess()))(ServiceSubscriptionMessage(service.serviceType, _))
      }
    }

    DeviceBehavior(deviceLocation, device, eventSubscriber, Map())
  }

  private def apply(
      deviceLocation: URL,
      device: Device,
      eventSubscriber: Option[ActorRef[Event]],
      serviceSubscriptions: Map[SubscriptionId, ServiceType]
  ): Behavior[DeviceMessage] = Behaviors.setup { context =>
    implicit val system: ActorSystem[_] = context.system
    implicit val ec: ExecutionContext = context.executionContext
    implicit val mat: Materializer = SystemMaterializer(system).materializer

    Behaviors.receiveMessage {
      case ActionRequestMessage(serviceType, actionName, properties, replyTo) =>
        val service = device.serviceList.find(_.serviceType == serviceType).getOrElseError(s"unable to find service $serviceType for ${device.friendlyName}")
        val controlUrl = new URL(deviceLocation.getProtocol, deviceLocation.getHost, deviceLocation.getPort, service.controlURL)
        val propertiesXml = properties.map { case (k, v) => Elem(null, k, Null, TopScope, minimizeEmpty = true, v.map(Text(_)).toSeq: _*) }.toSeq
        val actionXml = Elem("u", actionName, Null, NamespaceBinding("u", serviceType, TopScope), minimizeEmpty = true, propertiesXml: _*)
        val request = SoapActionRequest(controlUrl, serviceType, actionName, actionXml)
        val responseF = Http().singleRequest(request).map(response => response.ensuring(_.status.isSuccess(), response)).flatMap(Unmarshal(_).to[NodeSeq])
        context.pipeToSelf(responseF)(ActionResponseMessage(serviceType, actionName, _, replyTo))
        Behaviors.same

      case ActionResponseMessage(serviceType, actionName, xmlTry, replyTo) =>
        val properties = xmlTry.map(xml => (xml \ s"${actionName}Response").headOption.fold[Seq[Node]](Nil)(_.child).map(n => (n.label, n.text)).toMap)
        replyTo ! ActionResponse(serviceType, actionName, properties)
        Behaviors.same

      case HttpRequestMessage(HttpRequest(upnp.Methods.NOTIFY, Uri.Path(s"/events"), headers, entity, _), replyTo) =>
        val seq = headers.find(_ is "seq").flatMap(_.value().toLongOption).getOrElseError("unable to find SEQ in notify request")
        val subscriptionId = headers.find(_ is "sid").map(_.value()).getOrElseError("unable to find SID in notify request")
        val serviceType = serviceSubscriptions.get(subscriptionId).getOrElseError(s"unable to find subscription for $subscriptionId")
        context.pipeToSelf(Unmarshal(entity).to[NodeSeq])(EventReceivedMessage(serviceType, seq, _))
        replyTo ! HttpResponse(status = StatusCodes.NoContent)
        Behaviors.same

      case HttpRequestMessage(unexpectedRequest, replyTo) =>
        context.log.error(s"unexpected request: $unexpectedRequest")
        unexpectedRequest.discardEntityBytes()
        replyTo ! HttpResponse(status = StatusCodes.NoContent)
        Behaviors.same

      case EventReceivedMessage(serviceType, _, Success(propertySetXml)) =>
        val properties = (propertySetXml \ "property").headOption.fold[Seq[Node]](Nil)(_.child).map(n => (n.label, n.text)).toMap
        eventSubscriber.get ! Event(serviceType, properties)
        Behaviors.same

      case EventReceivedMessage(serviceType, seq, Failure(exception)) =>
        context.log.error(s"failed to parse event (serviceType: $serviceType, seq: $seq)", exception)
        Behaviors.same

      case ServiceSubscriptionMessage(serviceType, Success(response)) =>
        val subscriptionId = response.headers.find(_ is "sid").map(_.value()).getOrElseError("unable to find SID is subscription response")
        val subscriptionTimeout = response.headers.find(_ is "timeout").map(_.value()).getOrElseError("unable to find TIMEOUT in subscription response")
        // TODO renew subscriptions with timer
        // TODO unsubscribe in coordinated shutdown
        context.log.info(s"successfully subscribed to $serviceType with id $subscriptionId")
        DeviceBehavior(deviceLocation, device, eventSubscriber, serviceSubscriptions.updated(subscriptionId, serviceType))

      case ServiceSubscriptionMessage(serviceType, Failure(exception)) =>
        context.log.error(s"failed to subscribe to $serviceType for ${device.friendlyName}", exception)
        Behaviors.stopped

      case ServerCeasedMessage(result) =>
        result match {
          case Success(_)         => context.log.info(s"device server ceased OK for ${device.friendlyName}")
          case Failure(exception) => context.log.error(s"device server ceased unexpectedly for ${device.friendlyName}", exception)
        }
        Behaviors.stopped
    }
  }

  private def customHeader(name: String, value: String): HttpHeader = HttpHeader.parse(name, value) match {
    case HttpHeader.ParsingResult.Ok(header, _) => header
    case HttpHeader.ParsingResult.Error(error)  => throw new Exception(s"failed to parse header ($name, $value): $error")
  }
}
