package expo.modules.v2.modules

internal class ModuleFunctionDefinition(
  val jsName: String,
  val methodName: String,
  val argTypes: Array<IntArray>,
  val returnType: IntArray,
  val isAsync: Boolean = false,
) {
  val flags: Int
    get() = if (isAsync) {
      FLAG_ASYNC
    } else {
      0
    }

  internal companion object {
    const val FLAG_ASYNC = 1
  }
}

internal class ModulePropertyDefinition(
  val jsName: String,
  val getterName: String,
  val setterName: String?,
  val getterType: IntArray,
  val setterType: IntArray?,
)
