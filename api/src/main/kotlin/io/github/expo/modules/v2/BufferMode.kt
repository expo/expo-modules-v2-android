package io.github.expo.modules.v2

@Target(
  AnnotationTarget.CLASS,
  AnnotationTarget.FUNCTION,
  AnnotationTarget.PROPERTY,
  AnnotationTarget.VALUE_PARAMETER,
)
@Retention(AnnotationRetention.BINARY)
annotation class BufferMode(
  /** The choice for arguments, and for the result unless [returns] narrows it. */
  val value: Buffer = Buffer.AUTO,
  /** The choice for a member's result; defaults to [Buffer.AUTO]. */
  val returns: Buffer = Buffer.AUTO,
)
