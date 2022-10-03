package hermesUpnpAudioServer.models

import scala.xml.NodeSeq

case class Event(instanceId: String, transportState: String, currentTrackURI: String)

object Event {
  def apply(xml: NodeSeq): Event = {
    def getVal(label: String): Option[String] = (xml \\ label).headOption.map(_ \@ "val")

    val eventOpt = for {
      instanceId <- getVal("InstanceID")
      transportState <- getVal("TransportState")
      currentTrackURI <- getVal("CurrentTrackURI")
    } yield Event(instanceId, transportState, currentTrackURI)

    eventOpt.getOrElse(throw new Exception("unable to parse event xml"))
  }
}
