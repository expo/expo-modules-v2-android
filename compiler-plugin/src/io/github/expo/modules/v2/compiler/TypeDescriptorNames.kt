package io.github.expo.modules.v2.compiler

import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.Name

object TypeDescriptorNames {
  fun primitiveSingleton(fqName: String): ClassId? =
    PRIMITIVE[builtInName(fqName)]?.let(Identifiers.Classes::typeDescriptorNested)

  fun primitiveArray(fqName: String): ClassId? =
    PRIMITIVE_ARRAY[builtInName(fqName)]?.let(Identifiers.Classes::typeDescriptorNested)

  fun commonField(fqName: String, isNullable: Boolean): Name? =
    COMMON_FIELDS[builtInName(fqName)]?.let { (nonNull, nullable) ->
      Name.identifier(if (isNullable) nullable else nonNull)
    }

  private fun builtInName(fqName: String): String =
    if (fqName.startsWith("kotlin.") && !fqName.removePrefix("kotlin.").contains('.')) {
      fqName.removePrefix("kotlin.")
    } else {
      fqName
    }

  private val PRIMITIVE: Map<String, String> = mapOf(
    "Int" to "Int",
    "Long" to "Long",
    "Float" to "Float",
    "Double" to "Double",
    "Boolean" to "Bool",
  )

  private val PRIMITIVE_ARRAY: Map<String, String> = mapOf(
    "IntArray" to "IntArray",
    "LongArray" to "LongArray",
    "FloatArray" to "FloatArray",
    "DoubleArray" to "DoubleArray",
    "BooleanArray" to "BooleanArray",
    "ByteArray" to "ByteArray",
  )

  private val COMMON_FIELDS: Map<String, Pair<String, String>> = mapOf(
    "Boolean" to ("BOOLEAN_BOXED" to "BOOLEAN_BOXED_NULL"),
    "Int" to ("INT_BOXED" to "INT_BOXED_NULL"),
    "Long" to ("LONG_BOXED" to "LONG_BOXED_NULL"),
    "Float" to ("FLOAT_BOXED" to "FLOAT_BOXED_NULL"),
    "Double" to ("DOUBLE_BOXED" to "DOUBLE_BOXED_NULL"),
    "String" to ("STRING" to "STRING_NULL"),
    "Any" to ("ANY" to "ANY_NULL"),
  )
}
