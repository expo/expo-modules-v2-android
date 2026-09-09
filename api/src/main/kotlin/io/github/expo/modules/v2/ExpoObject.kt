package io.github.expo.modules.v2

import java.util.concurrent.atomic.AtomicLong

/**
 * Any Kotlin object JavaScript can hold a reference to: a [Module] or a [SharedObject].
 *
 * Carries the process-wide id the native side keys its instance tables by. One instance has one
 * id for its whole life, and each runtime maps that id to its own JavaScript object.
 */
abstract class ExpoObject {
  /** Assigned once here and only read by the native side, with a single JNI field read. */
  @JvmField
  internal val objectId: Long = nextObjectId.getAndIncrement()

  private companion object {
    private val nextObjectId = AtomicLong(1)
  }
}
