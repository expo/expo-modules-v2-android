package io.github.expo.modules.v2.converters

import io.github.expo.kolibri.binary.BinaryBuffer
import io.github.expo.modules.v2.Enumerable
import io.github.expo.modules.v2.logging.platformLogger
import io.github.expo.modules.v2.types.CppType
import io.github.expo.modules.v2.types.TypeCodes
import java.lang.reflect.Field
import java.lang.reflect.Modifier
import java.util.concurrent.ConcurrentHashMap

class EnumConverter(
  private val enumClass: Class<out Enum<*>>,
  isNullable: Boolean,
) : TypeConverter<Enum<*>>(isNullable) {
  init {
    // Checked first: under R8, an enum without the marker may have lost the entries read below.
    if (!Enumerable::class.java.isAssignableFrom(enumClass) && warnedEnums.add(enumClass)) {
      // The interface by its source name: R8 may rename it.
      platformLogger.warn(
        "${enumClass.name} does not implement io.github.expo.modules.v2.Enumerable. It crosses to " +
          "JavaScript here, but R8 keeps an enum's entries and its property only when it does, so " +
          "a release build may fail on it or send its entry names instead",
      )
    }
  }

  // Null only when R8 removed `values()`, which it keeps for an `Enumerable`.
  private val constants: Array<out Enum<*>> = requireNotNull(enumClass.enumConstants) {
    "${enumClass.name} lost its entries to R8: an enum that crosses to JavaScript must implement " +
      "io.github.expo.modules.v2.Enumerable"
  }

  /** The one property the entries cross by, or null when they cross by name. */
  private val property: Field? = propertyOf(enumClass)

  private val isIntBacked: Boolean = property?.type == Int::class.javaPrimitiveType

  /** The JavaScript value of each entry, by ordinal: a `String`, or an `Int` when [isIntBacked]. */
  private val jsValues = Array(constants.size) { ordinal ->
    property?.get(constants[ordinal]) ?: constants[ordinal].name
  }

  private val constantsByValue: Map<Any, Enum<*>> =
    jsValues
      .mapIndexed { ordinal, value -> value to constants[ordinal] }
      .toMap()

  override val codes: TypeCodes
    get() = when {
      !isIntBacked -> CppType.STRING.nullable(isNullable)
      // Like any Int: a register-width slot when it cannot be null, a boxed one when it can.
      isNullable -> CppType.BOX_INT.nullable(true)
      else -> CppType.INT
    }.toTypeCodes()

  override val isPassthrough: Boolean get() = false

  override fun fromJni(value: Any?): Enum<*>? =
    handleNullable(value) { entryOf(it) }

  override fun toJni(value: Enum<*>?): Any? =
    value?.let { jsValues[it.ordinal] }

  override fun writeToBuffer(buf: BinaryBuffer, value: Enum<*>?) =
    buf.writePresent(value) {
      if (isIntBacked) {
        putInt(jsValues[it.ordinal] as Int)
      } else {
        putString(jsValues[it.ordinal] as String)
      }
    }

  override fun readFromBuffer(buf: BinaryBuffer): Enum<*>? =
    buf.readPresent {
      entryOf(
        if (isIntBacked) {
          getInt()
        } else {
          getString()
        }
      )
    }

  private fun entryOf(value: Any): Enum<*> =
    constantsByValue[value] ?: throw IllegalArgumentException(
      "'$value' is not a valid ${enumClass.simpleName}. Pass one of: " +
        jsValues.joinToString { if (isIntBacked) "$it" else "'$it'" },
    )

  private companion object {
    /** The enums already warned about: one converter is made per enum and nullability. */
    private val warnedEnums: MutableSet<Class<*>> = ConcurrentHashMap.newKeySet()

    fun propertyOf(enumClass: Class<out Enum<*>>): Field? {
      // TODO(@lukmccall): remove reflection
      val properties = enumClass.declaredFields.filter {
        !Modifier.isStatic(it.modifiers) && !it.isSynthetic
      }

      if (properties.isEmpty()) {
        return null
      }

      val property = properties.singleOrNull()?.takeIf {
        it.type == String::class.java || it.type == Int::class.javaPrimitiveType
      } ?: throw IllegalArgumentException(
        "${enumClass.name} cannot cross to JavaScript: an enum crosses by its entry names, or by " +
          "the value of its only property when that property is a String or an Int, but it " +
          "declares " + properties.joinToString { "${it.name}: ${it.type.simpleName}" } + ". Give " +
          "it a single String or Int property, or remove its properties",
      )

      property.isAccessible = true
      return property
    }
  }
}
