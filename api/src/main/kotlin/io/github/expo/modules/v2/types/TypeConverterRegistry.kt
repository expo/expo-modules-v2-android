package io.github.expo.modules.v2.types

import io.github.expo.modules.v2.cache.classCacheOf
import io.github.expo.modules.v2.converters.ArrayConverter
import io.github.expo.modules.v2.converters.BoxedBooleanConverter
import io.github.expo.modules.v2.converters.BoxedDoubleConverter
import io.github.expo.modules.v2.converters.BoxedFloatConverter
import io.github.expo.modules.v2.converters.BoxedIntConverter
import io.github.expo.modules.v2.converters.BoxedLongConverter
import io.github.expo.modules.v2.converters.DurationConverter
import io.github.expo.modules.v2.converters.DynamicConverter
import io.github.expo.modules.v2.converters.FileConverter
import io.github.expo.modules.v2.converters.JsHandleConverter
import io.github.expo.modules.v2.converters.ListConverter
import io.github.expo.modules.v2.converters.MapConverter
import io.github.expo.modules.v2.converters.PathConverter
import io.github.expo.modules.v2.converters.RecordConverter
import io.github.expo.modules.v2.converters.SetConverter
import io.github.expo.modules.v2.converters.SharedObjectConverter
import io.github.expo.modules.v2.converters.SharedRefConverter
import io.github.expo.modules.v2.converters.StringConverter
import io.github.expo.modules.v2.converters.TypeConverter
import io.github.expo.modules.v2.converters.UnitConverter
import io.github.expo.modules.v2.converters.UriConverter
import io.github.expo.modules.v2.converters.UrlConverter
import io.github.expo.modules.v2.converters.selectJsHandleConverter
import io.github.expo.modules.v2.jsi.JavaScriptObject
import io.github.expo.modules.v2.jsi.JavaScriptValue
import io.github.expo.modules.v2.records.RecordRegistry
import io.github.expo.modules.v2.SharedObject
import io.github.expo.modules.v2.sharedobjects.SharedObjectRegistry
import io.github.expo.modules.v2.SharedRef
import java.io.File
import java.net.URI
import java.net.URL
import java.nio.file.Path
import kotlin.time.Duration

object TypeConverterRegistry {
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

    // TODO(@lukmccall): Fix path for Android < 26
    +entry(
      Path::class.java,
      ::PathConverter
    )
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

    throw IllegalArgumentException(
      "${jClass.name} has no bridge type — describe it with a leaf class, a List/Map, a registered " +
        "RecordCodec, or register a TypeConverter for it with TypeConverterRegistry.register"
    )
  }
}
