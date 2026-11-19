package io.github.expo.modules.v2.converters

import io.github.expo.modules.v2.types.CppType
import io.github.expo.modules.v2.types.TypeCodesWriter
import io.github.expo.modules.v2.types.TypeDescriptor
import io.github.expo.modules.v2.types.anyConverter
import io.github.expo.kolibri.binary.BinaryBuffer

sealed class TupleConverter<T : Any>(
  typeDescriptor: TypeDescriptor.Parametrized,
  arity: Int,
) : TypeConverter<T>(typeDescriptor.isNullable) {
  protected val elementConverters: List<TypeConverter<Any>> = run {
    require(typeDescriptor.params.size == arity) {
      "${typeDescriptor.javaClass.simpleName} needs $arity type arguments, got ${typeDescriptor.params.size}"
    }
    typeDescriptor.params.map { it.anyConverter }
  }

  final override val kind: CppType get() = CppType.TUPLE

  final override fun writeTypeParameters(out: TypeCodesWriter) {
    out.add(elementConverters.size)
    elementConverters.forEach { it.writeTypeCodes(out) }
  }

  final override val isPassthrough: Boolean get() = false
}

class PairConverter(
  typeDescriptor: TypeDescriptor.Parametrized,
) : TupleConverter<Pair<*, *>>(typeDescriptor, arity = 2) {
  private val first = elementConverters[0]
  private val second = elementConverters[1]

  override fun fromJni(value: Any?): Pair<*, *>? = handleNullable(value) { bridgeValue ->
    bridgeValue as List<*>
    Pair(first.fromJni(bridgeValue[0]), second.fromJni(bridgeValue[1]))
  }

  override fun toJni(value: Pair<*, *>?): Any? =
    value?.let { (a, b) -> listOf(first.toJni(a), second.toJni(b)) }

  override fun writeToBuffer(buf: BinaryBuffer, value: Pair<*, *>?) =
    buf.writePresent(value) { (a, b) ->
      first.writeToBuffer(buf, a)
      second.writeToBuffer(buf, b)
    }

  override fun readFromBuffer(buf: BinaryBuffer): Pair<*, *>? = buf.readPresent {
    Pair(first.readFromBuffer(buf), second.readFromBuffer(buf))
  }
}

class TripleConverter(
  typeDescriptor: TypeDescriptor.Parametrized,
) : TupleConverter<Triple<*, *, *>>(typeDescriptor, arity = 3) {
  private val first = elementConverters[0]
  private val second = elementConverters[1]
  private val third = elementConverters[2]

  override fun fromJni(value: Any?): Triple<*, *, *>? = handleNullable(value) { bridgeValue ->
    bridgeValue as List<*>
    Triple(
      first.fromJni(bridgeValue[0]),
      second.fromJni(bridgeValue[1]),
      third.fromJni(bridgeValue[2]),
    )
  }

  override fun toJni(value: Triple<*, *, *>?): Any? =
    value?.let { (a, b, c) -> listOf(first.toJni(a), second.toJni(b), third.toJni(c)) }

  override fun writeToBuffer(buf: BinaryBuffer, value: Triple<*, *, *>?) =
    buf.writePresent(value) { (a, b, c) ->
      first.writeToBuffer(buf, a)
      second.writeToBuffer(buf, b)
      third.writeToBuffer(buf, c)
    }

  override fun readFromBuffer(buf: BinaryBuffer): Triple<*, *, *>? = buf.readPresent {
    Triple(first.readFromBuffer(buf), second.readFromBuffer(buf), third.readFromBuffer(buf))
  }
}
