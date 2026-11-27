package io.github.expo.modules.v2.types

import io.github.expo.modules.v2.SharedObject
import io.github.expo.modules.v2.SharedRef
import io.github.expo.modules.v2.TypedArray
import io.github.expo.modules.v2.converters.ArrayConverterFactory
import io.github.expo.modules.v2.converters.CollectionConverterFactory
import io.github.expo.modules.v2.converters.EnumConverterFactory
import io.github.expo.modules.v2.converters.ListConverterFactory
import io.github.expo.modules.v2.converters.MapConverterFactory
import io.github.expo.modules.v2.converters.PairConverterFactory
import io.github.expo.modules.v2.converters.RecordConverterFactory
import io.github.expo.modules.v2.converters.SetConverterFactory
import io.github.expo.modules.v2.converters.SharedObjectConverterFactory
import io.github.expo.modules.v2.converters.SharedRefConverterFactory
import io.github.expo.modules.v2.converters.TripleConverterFactory
import io.github.expo.modules.v2.converters.TypeConverter
import io.github.expo.modules.v2.converters.TypedArrayConverterFactory
import io.github.expo.modules.v2.converters.UnionConverterFactory
import io.github.expo.modules.v2.converters.boxedBooleanConverter
import io.github.expo.modules.v2.converters.boxedDoubleConverter
import io.github.expo.modules.v2.converters.boxedFloatConverter
import io.github.expo.modules.v2.converters.boxedIntConverter
import io.github.expo.modules.v2.converters.boxedLongConverter
import io.github.expo.modules.v2.converters.durationConverter
import io.github.expo.modules.v2.converters.dynamicConverter
import io.github.expo.modules.v2.converters.fileConverter
import io.github.expo.modules.v2.converters.jsObjectConverter
import io.github.expo.modules.v2.converters.jsValueConverter
import io.github.expo.modules.v2.converters.kotlinUuidConverter
import io.github.expo.modules.v2.converters.pathConverter
import io.github.expo.modules.v2.converters.stringConverter
import io.github.expo.modules.v2.converters.unitConverter
import io.github.expo.modules.v2.converters.uriConverter
import io.github.expo.modules.v2.converters.urlConverter
import io.github.expo.modules.v2.converters.uuidConverter
import io.github.expo.modules.v2.jsi.JavaScriptObject
import io.github.expo.modules.v2.jsi.JavaScriptValue
import io.github.expo.modules.v2.records.RecordRegistry
import io.github.expo.modules.v2.unions.UnionRegistry
import java.io.File
import java.net.URI
import java.net.URL
import java.nio.file.Path
import java.util.UUID
import kotlin.time.Duration
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * Picks the converter factory for a descriptor that was built without one: by hand, or by a
 * compiler plugin from before descriptors named their factories. The plugin makes the same choice
 * at compile time - keep the two in step.
 *
 * Anything that reaches this object keeps every built-in converter reachable, which is why the
 * descriptors the plugin emits never do.
 */
object TypeConverterRegistry {
  // `Uuid` is `@ExperimentalUuidApi` in the Kotlin 2.2 and 2.3 standard libraries this project
  // still supports, and stable from 2.4, where this opt-in is harmless. It covers only the table.
  @OptIn(ExperimentalUuidApi::class)
  private val leafFactories: Map<Class<*>, SimpleConverterFactory> =
    HashMap<Class<*>, SimpleConverterFactory>().apply {
      put(
        Boolean::class.javaObjectType,
        leaf(boxedBooleanConverter(false), boxedBooleanConverter(true)),
      )
      put(Int::class.javaObjectType, leaf(boxedIntConverter(false), boxedIntConverter(true)))
      put(Long::class.javaObjectType, leaf(boxedLongConverter(false), boxedLongConverter(true)))
      put(Float::class.javaObjectType, leaf(boxedFloatConverter(false), boxedFloatConverter(true)))
      put(
        Double::class.javaObjectType,
        leaf(boxedDoubleConverter(false), boxedDoubleConverter(true)),
      )
      put(String::class.java, leaf(stringConverter(false), stringConverter(true)))
      put(Unit::class.java, leaf(unitConverter(false), unitConverter(true)))
      put(Any::class.java, leaf(dynamicConverter(false), dynamicConverter(true)))
      put(JavaScriptValue::class.java, leaf(jsValueConverter(false), jsValueConverter(true)))
      put(JavaScriptObject::class.java, leaf(jsObjectConverter(false), jsObjectConverter(true)))
      put(Duration::class.java, leaf(durationConverter(false), durationConverter(true)))
      put(File::class.java, leaf(fileConverter(false), fileConverter(true)))
      put(URL::class.java, leaf(urlConverter(false), urlConverter(true)))
      put(URI::class.java, leaf(uriConverter(false), uriConverter(true)))
      put(Uuid::class.java, leaf(kotlinUuidConverter(false), kotlinUuidConverter(true)))
      put(UUID::class.java, leaf(uuidConverter(false), uuidConverter(true)))

      // java.nio.file only exists on Android 8+ (API 26). Below that, resolving Path::class throws
      // NoClassDefFoundError inside this initializer
      runCatching {
        put(Path::class.java, leaf(pathConverter(false), pathConverter(true)))
      }

      // The leaf types of the platform this module is built for, such as `android.net.Uri`.
      platformConverters()
    }

