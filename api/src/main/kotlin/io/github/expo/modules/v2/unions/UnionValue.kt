package io.github.expo.modules.v2.unions

import io.github.expo.kolibri.CalledFromNative

/**
 * A union value on its JNI path: which variant it is, and the bridge value of what that variant
 * crosses as. Native code picks [index] from the JavaScript value, so the variant never has to be
 * guessed on this side.
 */
@CalledFromNative(by = "expo-modules-v2/jni/JUnionValue.h")
class UnionValue @CalledFromNative constructor(
  @JvmField @field:CalledFromNative val index: Int,
  @JvmField @field:CalledFromNative val payload: Any?,
)
