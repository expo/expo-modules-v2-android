package io.github.expo.modules.v2.views

import io.github.expo.kolibri.CalledFromNative
import io.github.expo.kolibri.binary.BinaryBuffer
import io.github.expo.kolibri.nativePointerOf
import io.github.expo.modules.v2.binary.newSharedView
import io.github.expo.modules.v2.binary.rewind
import io.github.expo.modules.v2.core.ExpoModulesV2
import io.github.expo.modules.v2.jsi.JavaScriptValue

/**
 * Where a view reads its encoded props from when the UI thread mounts them. Native in an app; tests
 * on the JVM, which can't load the bridge, write the same payload from Kotlin.
 */
fun interface EncodedViewPropsReader {
  /**
   * Returns a buffer positioned at the props of [handle] that changed since [mountedRevisions] (the
   * view's, one per prop, or null for a view with none), in the layout `EncodedViewProps::writeChanges`
   * documents. Null when the handle no longer resolves: a later commit replaced those props.
   */
  fun readChanges(handle: Long, mountedRevisions: LongArray?): BinaryBuffer?
}

/**
 * PROTOTYPE. The natives of the props codec.
 *
 * In an app the component descriptor owns the codec and the encoded props, and only [readChanges] is
 * called from Kotlin. The rest lets a desktop test run the same native encoder on props objects from a
 * Hermes runtime, without React Native.
 */
@CalledFromNative(by = "expo-modules-v2/views/ViewPropsNatives.cpp")
object ViewPropsNatives : EncodedViewPropsReader {
  init {
    ExpoModulesV2.load()
  }

  override fun readChanges(handle: Long, mountedRevisions: LongArray?): BinaryBuffer? {
    val length = nativeReadChanges(handle, mountedRevisions)
    if (length < 0) {
      return null
    }
    return BinaryBuffer.newSharedView().rewind(length)
  }

  /** Creates the native codec for [decoder] and returns its address. Free it with [destroyCodec]. */
  fun createCodec(decoder: ViewPropsDecoder<*>): Long = nativeCreateCodec(decoder)

  fun destroyCodec(pointer: Long) = nativeDestroyCodec(pointer)

  /**
   * Test-only. Encodes the props object [props] holds on top of the props [previousOwner] owns, as
   * `cloneProps` does, and returns an owner of the result, or 0 when nothing changed. An owner stands
   * in for the Fabric props that keep encoded props alive; release it with [releaseOwner].
   */
  fun encodeForTesting(codecPointer: Long, props: JavaScriptValue, previousOwner: Long): Long =
    nativeEncodeForTesting(codecPointer, nativePointerOf(props), previousOwner)

  /** Test-only. The handle the props of [owner] travel under. */
  fun handleOf(owner: Long): Long = nativeHandleOf(owner)

  /** Test-only. Drops [owner]'s reference, as Fabric does when it frees the props. */
  fun releaseOwner(owner: Long) = nativeReleaseOwner(owner)

  @JvmStatic
  private external fun nativeCreateCodec(decoder: Any): Long

  @JvmStatic
  private external fun nativeDestroyCodec(pointer: Long)

  @JvmStatic
  private external fun nativeReadChanges(handle: Long, mountedRevisions: LongArray?): Int

  @JvmStatic
  private external fun nativeEncodeForTesting(codecPointer: Long, valuePointer: Long, previousOwner: Long): Long

  @JvmStatic
  private external fun nativeHandleOf(owner: Long): Long

  @JvmStatic
  private external fun nativeReleaseOwner(owner: Long)
}
