package expo.modules.v2.compiler

import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name

// The multi-dollar string syntax is not available on Kotlin 2.1.20.
@Suppress("CanUnescapeDollarLiteral")
object Identifiers {
  private object Packages {
    val ANNOTATIONS = FqName("expo.modules.v2.annotations")
    val RECORDS = FqName("expo.modules.v2.records")
    val READERS = FqName("expo.modules.v2.records.readers")
    val WRITERS = FqName("expo.modules.v2.records.writers")
    val TYPES = FqName("expo.modules.v2.types")
    val MODULES = FqName("expo.modules.v2.modules")
    val ARGS = FqName("expo.modules.v2.args")
    val JSI = FqName("expo.modules.v2.jsi")
    val ASYNC = FqName("expo.modules.v2.async")
  }

  object Classes {
    val RecordAnnotation = classId(Packages.ANNOTATIONS, "Record")
    val JSAnnotation = classId(Packages.ANNOTATIONS, "JS")

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

    val Module = classId(Packages.MODULES, "Module")
    val ModuleBuilder = classId(Packages.MODULES, "ModuleBuilder")

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
    val FUNCTION = Name.identifier("function")
    val PROPERTY = Name.identifier("property")

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
    val ARG_BUFFER = Name.identifier("buffer")
    val ARG_RETURN_BUFFER = Name.identifier("returnBuffer")
  }

  object Literals {
    const val CODEC_PROPERTY = "recordCodec\$ExpoModulesV2"
    const val DEFINE_FUNCTION = "define\$ExpoModulesV2"
    const val DESCRIPTOR_FIELD_PREFIX = "type\$"
    const val TRAMPOLINE_SUFFIX = "__trampoline\$ExpoModulesV2"
    const val PROMISE_PARAMETER = "promise"

    // Entry names of the `Buffer` enum, as spelled in the annotation.
    const val BUFFER_AUTO = "AUTO"
    const val BUFFER_YES = "YES"
    const val BUFFER_NO = "NO"
  }

  const val MAX_ARGUMENTS = 8
}
