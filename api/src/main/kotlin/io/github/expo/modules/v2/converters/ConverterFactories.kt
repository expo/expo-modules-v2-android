package io.github.expo.modules.v2.converters

import io.github.expo.modules.v2.SharedObject
import io.github.expo.modules.v2.TypedArray
import io.github.expo.modules.v2.cache.ClassCache
import io.github.expo.modules.v2.records.RecordRegistry
import io.github.expo.modules.v2.sharedobjects.SharedObjectRegistry
import io.github.expo.modules.v2.types.ParametrizedConverterFactory
import io.github.expo.modules.v2.types.SimpleConverterFactory
import io.github.expo.modules.v2.types.TypeDescriptor
import io.github.expo.modules.v2.unions.UnionRegistry

/**
 * A factory whose converter depends on the descriptor's class: an enum, a shared object, a record.
 * Converters are cached per class and nullability, in one cache all such factories share: the
 * plugin picks exactly one of them for a class, so their keys never overlap.
 */
abstract class ClassConverterFactory : SimpleConverterFactory {
  protected abstract fun newConverter(type: Class<*>, isNullable: Boolean): TypeConverter<*>

  final override fun create(descriptor: TypeDescriptor.Simple): TypeConverter<*> {
    val type = descriptor.javaClass
    val isNullable = descriptor.isNullable
    val cachedConverter = sharedCache.get(type, isNullable)
    if (cachedConverter != null) {
      return cachedConverter
    }

    val converter = newConverter(type, isNullable)
    sharedCache.put(type, isNullable, converter)
    return converter
  }

  private companion object {
    val sharedCache = ClassCache()
  }
}

object EnumConverterFactory : ClassConverterFactory() {
  @Suppress("UNCHECKED_CAST")
  override fun newConverter(type: Class<*>, isNullable: Boolean): TypeConverter<*> =
    EnumConverter(type as Class<out Enum<*>>, isNullable)
}

object SharedObjectConverterFactory : ClassConverterFactory() {
  @Suppress("UNCHECKED_CAST")
  override fun newConverter(type: Class<*>, isNullable: Boolean): TypeConverter<*> {
    val sharedClass = type as Class<out SharedObject>
    return SharedObjectConverter(SharedObjectRegistry.classIdFor(sharedClass), sharedClass, isNullable)
  }
}

object TypedArrayConverterFactory : ClassConverterFactory() {
  @Suppress("UNCHECKED_CAST")
  override fun newConverter(type: Class<*>, isNullable: Boolean): TypeConverter<*> =
    TypedArrayConverter(type as Class<out TypedArray>, isNullable)
}

object UnionConverterFactory : ClassConverterFactory() {
  override fun newConverter(type: Class<*>, isNullable: Boolean): TypeConverter<*> {
    val unionType = requireNotNull(UnionRegistry.typeFor(type)) {
      "${type.name} is a Union with no registered UnionCodec - is the compiler plugin applied?"
    }
    return UnionConverter(unionType, isNullable)
  }
}

object RecordConverterFactory : ClassConverterFactory() {
  override fun newConverter(type: Class<*>, isNullable: Boolean): TypeConverter<*> {
    val recordType = requireNotNull(RecordRegistry.typeFor(type)) {
      "${type.name} is a Record with no registered RecordCodec - is the compiler plugin applied?"
    }
    return RecordConverter(recordType, isNullable)
  }
}

object ListConverterFactory : ParametrizedConverterFactory {
  override fun create(descriptor: TypeDescriptor.Parametrized): TypeConverter<*> =
    ListConverter(descriptor)
}

object SetConverterFactory : ParametrizedConverterFactory {
  override fun create(descriptor: TypeDescriptor.Parametrized): TypeConverter<*> =
    SetConverter<Any>(descriptor)
}

object CollectionConverterFactory : ParametrizedConverterFactory {
  override fun create(descriptor: TypeDescriptor.Parametrized): TypeConverter<*> =
    CollectionConverter(descriptor)
}

object MapConverterFactory : ParametrizedConverterFactory {
  override fun create(descriptor: TypeDescriptor.Parametrized): TypeConverter<*> =
    MapConverter(descriptor)
}

object ArrayConverterFactory : ParametrizedConverterFactory {
  override fun create(descriptor: TypeDescriptor.Parametrized): TypeConverter<*> =
    ArrayConverter<Any>(descriptor)
}

object PairConverterFactory : ParametrizedConverterFactory {
  override fun create(descriptor: TypeDescriptor.Parametrized): TypeConverter<*> =
    PairConverter(descriptor)
}

object TripleConverterFactory : ParametrizedConverterFactory {
  override fun create(descriptor: TypeDescriptor.Parametrized): TypeConverter<*> =
    TripleConverter(descriptor)
}

object SharedRefConverterFactory : ParametrizedConverterFactory {
  override fun create(descriptor: TypeDescriptor.Parametrized): TypeConverter<*> {
    @Suppress("UNCHECKED_CAST")
    val sharedClass = descriptor.javaClass as Class<out SharedObject>
    val refClass = (descriptor.params.singleOrNull() as? TypeDescriptor.Simple)?.javaClass
      ?: throw IllegalArgumentException(
        "A SharedRef parameter must name the type it carries, as SharedRef<Something>",
      )

    return SharedRefConverter(
      SharedObjectRegistry.classIdFor(sharedClass),
      refClass,
      descriptor.isNullable,
    )
  }
}

/**
 * `Promise<T>`, which only appears as what a lambda returns. `FunctionConverter` reads the `T` out
 * of it and never asks for a converter of the promise itself.
 */
object PromiseResultConverterFactory : ParametrizedConverterFactory {
  override fun create(descriptor: TypeDescriptor.Parametrized): TypeConverter<*> =
    throw IllegalArgumentException(
      "A Promise only crosses as what a JavaScript function's lambda returns, got $descriptor",
    )
}

object UnsupportedConverterFactory : SimpleConverterFactory {
  override fun create(descriptor: TypeDescriptor.Simple): TypeConverter<*> =
    throw IllegalArgumentException(
      "${descriptor.javaClass.name} has no bridge type - describe it with a leaf class, a " +
        "List/Map, a registered RecordCodec, or register a TypeConverter for it with " +
        "TypeConverterRegistry.register"
    )
}
