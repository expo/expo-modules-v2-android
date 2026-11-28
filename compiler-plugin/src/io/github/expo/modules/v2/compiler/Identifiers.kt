package io.github.expo.modules.v2.compiler

import org.jetbrains.kotlin.name.CallableId
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name

// The multi-dollar string syntax is not available on Kotlin 2.1.20.
@Suppress("CanUnescapeDollarLiteral")
object Identifiers {
  private object Packages {
    /** The public surface: the annotations, and the two base classes a user extends. */
    val API = FqName("io.github.expo.modules.v2")
    val RECORDS = FqName("io.github.expo.modules.v2.records")
    val UNIONS = FqName("io.github.expo.modules.v2.unions")
    val READERS = FqName("io.github.expo.modules.v2.records.readers")
    val WRITERS = FqName("io.github.expo.modules.v2.records.writers")
    val TYPES = FqName("io.github.expo.modules.v2.types")
    val MODULES = FqName("io.github.expo.modules.v2.modules")
    val ARGS = FqName("io.github.expo.modules.v2.args")
    val JSI = FqName("io.github.expo.modules.v2.jsi")
    val ASYNC = FqName("io.github.expo.modules.v2.async")
    val SHARED_OBJECTS = FqName("io.github.expo.modules.v2.sharedobjects")
    val EVENTS = FqName("io.github.expo.modules.v2.events")
    val CONVERTERS = FqName("io.github.expo.modules.v2.converters")

    /** Kolibri, the JNI layer under the bridge. */
    val KOLIBRI = FqName("io.github.expo.kolibri")

    /**
     * Where a compiled `@ExpoModule` leaves its hint. Shared by every module on a classpath, so a
     * later compilation can list the package's classes and find them all.
     */
    val HINTS = FqName("io.github.expo.modules.v2.hints")
  }

  /**
   * The cross-compilation hand-off that makes `discoveredExpoModules()` work.
   *
   * Compiling `@ExpoModule object Foo` also emits `interface <hint> { fun module(): Foo }` into
   * [Packages.HINTS]. The hint's name only has to be unique; its `module()` member's return type is
   * what names the module back, so a reader never has to un-mangle anything. A compilation given a
   * package name adds a second member, [packageMemberFor], whose name holds that package.
   */
  object Hints {
    val PACKAGE: FqName = Packages.HINTS

    /** `fun module(): <the module class>` - the hint's abstract member that names the module. */
    val MEMBER: Name = Name.identifier("module")

    private const val PACKAGE_MEMBER_PREFIX = "package_"

    /**
     * `fun package_<encoded>()` - the hint member that records [packageName]. Every UTF-8 byte other
     * than an ASCII letter or digit becomes `_` and two hex digits, so `@expo/ui` gives
     * `package__40expo_2fui`: a JVM method name cannot hold the `.` and `/` of npm package names.
     */
    fun packageMemberFor(packageName: String): Name {
      val encoded = buildString {
        for (byte in packageName.encodeToByteArray()) {
          val char = (byte.toInt() and 0xff).toChar()
          if (char in 'a'..'z' || char in 'A'..'Z' || char in '0'..'9') {
            append(char)
          } else {
            append('_').append("%02x".format(byte.toInt() and 0xff))
          }
        }
      }
      return Name.identifier(PACKAGE_MEMBER_PREFIX + encoded)
    }

    /** The package a [packageMemberFor] member records, or null for any other member name. */
    fun packageNameOf(member: Name): String? {
      val name = member.asString()
      if (member.isSpecial || !name.startsWith(PACKAGE_MEMBER_PREFIX)) {
        return null
      }
      val bytes = mutableListOf<Byte>()
      var index = PACKAGE_MEMBER_PREFIX.length
      while (index < name.length) {
        if (name[index] != '_') {
          bytes += name[index].code.toByte()
          index += 1
          continue
        }
        val high = name.getOrNull(index + 1)?.digitToIntOrNull(16) ?: return null
        val low = name.getOrNull(index + 2)?.digitToIntOrNull(16) ?: return null
        bytes += (high * 16 + low).toByte()
        index += 3
      }
      return bytes.toByteArray().decodeToString()
    }

