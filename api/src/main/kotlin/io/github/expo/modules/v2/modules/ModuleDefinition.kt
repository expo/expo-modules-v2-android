package io.github.expo.modules.v2.modules

import io.github.expo.modules.v2.binary.toBinaryFlag
import io.github.expo.modules.v2.types.AnyType

internal class ModuleFunctionDefinition(
  val jsName: String,
  val methodName: String,
  val argTypes: Array<out AnyType>,
  val returnType: AnyType,
  val isAsync: Boolean = false,
) {
  val flags: Int
    get() = isAsync.toBinaryFlag(FLAG_ASYNC)

  internal companion object {
    const val FLAG_ASYNC = 1
  }
}

internal class ModulePropertyDefinition(
  val jsName: String,
  val getterName: String,
  val setterName: String?,
  val getterType: AnyType,
  val setterType: AnyType?,
  val isConstant: Boolean = false,
) {
  val flags: Int
    get() = isConstant.toBinaryFlag(FLAG_CONSTANT)

  internal companion object {
    const val FLAG_CONSTANT = 1
  }
}

internal class ModuleSharedClassDefinition(
  val jsName: String,
  val classId: Int,
  val argTypes: Array<out AnyType>,
  val trampolineName: String,
)

internal class ModuleEventDefinition(
  val jsName: String,
  val payloadType: AnyType,
)
