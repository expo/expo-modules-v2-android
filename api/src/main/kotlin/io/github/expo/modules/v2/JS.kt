package io.github.expo.modules.v2

enum class Buffer { AUTO, YES, NO }

@Target(
  AnnotationTarget.CONSTRUCTOR,
  AnnotationTarget.FUNCTION,
  AnnotationTarget.PROPERTY,
)
@Retention(AnnotationRetention.BINARY)
annotation class JS(
  /** Export name reported to JavaScript; defaults to the member's own name. */
  val name: String = "",
)
