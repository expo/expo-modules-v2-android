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
  private val propertyName: String,
  val getterType: AnyType,
  val setterType: AnyType?,
  val isConstant: Boolean = false,
) {
  val getterName: String
    get() = if (hasIsPrefix(propertyName)) {
      propertyName
    } else {
      accessorName("get", propertyName)
    }

  val setterName: String?
    get() = when {
      setterType == null -> null
      hasIsPrefix(propertyName) -> accessorName("set", propertyName, from = 2)
      else -> accessorName("set", propertyName)
    }

  val flags: Int
    get() = isConstant.toBinaryFlag(FLAG_CONSTANT)

  internal companion object {
    const val FLAG_CONSTANT = 1

    private fun hasIsPrefix(name: String): Boolean =
      name.length > 2 && name.startsWith("is") && !isAsciiLowerCase(name[2])

    private fun accessorName(prefix: String, name: String, from: Int = 0): String {
      val builder = StringBuilder(
        prefix.length + name.length - from
      ).append(prefix)

      if (from < name.length && isAsciiLowerCase(name[from])) {
        builder.append(name[from] - ('a' - 'A')).append(name, from + 1, name.length)
      } else {
        builder.append(name, from, name.length)
      }

      return builder.toString()
    }

    private fun isAsciiLowerCase(char: Char): Boolean = char in 'a'..'z'
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
