package hermesUpnpAudioServer.utils

import java.io.ByteArrayInputStream
import javax.sound.sampled.AudioSystem
import scala.concurrent.duration.{Duration, SECONDS}

object audio {
  def estimatedDuration(audioBytes: Array[Byte]): Duration = {
    val audioInputStream = AudioSystem.getAudioInputStream(new ByteArrayInputStream(audioBytes))
    val frameLength = audioInputStream.getFrameLength
    val frameRate = audioInputStream.getFormat.getFrameRate
    Duration(frameLength / frameRate, SECONDS)
  }
}
