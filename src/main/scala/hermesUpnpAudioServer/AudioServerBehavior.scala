package hermesUpnpAudioServer

import akka.Done
import akka.actor.typed.scaladsl.AskPattern._
import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.{ActorRef, ActorSystem, Behavior}
import akka.http.scaladsl.Http
import akka.http.scaladsl.model._
import akka.stream.{Materializer, SystemMaterializer}
import hermesUpnpAudioServer.utils.server._

import java.net.URL
import scala.concurrent.duration._
import scala.util.{Failure, Success, Try}

object AudioServerBehavior {
  type SiteId = String
  type RequestId = String

  sealed trait AudioServerMessage

  final case class PublishAudioMessage(siteId: SiteId, requestId: RequestId, audio: Array[Byte], replyTo: ActorRef[URL]) extends AudioServerMessage
  final case class RemoveAudioMessage(siteId: SiteId, requestId: RequestId) extends AudioServerMessage

  private final case class HttpRequestMessage(request: HttpRequest, replyTo: ActorRef[HttpResponse]) extends AudioServerMessage
  private final case class ServerCeasedMessage(result: Try[Done]) extends AudioServerMessage

  def apply(): Behavior[AudioServerMessage] = Behaviors.setup { context =>
    implicit val system: ActorSystem[_] = context.system

    val serverPort = sys.env.get("HERMES_UPNP_AUDIO_SERVER_PORT").flatMap(_.toIntOption).getOrElse(8080)
    val serverInterface = sys.env.getOrElse("HERMES_UPNP_AUDIO_SERVER_INTERFACE", "0.0.0.0")
    val serverHost = sys.env.get("HERMES_UPNP_AUDIO_SERVER_HOST")
    val server = Http().newServerAt(serverInterface, serverPort).connectionSource().runForeach { connection =>
      connection.handleWithAsyncHandler(request => context.self.ask(HttpRequestMessage(request, _))(5.seconds, system.scheduler))
    }
    context.pipeToSelf(server)(ServerCeasedMessage)

    val baseUrl = new URL("http", serverHost getOrElse defaultInterface, serverPort, "")
    context.log.info(s"starting audio server at $baseUrl")
    AudioServerBehavior(baseUrl)
  }

  private def apply(baseUrl: URL, audioMap: Map[(SiteId, RequestId), (Array[Byte], Long)] = Map.empty): Behavior[AudioServerMessage] =
    Behaviors.setup { context =>
      implicit val mat: Materializer = SystemMaterializer(context.system).materializer
      val fullSites = audioMap.toSeq
        .groupMap { case ((siteId, _), _) => siteId } { case ((_, requestId), (_, createdAt)) => (requestId, createdAt) }
        .filter { case (_, requests) => requests.length >= 10 }
      require(fullSites.isEmpty, s"some sites have collected too much audio: $fullSites")

      Behaviors.receiveMessage {
        case PublishAudioMessage(siteId, requestId, audio, replyTo) =>
          val url = new URL(baseUrl.getProtocol, baseUrl.getHost, baseUrl.getPort, AudioPath(siteId, requestId))
          replyTo ! url
          AudioServerBehavior(baseUrl, audioMap.updated((siteId, requestId), (audio, System.currentTimeMillis())))

        case RemoveAudioMessage(siteId, requestId) =>
          AudioServerBehavior(baseUrl, audioMap.removed((siteId, requestId)))

        case HttpRequestMessage(HttpRequest(method, AudioPath(siteId, requestId), _, entity, _), replyTo) if audioMap.contains((siteId, requestId)) =>
          entity.discardBytes()
          context.log.debug(s"audio request received ${method.value}: ($siteId, $requestId)")
          val (audio, _) = audioMap((siteId, requestId))
          replyTo ! HttpResponse(entity = HttpEntity(MediaTypes.`audio/wav`, audio))
          Behaviors.same

        case HttpRequestMessage(unexpectedRequest, replyTo) =>
          context.log.error(s"unexpected request: $unexpectedRequest")
          unexpectedRequest.discardEntityBytes()
          replyTo ! HttpResponse(status = StatusCodes.NotFound)
          Behaviors.same

        case ServerCeasedMessage(Success(_)) =>
          context.log.info("audio server ceased OK")
          Behaviors.stopped

        case ServerCeasedMessage(Failure(exception)) =>
          context.log.error("audio server ceased unexpectedly", exception)
          Behaviors.stopped
      }
    }

  private object AudioPath {
    private val AudioPathRegex = "/audio/(.+)/(.+).wav".r

    def apply(siteId: SiteId, requestId: RequestId): String = s"/audio/$siteId/$requestId.wav"

    def unapply(uri: Uri): Option[(SiteId, RequestId)] = uri.path.toString() match {
      case AudioPathRegex(siteId, requestId) => Some((siteId, requestId))
      case _                                 => None
    }
  }
}
