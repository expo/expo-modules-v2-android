package io.github.expo.modules.v2

@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.BINARY)
annotation class ExpoSharedObject(
  /** Class name reported to JavaScript; defaults to the class's simple name. */
  val name: String = "",
)