    /** The hint for [module]: its dotted name with `_` for `.`, plus a hash of that name. */
    fun classIdFor(module: ClassId): ClassId {
      val fqName = module.asSingleFqName().asString()
      val hash = "%08x".format(fqName.hashCode())
      return ClassId(PACKAGE, Name.identifier(fqName.replace('.', '_') + "_" + hash))
    }
  }

  object Callables {
    /** `fun discoveredExpoModules(): List<Class<out Module>>` - the call the plugin expands. */
    val discoveredExpoModules = CallableId(Packages.API, Name.identifier("discoveredExpoModules"))

    /** `fun discoveredExpoPackages(): Map<String, ExpoPackage>` - expanded the same way. */
    val discoveredExpoPackages = CallableId(Packages.API, Name.identifier("discoveredExpoPackages"))

    /** `kotlin.collections.listOf(vararg elements: T)` - what the expansion builds the list with. */
    val listOf = CallableId(FqName("kotlin.collections"), Name.identifier("listOf"))

    /** `kotlin.collections.mapOf(vararg pairs: Pair<K, V>)` - what the package map is built with. */
    val mapOf = CallableId(FqName("kotlin.collections"), Name.identifier("mapOf"))

    /** `fun noSuchVariant(union, index): Nothing` - what a generated `wrap` throws past its variants. */
    val noSuchVariant = CallableId(Packages.UNIONS, Name.identifier("noSuchVariant"))

    /** A leaf type's converter function, such as `urlConverter(isNullable)`. */
    fun leafConverter(name: String): CallableId =
      CallableId(Packages.CONVERTERS, Name.identifier(name))
  }

  object Classes {
    val RecordAnnotation = classId(Packages.API, "Record")
    val JSAnnotation = classId(Packages.API, "JS")
    val ExpoModuleAnnotation = classId(Packages.API, "ExpoModule")
    val BufferModeAnnotation = classId(Packages.API, "BufferMode")
    val ExpoSharedObjectAnnotation = classId(Packages.API, "ExpoSharedObject")
    val EventAnnotation = classId(Packages.API, "Event")
    val ConstantAnnotation = classId(Packages.API, "Constant")
    val UnionAnnotation = classId(Packages.API, "Union")
    val TagAnnotation = classId(Packages.API, "Tag")

    /** Kolibri's marker for what native code looks up by name; the consumer R8 rules keep it. */
    val CalledFromNativeAnnotation = classId(Packages.KOLIBRI, "CalledFromNative")

    val RecordInterface = classId(Packages.RECORDS, "Record")
    val RecordCodec = classId(Packages.RECORDS, "RecordCodec")
    val RecordSchema = classId(Packages.RECORDS, "RecordSchema")
    val RecordField = classId(Packages.RECORDS, "RecordField")
    val RecordRegistry = classId(Packages.RECORDS, "RecordRegistry")
    val RecordReader = classId(Packages.READERS, "RecordReader")
    val RecordWriter = classId(Packages.WRITERS, "RecordWriter")

    val UnionInterface = classId(Packages.UNIONS, "Union")
    val UnionCodec = classId(Packages.UNIONS, "UnionCodec")
    val UnionSchema = classId(Packages.UNIONS, "UnionSchema")
    val UnionVariant = classId(Packages.UNIONS, "UnionVariant")
    val UnionRegistry = classId(Packages.UNIONS, "UnionRegistry")
    val UnionValue = classId(Packages.UNIONS, "UnionValue")

    val TypeDescriptor = classId(Packages.TYPES, "TypeDescriptor")
    val TypeDescriptorObjectLike = typeDescriptorNested("ObjectLike")
    val TypeDescriptorSimple = typeDescriptorNested("Simple")
    val TypeDescriptorParametrized = typeDescriptorNested("Parametrized")
    val TypeDescriptorFunction = typeDescriptorNested("Function")
    val CommonDescriptors = classId(Packages.TYPES, "CommonDescriptors")
    val AnyType = classId(Packages.TYPES, "AnyType")

