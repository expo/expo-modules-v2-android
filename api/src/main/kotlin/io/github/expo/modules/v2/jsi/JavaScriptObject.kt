package io.github.expo.modules.v2.jsi

import io.github.expo.kolibri.AsNativePointer
import io.github.expo.kolibri.CalledFromNative
import io.github.expo.kolibri.NativeMethod
import io.github.expo.kolibri.NativeObject
import io.github.expo.kolibri.NativePointer

class JavaScriptObject @CalledFromNative(by = "expo-modules-v2/jsi/JavaScriptObject.h") private constructor(
  pointer: Long,
) : NativeObject(NativePointer(pointer)) {

  //@formatter:off
  @NativeMethod external fun isArray(): Boolean
  @NativeMethod external fun isArrayBuffer(): Boolean

  @NativeMethod external fun hasProperty(name: String): Boolean

  @NativeMethod external fun getProperty(name: String): JavaScriptValue

  @NativeMethod external fun getPropertyNames(): Array<String>

  @NativeMethod external fun getArray(): Array<JavaScriptValue>

  @NativeMethod private external fun setBoolProperty(name: String, value: Boolean)
  @NativeMethod private external fun setDoubleProperty(name: String, value: Double)
  @NativeMethod private external fun setStringProperty(name: String, value: String?)
  @NativeMethod private external fun setJSValueProperty(name: String, @AsNativePointer value: JavaScriptValue?)
  @NativeMethod private external fun setObjectProperty(name: String, @AsNativePointer value: JavaScriptObject?)

  @NativeMethod external fun unsetProperty(name: String)
  //@formatter:on

  fun setProperty(name: String, value: Boolean): Unit = setBoolProperty(name, value)
  fun setProperty(name: String, value: Int): Unit = setDoubleProperty(name, value.toDouble())
  fun setProperty(name: String, value: Double): Unit = setDoubleProperty(name, value)
  fun setProperty(name: String, value: String?): Unit = setStringProperty(name, value)
  fun setProperty(name: String, value: JavaScriptValue?): Unit = setJSValueProperty(name, value)
  fun setProperty(name: String, value: JavaScriptObject?): Unit = setObjectProperty(name, value)
  fun setProperty(name: String, @Suppress("UNUSED_PARAMETER") `null`: Nothing?): Unit = unsetProperty(name)

  operator fun get(name: String): JavaScriptValue = getProperty(name)

  operator fun set(name: String, value: Boolean): Unit = setProperty(name, value)
  operator fun set(name: String, value: Int): Unit = setProperty(name, value)
  operator fun set(name: String, value: Double): Unit = setProperty(name, value)
  operator fun set(name: String, value: String?): Unit = setProperty(name, value)
  operator fun set(name: String, value: JavaScriptValue?): Unit = setProperty(name, value)
  operator fun set(name: String, value: JavaScriptObject?): Unit = setProperty(name, value)
  operator fun set(name: String, @Suppress("UNUSED_PARAMETER") `null`: Nothing?): Unit = unsetProperty(name)
}
