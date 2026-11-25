package io.github.expo.modules.v2.async

/** Something waiting on the JS thread that has to fail when the runtime goes away. */
internal fun interface RuntimeCloseListener {
  fun onRuntimeClosed()
}
