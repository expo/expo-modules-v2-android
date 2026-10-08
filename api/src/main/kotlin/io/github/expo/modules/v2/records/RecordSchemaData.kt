package io.github.expo.modules.v2.records

import io.github.expo.kolibri.CalledFromNative

/**
 * Used to move [RecordSchema] from Kotlin to C++
 */
@CalledFromNative(by = "expo-modules-v2/jni/JRecordSchemaData.h")
class RecordSchemaData internal constructor(
  @JvmField @field:CalledFromNative val name: String,
  @JvmField @field:CalledFromNative val jniDescriptor: String,
  @JvmField @field:CalledFromNative val bufferSafe: Boolean,
  @JvmField @field:CalledFromNative val fieldNames: Array<String>,
  @JvmField @field:CalledFromNative val fieldTypes: IntArray,
  @JvmField @field:CalledFromNative val fieldOptional: BooleanArray,
)
