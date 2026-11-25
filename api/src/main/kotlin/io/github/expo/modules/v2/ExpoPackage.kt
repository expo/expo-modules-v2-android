package io.github.expo.modules.v2

/**
 * What `discoveredExpoPackages()` found in one package: its modules. Later kinds of exports, such
 * as shared object classes, are added as properties with defaults.
 */
class ExpoPackage(
  /** The modules of the package, sorted by class name. */
  val modules: List<Class<out Module>>,
)
