package io.github.expo.modules.v2.unions

import io.github.expo.kolibri.CalledFromNative

/**
 * Used to move [UnionSchema] from Kotlin to C++
 */
@CalledFromNative(by = "expo-modules-v2/jni/JUnionSchemaData.h")
class UnionSchemaData internal constructor(
  @JvmField @field:CalledFromNative val name: String,
  @JvmField @field:CalledFromNative val discriminator: String?,
  @JvmField @field:CalledFromNative val bufferSafe: Boolean,
  @JvmField @field:CalledFromNative val variantKinds: IntArray,
  @JvmField @field:CalledFromNative val variantTags: Array<String>,
  @JvmField @field:CalledFromNative val variantTypes: IntArray,
)
