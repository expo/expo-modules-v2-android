package io.github.expo.modules.v2.sharedobjects

abstract class SharedObject {
  @Volatile
  internal var sharedObjectId: Int = UNASSIGNED

  open fun sharedObjectDidRelease() = Unit

  // TODO(@lukmccall): use it in cpp
  open fun getAdditionalMemoryPressure(): Int = 0

  internal companion object {
    const val UNASSIGNED: Int = 0
    const val RELEASED: Int = -1
  }
}
