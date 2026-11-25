package io.github.expo.modules.v2.types

import io.github.expo.modules.v2.SharedObject
import io.github.expo.modules.v2.SharedRef
import io.github.expo.modules.v2.TypedArray
import io.github.expo.modules.v2.cache.classCacheOf
import io.github.expo.modules.v2.converters.ArrayConverter
import io.github.expo.modules.v2.converters.BoxedBooleanConverter
import io.github.expo.modules.v2.converters.BoxedDoubleConverter
import io.github.expo.modules.v2.converters.BoxedFloatConverter
import io.github.expo.modules.v2.converters.BoxedIntConverter
import io.github.expo.modules.v2.converters.BoxedLongConverter
import io.github.expo.modules.v2.converters.DurationConverter
import io.github.expo.modules.v2.converters.DynamicConverter
import io.github.expo.modules.v2.converters.EnumConverter
import io.github.expo.modules.v2.converters.FileConverter
import io.github.expo.modules.v2.converters.JsHandleConverter
import io.github.expo.modules.v2.converters.KotlinUuidConverter
import io.github.expo.modules.v2.converters.ListConverter
import io.github.expo.modules.v2.converters.MapConverter
import io.github.expo.modules.v2.converters.PairConverter
import io.github.expo.modules.v2.converters.PathConverter
import io.github.expo.modules.v2.converters.RecordConverter
import io.github.expo.modules.v2.converters.SetConverter
import io.github.expo.modules.v2.converters.SharedObjectConverter
import io.github.expo.modules.v2.converters.SharedRefConverter
import io.github.expo.modules.v2.converters.StringConverter
import io.github.expo.modules.v2.converters.TripleConverter
import io.github.expo.modules.v2.converters.TypeConverter
import io.github.expo.modules.v2.converters.TypedArrayConverter
import io.github.expo.modules.v2.converters.UnionConverter
import io.github.expo.modules.v2.converters.UnitConverter
import io.github.expo.modules.v2.converters.UriConverter
import io.github.expo.modules.v2.converters.UrlConverter
import io.github.expo.modules.v2.converters.UuidConverter
import io.github.expo.modules.v2.converters.selectJsHandleConverter
import io.github.expo.modules.v2.jsi.JavaScriptObject
import io.github.expo.modules.v2.jsi.JavaScriptValue
import io.github.expo.modules.v2.records.RecordRegistry
import io.github.expo.modules.v2.sharedobjects.SharedObjectRegistry
import io.github.expo.modules.v2.unions.UnionRegistry
import java.io.File
import java.net.URI
import java.net.URL
import java.nio.file.Path
import java.util.UUID
import kotlin.time.Duration
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

object TypeConverterRegistry {
  // `Uuid` is `@ExperimentalUuidApi` in the Kotlin 2.2 and 2.3 standard libraries this project
  // still supports, and stable from 2.4, where this opt-in is harmless. It covers only the table.
  @OptIn(ExperimentalUuidApi::class)
  private val simpleConvertersCache = classCacheOf {
    +entry(
      Boolean::class.javaObjectType,
      ::BoxedBooleanConverter
    )

    +entry(
      Int::class.javaObjectType,
      ::BoxedIntConverter
    )

    +entry(
      Long::class.javaObjectType,
      ::BoxedLongConverter
    )

    +entry(
      Float::class.javaObjectType,
      ::BoxedFloatConverter
    )

    +entry(
      Double::class.javaObjectType,
      ::BoxedDoubleConverter
    )

    +entry(
      String::class.java,
      ::StringConverter
    )

    +entry(
      Unit::class.java,
      ::UnitConverter
    )

    +entry(
      Any::class.java,
      ::DynamicConverter
    )

    +entry(
      JavaScriptValue::class.java,
      selectJsHandleConverter(CppType.JS_VALUE, ::JsHandleConverter)
    )

    +entry(
      JavaScriptObject::class.java,
      selectJsHandleConverter(CppType.JS_OBJECT, ::JsHandleConverter)
    )

    +entry(
      Duration::class.java,
      ::DurationConverter
    )

    +entry(
      File::class.java,
      ::FileConverter
    )

    +entry(
      URL::class.java,
      ::UrlConverter
    )

    +entry(
      URI::class.java,
      ::UriConverter
    )

    +entry(
      Uuid::class.java,
      ::KotlinUuidConverter
    )

    +entry(
      UUID::class.java,
      ::UuidConverter
    )

    // java.nio.file only exists on Android 8+ (API 26). Below that, resolving Path::class throws
    // NoClassDefFoundError inside this initializer
    runCatching {
      +entry(
        Path::class.java,
        ::PathConverter
      )
    }

    // The leaf types of the platform this module is built for, such as `android.net.Uri`.
    platformConverters()
  }

