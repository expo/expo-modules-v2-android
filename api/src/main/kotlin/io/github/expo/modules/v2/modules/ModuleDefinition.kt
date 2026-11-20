package io.github.expo.modules.v2.modules

import io.github.expo.modules.v2.types.AnyType

internal class ModuleFunctionDefinition(
  val jsName: String,
  val methodName: String,
  val argTypes: Array<out AnyType>,
  val returnType: AnyType,
  val isAsync: Boolean = false,
) {
  val flags: Int
    get() = if (isAsync) {
      FLAG_ASYNC
    } else {
      0
    }

  override fun toString(): String = buildString {
    append("function $jsName(${argTypes.joinToString()}): $returnType")
    if (isAsync) append("; async")
    append("; method $methodName")
  }

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
    get() = if (isConstant) {
      FLAG_CONSTANT
    } else {
      0
    }

  override fun toString(): String = buildString {
    append("property $jsName: $getterType")
    if (isConstant) append("; constant")
    append("; getter $getterName")
    if (setterName != null) append("; setter $setterName($setterType)")
  }

  internal companion object {
    const val FLAG_CONSTANT = 1
  }
}

internal class ModuleSharedClassDefinition(
  val jsName: String,
  val classId: Int,
  val argTypes: Array<out AnyType>,
  val trampolineName: String,
) {
  // The class id is assigned at registration time, so it is left out: the line names the
  // constructor JavaScript calls instead.
  override fun toString(): String =
    "sharedClass $jsName(${argTypes.joinToString()}); constructor $trampolineName"
}

internal class ModuleEventDefinition(
  val jsName: String,
  val payloadType: AnyType,
) {
  override fun toString(): String = "event $jsName: $payloadType"
}
