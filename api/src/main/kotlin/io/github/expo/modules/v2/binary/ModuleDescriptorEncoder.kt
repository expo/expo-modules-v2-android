package io.github.expo.modules.v2.binary

import io.github.expo.modules.v2.modules.ModuleFunctionDefinition
import io.github.expo.modules.v2.modules.ModulePropertyDefinition
import io.github.expo.modules.v2.modules.ModuleSharedClassDefinition
import io.github.expo.kolibri.binary.BinaryBuffer

/**
 * Encodes a module descriptor into the bridge buffer:
 *
 * ```
 * payloadEnd: i32                     // total payload size in bytes, these 4 bytes included
 * functionCount: i32
 * functionCount x {
 *   jsName: string                    // i32 UTF-8 byte length + bytes
 *   methodName: string                // selected JVM method
 *   flags: i32                        // bit 0 = async (returns a Promise, takes one)
 *   argCount: i32
 *   argCount x { typeCodes: intArray }   // i32 count + count x i32
 *   returnTypeCodes: intArray
 * }
 * propertyCount: i32
 * propertyCount x {
 *   jsName: string
 *   getterName: string                // selected JVM getter
 *   getterTypeCodes: intArray         // how a read crosses
 *   hasSetter: bool
 *   [setterName: string]              // selected JVM setter
 *   [setterTypeCodes: intArray]       // how a write crosses, which need not match the read
 * }
 * sharedClassCount: i32
 * sharedClassCount x {
 *   jsName: string                    // the constructable class's name on the module
 *   classId: i32                      // SharedObjectRegistry's id for it
 *   argCount: i32
 *   argCount x { typeCodes: intArray }   // the constructor's parameters
 * }
 * ```
 */
internal object ModuleDescriptorEncoder {
  fun encode(
    functions: List<ModuleFunctionDefinition>,
    properties: List<ModulePropertyDefinition>,
    buf: BinaryBuffer,
    sharedClasses: List<ModuleSharedClassDefinition> = emptyList(),
  ): Int {
    return buf.encode(
      functions,
      properties,
      sharedClasses
    )
  }

  private fun BinaryBuffer.encode(
    functions: List<ModuleFunctionDefinition>,
    properties: List<ModulePropertyDefinition>,
    sharedClasses: List<ModuleSharedClassDefinition>,
  ): Int {
    val currentPosition = position

    putInt(0) // payloadEnd placeholder, patched below
    writeFunctions(functions)
    writeProperties(properties)
    writeSharedClasses(sharedClasses)

    val payloadEnd = position
    position = currentPosition
    putInt(payloadEnd)

    return payloadEnd
  }

  private fun BinaryBuffer.writeFunctions(functions: List<ModuleFunctionDefinition>) {
    putInt(functions.size)
    for (function in functions) {
      putString(function.jsName)
      putString(function.methodName)
      putInt(function.flags)
      putInt(function.argTypes.size)
      for (argType in function.argTypes) {
        putIntArray(argType)
      }
      putIntArray(function.returnType)
    }
  }

  private fun BinaryBuffer.writeSharedClasses(sharedClasses: List<ModuleSharedClassDefinition>) {
    putInt(sharedClasses.size)
    for (sharedClass in sharedClasses) {
      putString(sharedClass.jsName)
      putInt(sharedClass.classId)
      putString(sharedClass.trampolineName)
      putInt(sharedClass.argTypes.size)
      for (argType in sharedClass.argTypes) {
        putIntArray(argType)
      }
    }
  }

  private fun BinaryBuffer.writeProperties(properties: List<ModulePropertyDefinition>) {
    putInt(properties.size)
    for (property in properties) {
      putString(property.jsName)
      putString(property.getterName)
      putIntArray(property.getterType)
      putBoolean(property.setterName != null)
      property.setterName?.let { setterName ->
        putString(setterName)
        putIntArray(requireNotNull(property.setterType))
      }
    }
  }
}
