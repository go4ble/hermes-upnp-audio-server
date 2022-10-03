package hermesUpnpAudioServer.utils

import java.net.{ServerSocket, Socket}

object server {
  // determine via routing to external DNS server
  lazy val defaultInterface: String = {
    val socket = new Socket("1.1.1.1", 53)
    val address = socket.getLocalAddress.getHostAddress
    socket.close()
    address
  }

  def getAvailablePort: Int = {
    val serverSocket = new ServerSocket(0)
    serverSocket.setReuseAddress(true)
    serverSocket.close()
    serverSocket.getLocalPort
  }
}
