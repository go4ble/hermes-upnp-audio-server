package hermesUpnpAudioServer.utils

object options {
  implicit class EnhancedOption[T](option: Option[T]) {
    def getOrElseError(errorMessage: String): T = option.getOrElse(throw new NoSuchElementException(errorMessage))
  }
}