    val Module = classId(Packages.API, "Module")
    val ModuleBuilder = classId(Packages.MODULES, "ModuleBuilder")

    /** `class ExpoPackage(modules)` - the value of one entry of the package map. */
    val ExpoPackage = classId(Packages.API, "ExpoPackage")

    /** `class Pair<out A, out B>(first, second)` - one entry of the package map. */
    val Pair = classId(FqName("kotlin"), "Pair")

    val SharedObject = classId(Packages.API, "SharedObject")
    val SharedRef = classId(Packages.API, "SharedRef")
    val TypedArray = classId(Packages.API, "TypedArray")
    val SharedObjectRegistry = classId(Packages.SHARED_OBJECTS, "SharedObjectRegistry")

    val ExpoObject = classId(Packages.API, "ExpoObject")

    /** `class Event<T>` - the event object, distinct from the `@Event` annotation. */
    val Event = classId(Packages.EVENTS, "Event")
    val EventSupport = classId(Packages.EVENTS, "EventSupport")

    val Trampoline = classId(Packages.ARGS, "Trampoline")
    val TrampolineArguments = classId(Packages.ARGS, "TrampolineArguments")
    val Bridge = classId(Packages.ARGS, "Bridge")

    /** `class Promise<T>` - what a synchronous export returns to settle later. */
    val Promise = classId(Packages.ASYNC, "Promise")

    /** `class PromiseHandle` - the bridge's side of one async call, handed to its trampoline. */
    val PromiseHandle = classId(Packages.ASYNC, "PromiseHandle")

    val JavaScriptValue = classId(Packages.JSI, "JavaScriptValue")
    val JavaScriptObject = classId(Packages.JSI, "JavaScriptObject")
    val JavaScriptFunction = classId(Packages.JSI, "JavaScriptFunction")

    /** One of the converter factory objects a descriptor names, such as `UrlConverterFactory`. */
    fun converterFactory(simpleName: String): ClassId = classId(Packages.CONVERTERS, simpleName)

    fun typeDescriptorNested(simpleName: String): ClassId =
      TypeDescriptor.createNestedClassId(Name.identifier(simpleName))

    private fun classId(packageName: FqName, simpleName: String): ClassId =
      ClassId(packageName, Name.identifier(simpleName))
  }

  object FqNames {
    val RECORD_ANNOTATION: FqName = Classes.RecordAnnotation.asSingleFqName()
    val JS_ANNOTATION: FqName = Classes.JSAnnotation.asSingleFqName()
    val EXPO_MODULE_ANNOTATION: FqName = Classes.ExpoModuleAnnotation.asSingleFqName()
    val BUFFER_MODE_ANNOTATION: FqName = Classes.BufferModeAnnotation.asSingleFqName()
    val SHARED_OBJECT_ANNOTATION: FqName = Classes.ExpoSharedObjectAnnotation.asSingleFqName()
    val EVENT_ANNOTATION: FqName = Classes.EventAnnotation.asSingleFqName()
    val CONSTANT_ANNOTATION: FqName = Classes.ConstantAnnotation.asSingleFqName()
    val UNION_ANNOTATION: FqName = Classes.UnionAnnotation.asSingleFqName()
    val TAG_ANNOTATION: FqName = Classes.TagAnnotation.asSingleFqName()
    val PROMISE: FqName = Classes.Promise.asSingleFqName()
  }

  object Names {
    // Record codec members.
    val REGISTER = Name.identifier("register")
    val RECORD_CLASS = Name.identifier("recordClass")
    val SCHEMA = Name.identifier("schema")
    val ENCODE = Name.identifier("encode")
    val DECODE = Name.identifier("decode")
    val WRITE = Name.identifier("write")
    val READ_IS_PRESENT = Name.identifier("readIsPresent")
    val CODEC_CLASS = Name.identifier("RecordCodec\$ExpoModulesV2")
    val CODEC_PROPERTY = Name.identifier(Literals.CODEC_PROPERTY)

