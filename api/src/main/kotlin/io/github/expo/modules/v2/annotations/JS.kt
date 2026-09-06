package io.github.expo.modules.v2.annotations

import kotlin.reflect.KClass

enum class Buffer { AUTO, YES, NO }

@Target(
  AnnotationTarget.CLASS,
  AnnotationTarget.CONSTRUCTOR,
  AnnotationTarget.FUNCTION,
  AnnotationTarget.PROPERTY,
  AnnotationTarget.VALUE_PARAMETER,
)
@Retention(AnnotationRetention.BINARY)
annotation class JS(
  val name: String = "",
  val buffer: Buffer = Buffer.AUTO,
  val returnBuffer: Buffer = Buffer.AUTO,
  val classes: Array<KClass<*>> = [],
)
