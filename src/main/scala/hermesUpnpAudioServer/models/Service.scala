package hermesUpnpAudioServer.models

import scala.xml.Node

case class Service(serviceType: String, serviceId: String, controlURL: String, eventSubURL: String, SCPDURL: String)

object Service {
  def apply(xml: Node): Service = {
    val getText = (label: String) => (xml \ label).headOption.fold("")(_.text)
    Service(
      serviceType = getText("serviceType"),
      serviceId = getText("serviceId"),
      controlURL = getText("controlURL"),
      eventSubURL = getText("eventSubURL"),
      SCPDURL = getText("SCPDURL")
    )
  }
}
