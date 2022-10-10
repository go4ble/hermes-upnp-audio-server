package hermesUpnpAudioServer.utils

import java.io.ByteArrayInputStream
import javax.sound.sampled.AudioSystem
import scala.concurrent.duration.{FiniteDuration, MILLISECONDS}

object audio {
  def estimatedDuration(audioBytes: Array[Byte]): FiniteDuration = {
    val audioInputStream = AudioSystem.getAudioInputStream(new ByteArrayInputStream(audioBytes))
    val frameLength = audioInputStream.getFrameLength
    val frameRate = audioInputStream.getFormat.getFrameRate
    FiniteDuration((frameLength * 1000 / frameRate).toLong, MILLISECONDS)
  }
}
