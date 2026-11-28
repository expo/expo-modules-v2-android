package io.github.expo.modules.v2.views.baseline

import io.github.expo.kolibri.CalledFromNative
import io.github.expo.kolibri.nativePointerOf
import io.github.expo.modules.v2.core.ExpoModulesV2
import io.github.expo.modules.v2.jsi.JavaScriptValue
import io.github.expo.modules.v2.views.MountedViewProps
import io.github.expo.modules.v2.views.PropsDiff
import io.github.expo.modules.v2.views.ViewPropsDecoder
import java.util.concurrent.atomic.AtomicLong

/**
 * BENCHMARK ONLY - drop before this lands. The props transport of the first prototype, which the
 * binary one replaced, kept so `:benchmark` can compare the two on the same props:
 *
 * - commit (`cloneProps`, JS thread): each prop converted to a Java object (`encodeToJni`), then one
 *   JNI call into [decode], which builds the next immutable props; C++ keeps a global ref to them,
 *   which the shadow node's state carried to Java.
 * - mount (UI thread): `getJNIReference` hands back the object, and the view diffs revisions.
 *
 * What it can't include is React Native's share, which only exists on a device: the extra `State`
 * per update, the `UpdateState` mount item and the `StateWrapperImpl` Java object built for it.
 */
@CalledFromNative(by = "expo-modules-v2/views/baseline/StateViewPropsBaseline.cpp")
class StateViewPropsBaseline<P : Any>(val decoder: ViewPropsDecoder<P>) : AutoCloseable {
  /** The props object the state carried: decoded props and per-prop revisions. */
  class Decoded<P : Any> internal constructor(val props: P, internal val revisions: LongArray)

  private val pointer = nativeCreate(this, decoder)

  override fun close() = nativeDestroy(pointer)

  /**
   * Commits the props object [props] holds on top of the props [previousOwner] owns, and returns an
   * owner of the result (a C++ `shared_ptr` to the global ref, as the props and state held), or 0
   * when no prop of the view changed. Release owners with [release].
   */
  fun commit(props: JavaScriptValue, previousOwner: Long): Long =
    nativeCommit(pointer, nativePointerOf(props), previousOwner)

  /** What `ExpoViewState::getJNIReference` returned to `stateDataReference` on the UI thread. */
  @Suppress("UNCHECKED_CAST")
  fun reference(owner: Long): Decoded<P> = nativeReference(owner) as Decoded<P>

  fun release(owner: Long) = nativeRelease(owner)

  /** The view's side: what changed since [mounted], as the view gets it. */
  fun mount(decoded: Decoded<P>, mounted: Decoded<P>?): PropsDiff<P> {
    var changed = 0L
    if (mounted == null) {
      changed = if (decoded.revisions.size == 64) -1L else (1L shl decoded.revisions.size) - 1
    } else {
      for (index in decoded.revisions.indices) {
        if (decoded.revisions[index] != mounted.revisions[index]) {
          changed = changed or (1L shl index)
        }
      }
    }
    return PropsDiff(
      old = mounted?.let { MountedViewProps(decoder, it.props, it.revisions) },
      new = MountedViewProps(decoder, decoded.props, decoded.revisions),
      changedProps = changed,
    )
  }

  @Suppress("UNCHECKED_CAST", "unused")
  @CalledFromNative(by = "expo-modules-v2/views/baseline/StateViewPropsBaseline.cpp")
  private fun decode(previous: Any?, indices: IntArray, values: Array<Any?>): Any {
    val prior = previous as Decoded<P>?
    val revision = nextRevision.incrementAndGet()
    val props = decoder.applyUpdate(prior?.props ?: decoder.defaults(), indices, values)
    if (prior == null) {
      return Decoded(props, LongArray(decoder.props.size) { revision })
    }
    val revisions = prior.revisions.copyOf()
    for (index in indices) {
      revisions[index] = revision
    }
    return Decoded(props, revisions)
  }

  private companion object {
    init {
      ExpoModulesV2.load()
    }

    val nextRevision = AtomicLong(0)

    @JvmStatic
    external fun nativeCreate(baseline: Any, decoder: Any): Long

    @JvmStatic
    external fun nativeDestroy(pointer: Long)

    @JvmStatic
    external fun nativeCommit(pointer: Long, valuePointer: Long, previousOwner: Long): Long

    @JvmStatic
    external fun nativeReference(owner: Long): Any?

    @JvmStatic
    external fun nativeRelease(owner: Long)
  }
}
