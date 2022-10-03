package hermesUpnpAudioServer.models

import scala.xml.Node

case class Device(
    deviceType: String,
    friendlyName: String,
    manufacturer: String,
    manufacturerURL: String,
    modelNumber: String,
    modelDescription: String,
    modelName: String,
    UDN: String,
    serviceList: Seq[Service]
)

object Device {
  def apply(xml: Node): Device = {
    val getText = (label: String) => (xml \ label).headOption.fold("")(_.text)
    Device(
      deviceType = getText("deviceType"),
      friendlyName = getText("friendlyName"),
      manufacturer = getText("manufacturer"),
      manufacturerURL = getText("manufacturerURL"),
      modelNumber = getText("modelNumber"),
      modelDescription = getText("modelDescription"),
      modelName = getText("modelName"),
      UDN = getText("UDN"),
      serviceList = (xml \ "serviceList" \ "service").map(Service(_))
    )
  }
}
