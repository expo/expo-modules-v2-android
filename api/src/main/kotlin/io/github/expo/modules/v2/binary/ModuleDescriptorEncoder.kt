package io.github.expo.modules.v2.binary

import io.github.expo.modules.v2.modules.ModuleEventDefinition
import io.github.expo.modules.v2.modules.ModuleFunctionDefinition
import io.github.expo.modules.v2.modules.ModulePropertyDefinition
import io.github.expo.modules.v2.modules.ModuleSharedClassDefinition
import io.github.expo.modules.v2.types.AnyType
import io.github.expo.modules.v2.types.TypeCodesWriter
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
 *   flags: i32                        // bit 0 = async (returns a Promise, takes a PromiseHandle)
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
 *   trampolineName: string            // the static factory `new` lands on
 *   argCount: i32
 *   argCount x { typeCodes: intArray }   // the constructor's parameters
 * }
 * eventCount: i32
 * eventCount x {
 *   jsName: string                    // the name JavaScript subscribes to
 *   payloadTypeCodes: intArray        // how an emitted payload crosses Kotlin -> JS
 * }
 * [reportsMemoryPressure: bool]       // shared classes only, see [encodeSharedClass]
 * ```
 */
internal object ModuleDescriptorEncoder {
  fun encode(
    buf: BinaryBuffer,
    functions: List<ModuleFunctionDefinition>,
    properties: List<ModulePropertyDefinition>,
    sharedClasses: List<ModuleSharedClassDefinition> = emptyList(),
    events: List<ModuleEventDefinition> = emptyList(),
  ): Int {
    return buf.writeDescriptor(
      functions,
      properties,
      sharedClasses,
      events,
    )
  }

  /**
   * A shared class's exports, followed by whether its instances are asked for their memory
   * pressure. A shared class constructs no other classes, so it has none to describe.
   */
  fun encodeSharedClass(
    buf: BinaryBuffer,
    functions: List<ModuleFunctionDefinition>,
    properties: List<ModulePropertyDefinition>,
    events: List<ModuleEventDefinition>,
    reportsMemoryPressure: Boolean,
  ): Int {
    return buf.writeDescriptor(
      functions,
      properties,
      emptyList(),
      events,
    ) {
      putBoolean(reportsMemoryPressure)
    }
  }

  // Not an `encode` overload: with `buf` first in the public signature, an extension of the same
  // name would erase to the same JVM method.
  private fun BinaryBuffer.writeDescriptor(
    functions: List<ModuleFunctionDefinition>,
    properties: List<ModulePropertyDefinition>,
    sharedClasses: List<ModuleSharedClassDefinition>,
    events: List<ModuleEventDefinition>,
    trailer: BinaryBuffer.() -> Unit = {},
  ): Int {
    val currentPosition = position
    // Every type of the descriptor is written through this one, so none needs an array of its own.
    val codes = TypeCodesWriter()

    putInt(0) // payloadEnd placeholder, patched below
    writeFunctions(functions, codes)
    writeProperties(properties, codes)
    writeSharedClasses(sharedClasses, codes)
    writeEvents(events, codes)
    trailer()

    val payloadEnd = position
    position = currentPosition
    putInt(payloadEnd)

    return payloadEnd
  }

  /** [type]'s codes as an `intArray`: `count, code...`. */
  private fun BinaryBuffer.putTypeCodes(type: AnyType, codes: TypeCodesWriter) {
    codes.reset()
    type.writeTypeCodes(codes)
    codes.writeTo(this)
  }

  private fun BinaryBuffer.writeFunctions(
    functions: List<ModuleFunctionDefinition>,
    codes: TypeCodesWriter,
  ) {
    putInt(functions.size)
    for (function in functions) {
      putString(function.jsName)
      putString(function.methodName)
      putInt(function.flags)
      putInt(function.argTypes.size)
      for (argType in function.argTypes) {
        putTypeCodes(argType, codes)
      }
      putTypeCodes(function.returnType, codes)
    }
  }

  private fun BinaryBuffer.writeSharedClasses(
    sharedClasses: List<ModuleSharedClassDefinition>,
    codes: TypeCodesWriter,
  ) {
    putInt(sharedClasses.size)
    for (sharedClass in sharedClasses) {
      putString(sharedClass.jsName)
      putInt(sharedClass.classId)
      putString(sharedClass.trampolineName)
      putInt(sharedClass.argTypes.size)
      for (argType in sharedClass.argTypes) {
        putTypeCodes(argType, codes)
      }
    }
  }

  private fun BinaryBuffer.writeEvents(events: List<ModuleEventDefinition>, codes: TypeCodesWriter) {
    putInt(events.size)
    for (event in events) {
      putString(event.jsName)
      putTypeCodes(event.payloadType, codes)
    }
  }

  private fun BinaryBuffer.writeProperties(
    properties: List<ModulePropertyDefinition>,
    codes: TypeCodesWriter,
  ) {
    putInt(properties.size)
    for (property in properties) {
      putString(property.jsName)
      putString(property.getterName)
      putInt(property.flags)
      putTypeCodes(property.getterType, codes)
      putBoolean(property.setterName != null)
      property.setterName?.let { setterName ->
        putString(setterName)
        putTypeCodes(requireNotNull(property.setterType), codes)
      }
    }
  }
}
