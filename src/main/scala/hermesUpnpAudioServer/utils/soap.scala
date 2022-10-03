package hermesUpnpAudioServer.utils


import akka.http.scaladsl.model.{ContentTypes, HttpEntity, HttpHeader, HttpMethods, HttpRequest}

import java.net.URL
import scala.xml.Elem

object soap {
  object SoapActionRequest {
    def apply(url: URL, service: String, action: String, body: Elem): HttpRequest = {
      val payload =
        <s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">
          <s:Body>
            {body}
          </s:Body>
        </s:Envelope>
      HttpRequest(
        method = HttpMethods.POST,
        uri = url.toString,
        headers = Seq(SoapActionHeader(service, action).asAkkaHeader),
        entity = HttpEntity(ContentTypes.`text/xml(UTF-8)`, payload.toString())
      )
    }
  }

  case class SoapActionHeader(service: String, action: String) {
    val HeaderName = "SoapAction"
    lazy val asAkkaHeader: HttpHeader = HttpHeader.parse(HeaderName, s"$service#$action").asInstanceOf[HttpHeader.ParsingResult.Ok].header
  }
}
