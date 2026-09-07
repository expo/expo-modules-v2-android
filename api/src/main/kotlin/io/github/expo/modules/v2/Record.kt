package io.github.expo.modules.v2

@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.BINARY)
annotation class Record(
  /**
   * Whether every field of this record can ride the binary buffer.
   */
  val bufferSafe: Boolean = true,
)
