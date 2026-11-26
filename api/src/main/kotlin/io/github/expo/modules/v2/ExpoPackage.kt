package io.github.expo.modules.v2

class ExpoPackage(
  /** The modules of the package, sorted by class name. */
  val modules: List<Class<out Module>>,
)