  fun converter(typeDescriptor: TypeDescriptor.Simple): TypeConverter<*> {
    val (type, isNullable) = typeDescriptor

    return simpleConvertersCache.getOrPut(
      type,
      isNullable,
    ) {
      if (SharedObject::class.java.isAssignableFrom(type)) {
        @Suppress("UNCHECKED_CAST")
        val sharedClass = type as Class<out SharedObject>
        return@getOrPut SharedObjectConverter(
          SharedObjectRegistry.classIdFor(sharedClass),
          sharedClass,
          isNullable,
        )
      }

      if (TypedArray::class.java.isAssignableFrom(type)) {
        @Suppress("UNCHECKED_CAST")
        return@getOrPut TypedArrayConverter(type as Class<out TypedArray>, isNullable)
      }

      if (type.isEnum) {
        @Suppress("UNCHECKED_CAST")
        return@getOrPut EnumConverter(type as Class<out Enum<*>>, isNullable)
      }

      val unionType = UnionRegistry.typeFor(type)
      if (unionType != null) {
        return@getOrPut UnionConverter(unionType, isNullable)
      }

      val recordType = RecordRegistry.typeFor(type)
      if (recordType != null) {
        RecordConverter(recordType, isNullable)
      } else {
        throw IllegalArgumentException(
          "${type.name} has no bridge type - describe it with a leaf class, a List/Map, a registered " +
            "RecordCodec, or register a TypeConverter for it with TypeConverterRegistry.register"
        )
      }
    }
  }

  fun converter(typeDescriptor: TypeDescriptor.Parametrized): TypeConverter<*> {
    val (jClass) = typeDescriptor

    if (SharedRef::class.java.isAssignableFrom(jClass)) {
      @Suppress("UNCHECKED_CAST")
      val sharedClass = jClass as Class<out SharedObject>
      val refClass = (typeDescriptor.params.singleOrNull() as? TypeDescriptor.Simple)?.javaClass
        ?: throw IllegalArgumentException(
          "A SharedRef parameter must name the type it carries, as SharedRef<Something>",
        )

      return SharedRefConverter(
        SharedObjectRegistry.classIdFor(sharedClass),
        refClass,
        typeDescriptor.isNullable,
      )
    }

    if (List::class.java.isAssignableFrom(jClass)) {
      return ListConverter(typeDescriptor)
    }

    if (Map::class.java.isAssignableFrom(jClass)) {
      return MapConverter(typeDescriptor)
    }

    if (Set::class.java.isAssignableFrom(jClass)) {
      return SetConverter<Any>(typeDescriptor)
    }

    if (Array::class.java.isAssignableFrom(jClass)) {
      return ArrayConverter<Any>(typeDescriptor)
    }

    if (jClass == Pair::class.java) {
      return PairConverter(typeDescriptor)
    }

    if (jClass == Triple::class.java) {
      return TripleConverter(typeDescriptor)
    }

    throw IllegalArgumentException(
      "${jClass.name} has no bridge type - describe it with a leaf class, a List/Map/Pair/Triple, a registered " +
        "RecordCodec, or register a TypeConverter for it with TypeConverterRegistry.register"
    )
  }
}
