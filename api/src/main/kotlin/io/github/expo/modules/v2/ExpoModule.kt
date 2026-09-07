package io.github.expo.modules.v2

import kotlin.reflect.KClass

@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.BINARY)
annotation class ExpoModule(
  val name: String = "",
  val classes: Array<KClass<*>> = [],
)