  /**
   * A leaf type's factory: one converter per nullability, built with the table. The converters, not
   * a function that builds them - a function reference is a class of its own on Android.
   */
  internal fun leaf(nonNull: TypeConverter<*>, nullable: TypeConverter<*>): SimpleConverterFactory =
    object : SimpleConverterFactory {
      override fun create(descriptor: TypeDescriptor.Simple): TypeConverter<*> =
        if (descriptor.isNullable) {
          nullable
        } else {
          nonNull
        }
    }

  /** The factory a hand-built [TypeDescriptor.Simple] gets: the one its class calls for. */
  internal object SimpleLookup : SimpleConverterFactory {
    override fun create(descriptor: TypeDescriptor.Simple): TypeConverter<*> =
      simpleFactoryFor(descriptor.javaClass).create(descriptor)
  }

  /** The factory a hand-built [TypeDescriptor.Parametrized] gets: the one its class calls for. */
  internal object ParametrizedLookup : ParametrizedConverterFactory {
    override fun create(descriptor: TypeDescriptor.Parametrized): TypeConverter<*> =
      parametrizedFactoryFor(descriptor.javaClass).create(descriptor)
  }

  fun converter(typeDescriptor: TypeDescriptor.Simple): TypeConverter<*> =
    SimpleLookup.create(typeDescriptor)

  fun converter(typeDescriptor: TypeDescriptor.Parametrized): TypeConverter<*> =
    ParametrizedLookup.create(typeDescriptor)

  private fun simpleFactoryFor(type: Class<*>): SimpleConverterFactory {
    leafFactories[type]?.let { return it }

    return when {
      SharedObject::class.java.isAssignableFrom(type) -> SharedObjectConverterFactory
      TypedArray::class.java.isAssignableFrom(type) -> TypedArrayConverterFactory
      type.isEnum -> EnumConverterFactory
      UnionRegistry.typeFor(type) != null -> UnionConverterFactory
      RecordRegistry.typeFor(type) != null -> RecordConverterFactory
      else -> throw IllegalArgumentException(
        "${type.name} has no bridge type - describe it with a leaf class, a List/Map, a registered " +
          "RecordCodec, or register a TypeConverter for it with TypeConverterRegistry.register"
      )
    }
  }

  private fun parametrizedFactoryFor(jClass: Class<*>): ParametrizedConverterFactory = when {
    SharedRef::class.java.isAssignableFrom(jClass) -> SharedRefConverterFactory
    List::class.java.isAssignableFrom(jClass) -> ListConverterFactory
    Map::class.java.isAssignableFrom(jClass) -> MapConverterFactory
    Set::class.java.isAssignableFrom(jClass) -> SetConverterFactory
    Array::class.java.isAssignableFrom(jClass) -> ArrayConverterFactory
    // Last of the collections: a declared `Collection` is none of the above, and only the
    // interface itself reaches here - the compiler plugin accepts no other Collection type.
    jClass == Collection::class.java -> CollectionConverterFactory
    jClass == Pair::class.java -> PairConverterFactory
    jClass == Triple::class.java -> TripleConverterFactory
    else -> throw IllegalArgumentException(
      "${jClass.name} has no bridge type - describe it with a leaf class, a List/Set/Collection/Map/Pair/Triple, a registered " +
        "RecordCodec, or register a TypeConverter for it with TypeConverterRegistry.register"
    )
  }
}
