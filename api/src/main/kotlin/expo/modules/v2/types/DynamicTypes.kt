package expo.modules.v2.types

import expo.modules.v2.jsi.JavaScriptObject
import expo.modules.v2.jsi.JavaScriptValue
import expo.modules.v2.records.RecordRegistry
import io.github.expo.kolibri.CalledFromNative

object DynamicTypes {
  const val UNKNOWN = -1

  @JvmStatic
  @CalledFromNative(by = "expo-modules-v2/jni/JDynamicTypes.h")
  fun kindOf(value: Any): Int = when (value) {
    is String -> CppType.STRING.code
    is Int -> CppType.INT.code
    is Double -> CppType.DOUBLE.code
    is Boolean -> CppType.BOOLEAN.code
    is List<*> -> CppType.LIST.code
    is Map<*, *> -> CppType.MAP.code
    is Long -> CppType.LONG.code
    is Float -> CppType.FLOAT.code
    is DoubleArray -> CppType.DOUBLE_ARRAY.code
    is IntArray -> CppType.INT_ARRAY.code
    is LongArray -> CppType.LONG_ARRAY.code
    is FloatArray -> CppType.FLOAT_ARRAY.code
    is BooleanArray -> CppType.BOOLEAN_ARRAY.code
    is ByteArray -> CppType.BYTE_ARRAY.code
    is JavaScriptValue -> CppType.JS_VALUE.code
    is JavaScriptObject -> CppType.JS_OBJECT.code
    is Unit -> CppType.UNIT.code
    else -> if (RecordRegistry.typeFor(value.javaClass) != null) {
      CppType.RECORD.code
    } else {
      UNKNOWN
    }
  }
}
