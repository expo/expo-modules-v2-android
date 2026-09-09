package io.github.expo.modules.v2.compiler

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
    val READERS = FqName("io.github.expo.modules.v2.records.readers")
    val WRITERS = FqName("io.github.expo.modules.v2.records.writers")
    val TYPES = FqName("io.github.expo.modules.v2.types")
    val MODULES = FqName("io.github.expo.modules.v2.modules")
    val ARGS = FqName("io.github.expo.modules.v2.args")
    val JSI = FqName("io.github.expo.modules.v2.jsi")
    val ASYNC = FqName("io.github.expo.modules.v2.async")
    val SHARED_OBJECTS = FqName("io.github.expo.modules.v2.sharedobjects")
    val EVENTS = FqName("io.github.expo.modules.v2.events")
  }

  object Classes {
    val RecordAnnotation = classId(Packages.API, "Record")
    val JSAnnotation = classId(Packages.API, "JS")
    val ExpoModuleAnnotation = classId(Packages.API, "ExpoModule")
    val BufferModeAnnotation = classId(Packages.API, "BufferMode")
    val ExpoSharedObjectAnnotation = classId(Packages.API, "ExpoSharedObject")
    val EventAnnotation = classId(Packages.API, "Event")

    val RecordInterface = classId(Packages.RECORDS, "Record")
    val RecordCodec = classId(Packages.RECORDS, "RecordCodec")
    val RecordSchema = classId(Packages.RECORDS, "RecordSchema")
    val RecordField = classId(Packages.RECORDS, "RecordField")
    val RecordRegistry = classId(Packages.RECORDS, "RecordRegistry")
    val RecordReader = classId(Packages.READERS, "RecordReader")
    val RecordWriter = classId(Packages.WRITERS, "RecordWriter")

    val TypeDescriptor = classId(Packages.TYPES, "TypeDescriptor")
    val TypeDescriptorObjectLike = typeDescriptorNested("ObjectLike")
    val TypeDescriptorSimple = typeDescriptorNested("Simple")
    val TypeDescriptorParametrized = typeDescriptorNested("Parametrized")
    val CommonDescriptors = classId(Packages.TYPES, "CommonDescriptors")
    val AnyType = classId(Packages.TYPES, "AnyType")

    val Module = classId(Packages.API, "Module")
    val ModuleBuilder = classId(Packages.MODULES, "ModuleBuilder")

    val SharedObject = classId(Packages.API, "SharedObject")
    val SharedRef = classId(Packages.API, "SharedRef")
    val SharedObjectRegistry = classId(Packages.SHARED_OBJECTS, "SharedObjectRegistry")

    val ExpoObject = classId(Packages.API, "ExpoObject")

    /** `class Event<T>` - the event object, distinct from the `@Event` annotation. */
    val Event = classId(Packages.EVENTS, "Event")
    val EventSupport = classId(Packages.EVENTS, "EventSupport")

    val Trampoline = classId(Packages.ARGS, "Trampoline")
    val TrampolineArguments = classId(Packages.ARGS, "TrampolineArguments")
    val Bridge = classId(Packages.ARGS, "Bridge")

    val Promise = classId(Packages.ASYNC, "Promise")

    val JavaScriptValue = classId(Packages.JSI, "JavaScriptValue")
    val JavaScriptObject = classId(Packages.JSI, "JavaScriptObject")

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

    // Module members.
    val DEFINE_FUNCTION = Name.identifier(Literals.DEFINE_FUNCTION)
    val REGISTER_SHARED_CLASS = Name.identifier("register")
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

    // Annotation arguments.
    val ARG_NAME = Name.identifier("name")
    val ARG_BUFFER_SAFE = Name.identifier("bufferSafe")
    val ARG_VALUE = Name.identifier("value")
    val ARG_RETURNS = Name.identifier("returns")
    val ARG_CLASSES = Name.identifier("classes")
  }

  object Literals {
    const val CODEC_PROPERTY = "recordCodec\$ExpoModulesV2"
    const val DEFINE_FUNCTION = "define\$ExpoModulesV2"
    const val DESCRIPTOR_FIELD_PREFIX = "type\$"
    const val TRAMPOLINE_SUFFIX = "__trampoline\$ExpoModulesV2"

    const val CONSTRUCTOR_TRAMPOLINE = "__construct\$ExpoModulesV2"
    const val PROMISE_PARAMETER = "promise"
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

  const val MAX_ARGUMENTS = 8
}