    // Union codec members.
    val UNION_CODEC_CLASS = Name.identifier("UnionCodec\$ExpoModulesV2")
    val UNION_CODEC_PROPERTY = Name.identifier(Literals.UNION_CODEC_PROPERTY)
    val UNION_CLASS = Name.identifier("unionClass")
    val INDEX_OF = Name.identifier("indexOf")
    val UNWRAP = Name.identifier("unwrap")
    val WRAP = Name.identifier("wrap")

    // Module members.
    val DEFINE_FUNCTION = Name.identifier(Literals.DEFINE_FUNCTION)
    val NAME_FUNCTION = Name.identifier(Literals.NAME_FUNCTION)
    val REGISTER_SHARED_CLASS = Name.identifier("register")
    val RESERVE_SHARED_CLASS = Name.identifier("reserve")
    val GET_ADDITIONAL_MEMORY_PRESSURE = Name.identifier("getAdditionalMemoryPressure")
    val FUNCTION = Name.identifier("function")
    val PROPERTY = Name.identifier("property")
    val SHARED_CLASS = Name.identifier("sharedClass")
    val EVENT = Name.identifier("event")
    val BIND_EVENT = Name.identifier("bind")

    // Trampoline members.
    val ARGUMENTS = Name.identifier("arguments")
    val WRITE_RESULT = Name.identifier("writeResult")
    val FINISH = Name.identifier("finish")
    val NEXT = Name.identifier("next")
    val FROM_JNI = Name.identifier("fromJni")
    val TO_JNI = Name.identifier("toJni")
    val LAUNCH = Name.identifier("launch")
    val SUBSCRIBE_TO = Name.identifier("subscribeTo")

    // Annotation arguments.
    val ARG_NAME = Name.identifier("name")
    val ARG_BUFFER_SAFE = Name.identifier("bufferSafe")
    val ARG_VALUE = Name.identifier("value")
    val ARG_RETURNS = Name.identifier("returns")
    val ARG_CLASSES = Name.identifier("classes")
  }

  object Literals {
    const val CODEC_PROPERTY = "recordCodec\$ExpoModulesV2"
    const val UNION_CODEC_PROPERTY = "unionCodec\$ExpoModulesV2"
    const val DEFINE_FUNCTION = "define\$ExpoModulesV2"
    const val NAME_FUNCTION = "name\$ExpoModulesV2"
    const val DESCRIPTOR_FIELD_PREFIX = "type\$"
    const val TRAMPOLINE_SUFFIX = "__trampoline\$ExpoModulesV2"

    const val CONSTRUCTOR_TRAMPOLINE = "__construct\$ExpoModulesV2"
    const val HANDLE_PARAMETER = "handle"
    const val BUILDER_PARAMETER = "builder"

    const val REGISTRATION_FIELD = "sharedClassId\$ExpoModulesV2"

    const val REGISTER_FUNCTION = "register\$ExpoModulesV2"

    /**
     * The members the event emitter installs on every module object and shared-object prototype.
     * MUST stay in sync with `ModuleBuilder.RESERVED_EXPORT_NAMES` in `:api`.
     */
    val RESERVED_EXPORT_NAMES: Set<String> = setOf(
      "addListener",
      "removeListener",
      "removeAllListeners",
      "listenerCount",
      "emit",
    )

    // Entry names of the `Buffer` enum, as spelled in the annotation.
    const val BUFFER_AUTO = "AUTO"
    const val BUFFER_YES = "YES"
    const val BUFFER_NO = "NO"
  }

  // MUST stay in sync with `Trampoline.MAX_ARGUMENTS` in `:api` and `config::kMaxFunctionArgs` in
  // `expo-modules-v2/Config.h`.
  const val MAX_ARGUMENTS = 8
}
