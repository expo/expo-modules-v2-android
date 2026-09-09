package io.github.expo.modules.v2

abstract class SharedObject : ExpoObject() {
  open fun sharedObjectDidRelease() = Unit

  // TODO(@lukmccall): use it in cpp
  open fun getAdditionalMemoryPressure(): Int = 0
}
