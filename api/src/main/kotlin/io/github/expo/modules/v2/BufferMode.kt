package io.github.expo.modules.v2

/**
 * Whether a value crosses the bridge on the shared binary buffer or in its own JNI slot.
 */
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
