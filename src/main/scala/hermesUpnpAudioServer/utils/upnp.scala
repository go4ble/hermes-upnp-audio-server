package hermesUpnpAudioServer.utils

import akka.http.scaladsl.model.{HttpMethod, RequestEntityAcceptance}

object upnp {
  object Methods {
    val SUBSCRIBE: HttpMethod = HttpMethod.custom(
      name = "SUBSCRIBE",
      safe = false,
      idempotent = false,
      requestEntityAcceptance = RequestEntityAcceptance.Disallowed
    )
    val NOTIFY: HttpMethod = HttpMethod.custom(
      name = "NOTIFY",
      safe = false,
      idempotent = true,
      requestEntityAcceptance = RequestEntityAcceptance.Expected
    )
  }
}
