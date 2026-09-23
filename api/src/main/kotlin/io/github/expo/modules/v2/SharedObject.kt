package io.github.expo.modules.v2

import io.github.expo.modules.v2.jsi.CurrentRuntime
import java.lang.ref.WeakReference

abstract class SharedObject : ExpoObject {

  constructor() {
    CurrentRuntime.get()?.context?.let { contextRef = WeakReference(it) }
  }

  /** Binds the object to [context]. Safe on any thread. */
  constructor(context: ExpoContext) {
    contextRef = WeakReference(context)
  }

  open fun sharedObjectDidRelease() = Unit

  // TODO(@lukmccall): use it in cpp
  open fun getAdditionalMemoryPressure(): Int = 0
}
