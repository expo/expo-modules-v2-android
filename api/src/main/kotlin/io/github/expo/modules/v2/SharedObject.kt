package io.github.expo.modules.v2

import io.github.expo.kolibri.CalledFromNative
import io.github.expo.modules.v2.jsi.CurrentRuntime
import java.lang.ref.WeakReference

@CalledFromNative(by = "expo-modules-v2/jni/JSharedObject.h")
abstract class SharedObject : ExpoObject {

  constructor() {
    CurrentRuntime.get()?.context?.let { contextRef = WeakReference(it) }
  }

  /** Binds the object to [context]. Safe on any thread. */
  constructor(context: ExpoContext) {
    contextRef = WeakReference(context)
  }

  open fun sharedObjectDidRelease() = Unit

  /**
   * The native memory, in bytes, this object holds beyond what the JavaScript heap sees.
   */
  @CalledFromNative(by = "expo-modules-v2/jni/JSharedObject.h")
  open fun getAdditionalMemoryPressure(): Int = 0
}
