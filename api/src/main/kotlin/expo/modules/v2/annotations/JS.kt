package expo.modules.v2.annotations

enum class Buffer { AUTO, YES, NO }

@Target(
  AnnotationTarget.CLASS,
  AnnotationTarget.FUNCTION,
  AnnotationTarget.PROPERTY,
  AnnotationTarget.VALUE_PARAMETER,
)
@Retention(AnnotationRetention.BINARY)
annotation class JS(
  val name: String = "",
  val buffer: Buffer = Buffer.AUTO,
  val returnBuffer: Buffer = Buffer.AUTO,
)
