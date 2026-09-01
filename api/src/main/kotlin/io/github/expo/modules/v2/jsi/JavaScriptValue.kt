package io.github.expo.modules.v2.jsi

import io.github.expo.kolibri.CalledFromNative
import io.github.expo.kolibri.NativeMethod
import io.github.expo.kolibri.NativeObject
import io.github.expo.kolibri.NativePointer

class JavaScriptValue @CalledFromNative(by = "expo-modules-v2/jsi/JavaScriptValue.h") private constructor(
  pointer: Long,
) : NativeObject(NativePointer(pointer)) {

  enum class Kind(val code: Int) {
    UNDEFINED(0),
    NULL(1),
    BOOLEAN(2),
    BIG_INT(3),
    NUMBER(4),
    STRING(5),
    SYMBOL(6),
    OBJECT(7);

    internal companion object {
      fun fromCode(code: Int): Kind =
        entries
          .firstOrNull { it.code == code }
          ?: error("Unknown JavaScriptValue kind code: $code")
    }
  }

  fun kind(): Kind = Kind.fromCode(kindCode())

  //@formatter:off
  @NativeMethod private external fun kindCode(): Int

  @NativeMethod external fun isNull(): Boolean
  @NativeMethod external fun isUndefined(): Boolean
  @NativeMethod external fun isBool(): Boolean
  @NativeMethod external fun isNumber(): Boolean
  @NativeMethod external fun isString(): Boolean
  @NativeMethod external fun isSymbol(): Boolean
  @NativeMethod external fun isFunction(): Boolean
  @NativeMethod external fun isArray(): Boolean
  @NativeMethod external fun isObject(): Boolean

  @NativeMethod external fun getBool(): Boolean
  @NativeMethod external fun getDouble(): Double
  @NativeMethod external fun getString(): String
  @NativeMethod external fun getObject(): JavaScriptObject
  @NativeMethod external fun getArray(): Array<JavaScriptValue>
  //@formatter:on

  fun getInt(): Int = getDouble().toInt()
  fun getLong(): Long = getDouble().toLong()
  fun getFloat(): Float = getDouble().toFloat()

  override fun toString(): String = "JavaScriptValue(${kind()})"
}
