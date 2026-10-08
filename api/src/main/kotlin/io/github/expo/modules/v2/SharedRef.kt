package io.github.expo.modules.v2

import io.github.expo.kolibri.CalledFromNative

abstract class SharedRef<T : Any> : SharedObject {
  val ref: T

  constructor(ref: T) : super() {
    this.ref = ref
  }

  constructor(context: ExpoContext, ref: T) : super(context) {
    this.ref = ref
  }

  // SharedObjectRegistry exports it on every shared ref class, by its accessor's name.
  @get:CalledFromNative(by = "expo-modules-v2/descriptor/HostFunctionSpec.cpp")
  open val nativeRefType: String
    get() = ref.javaClass.simpleName
}
