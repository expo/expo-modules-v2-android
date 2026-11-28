package io.github.expo.modules.v2.react.views

import android.content.Context
import android.widget.FrameLayout
import io.github.expo.modules.v2.views.EncodedViewPropsReader
import io.github.expo.modules.v2.views.MountedViewProps
import io.github.expo.modules.v2.views.PropsDiff
import io.github.expo.modules.v2.views.ViewPropsDecoder

/**
 * PROTOTYPE. A view of Expo Modules v2, the Android counterpart of iOS's `ExpoNativeView`.
 *
 * Its props are encoded on the thread that committed them and decoded on the UI thread when they
 * mount - only the ones that changed since the props the view has. The view gets one [didUpdateProps]
 * per mount with what changed.
 */
abstract class ExpoNativeView<P : Any>(context: Context) : FrameLayout(context) {
  /** The props the view has mounted, or null before the first update. */
  var mountedProps: MountedViewProps<P>? = null
    private set

  /** The current props, or null before the first [didUpdateProps]. */
  val props: P?
    get() = mountedProps?.props

  /**
   * Called on the UI thread when the view gets new props, with what changed since the props it had.
   * The first call has all props, also when React set none of them.
   */
  open fun didUpdateProps(diff: PropsDiff<P>) = Unit

  /** Mounts the props [handle] stands for. Does nothing when it no longer resolves or nothing changed. */
  internal fun mount(handle: Long, decoder: ViewPropsDecoder<P>, reader: EncodedViewPropsReader) {
    decoder.mount(handle, mountedProps, reader)?.let(::apply)
  }

  /** Gives a view that React set none of the props of its defaults, once. */
  internal fun mountInitialIfNeeded(decoder: ViewPropsDecoder<P>) {
    if (mountedProps == null) {
      apply(PropsDiff(old = null, new = decoder.initial(), changedProps = decoder.allPropsMask()))
    }
  }

  private fun apply(diff: PropsDiff<P>) {
    mountedProps = diff.new
    didUpdateProps(diff)
  }
}
