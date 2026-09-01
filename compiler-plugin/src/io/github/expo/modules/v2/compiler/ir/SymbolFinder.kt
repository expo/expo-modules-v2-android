package io.github.expo.modules.v2.compiler.ir

import io.github.expo.modules.v2.compiler.Identifiers
import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.ir.declarations.IrParameterKind
import org.jetbrains.kotlin.ir.symbols.IrClassSymbol
import org.jetbrains.kotlin.ir.symbols.IrConstructorSymbol
import org.jetbrains.kotlin.ir.symbols.IrSimpleFunctionSymbol
import org.jetbrains.kotlin.ir.types.classOrNull
import org.jetbrains.kotlin.ir.types.isMarkedNullable
import org.jetbrains.kotlin.ir.util.constructors
import org.jetbrains.kotlin.ir.util.functions
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.FqName

class SymbolFinder(private val context: IrPluginContext) {
  val classes = Classes()
  val constructors = Constructors()
  val functions = Functions()

  private fun clazz(classId: ClassId): IrClassSymbol =
    context.referenceClass(classId)
      ?: error("$classId not found — is io.github.expo.modules.v2:api on the compile classpath?")

  inner class Classes internal constructor() {
    /** `interface RecordCodec<T : Record>` */
    val recordCodec: IrClassSymbol by lazy { clazz(Identifiers.Classes.RecordCodec) }

    /** `class RecordSchema(name, bufferSafe, vararg fields)` */
    val recordSchema: IrClassSymbol by lazy { clazz(Identifiers.Classes.RecordSchema) }

    /** `data class RecordField(name, type, isOptional)` */
    val recordField: IrClassSymbol by lazy { clazz(Identifiers.Classes.RecordField) }

    /** `object RecordRegistry` */
    val recordRegistry: IrClassSymbol by lazy { clazz(Identifiers.Classes.RecordRegistry) }

    /** `interface RecordReader` */
    val recordReader: IrClassSymbol by lazy { clazz(Identifiers.Classes.RecordReader) }

    /** `interface RecordWriter` */
    val recordWriter: IrClassSymbol by lazy { clazz(Identifiers.Classes.RecordWriter) }

    /** `sealed interface TypeDescriptor` */
    val typeDescriptor: IrClassSymbol by lazy { clazz(Identifiers.Classes.TypeDescriptor) }

    /** `sealed interface TypeDescriptor.ObjectLike` */
    val typeDescriptorObjectLike: IrClassSymbol by lazy {
      clazz(Identifiers.Classes.TypeDescriptorObjectLike)
    }

    /** `class TypeDescriptor.Simple(javaClass, isNullable)` */
    val typeDescriptorSimple: IrClassSymbol by lazy {
      clazz(Identifiers.Classes.TypeDescriptorSimple)
    }

    /** `object CommonDescriptors` */
    val commonDescriptors: IrClassSymbol by lazy { clazz(Identifiers.Classes.CommonDescriptors) }

    /** `abstract class Module` */
    val module: IrClassSymbol by lazy { clazz(Identifiers.Classes.Module) }

    /** `class ModuleBuilder` */
    val moduleBuilder: IrClassSymbol by lazy { clazz(Identifiers.Classes.ModuleBuilder) }

    /** `class AnyType(descriptor, useBuffer)` */
    val anyType: IrClassSymbol by lazy { clazz(Identifiers.Classes.AnyType) }

    /** `object Trampoline` */
    val trampoline: IrClassSymbol by lazy { clazz(Identifiers.Classes.Trampoline) }

    /** `sealed interface TrampolineArguments` */
    val trampolineArguments: IrClassSymbol by lazy { clazz(Identifiers.Classes.TrampolineArguments) }

    /** `object Bridge` */
    val bridge: IrClassSymbol by lazy { clazz(Identifiers.Classes.Bridge) }

    /** `class Promise` */
    val promise: IrClassSymbol by lazy { clazz(Identifiers.Classes.Promise) }

    /** `java.lang.Class` */
    val javaLangClass: IrClassSymbol by lazy { clazz(ClassId.topLevel(FqName("java.lang.Class"))) }

    /** `object TypeDescriptor.Int`, `class TypeDescriptor.IntArray`, … */
    fun descriptor(classId: ClassId): IrClassSymbol = clazz(classId)
  }

  inner class Constructors internal constructor() {
    /** `RecordSchema(name, bufferSafe, vararg fields)` */
    val recordSchema: IrConstructorSymbol by lazy { classes.recordSchema.constructors.single() }

    /** `RecordField(name, type, isOptional)` */
    val recordField: IrConstructorSymbol by lazy { classes.recordField.constructors.single() }

    /** `TypeDescriptor.Simple(javaClass, isNullable)` */
    val simpleDescriptor: IrConstructorSymbol by lazy {
      classes.typeDescriptorSimple.constructors.single()
    }

    /** `TypeDescriptor.Parametrized(javaClass, isNullable, params)` */
    val parametrizedDescriptor: IrConstructorSymbol by lazy {
      descriptor(Identifiers.Classes.TypeDescriptorParametrized)
    }

    /** `AnyType(descriptor, useBuffer)` */
    val anyType: IrConstructorSymbol by lazy { classes.anyType.constructors.single() }

    /** `TypeDescriptor.IntArray(isNullable)` and its siblings */
    fun descriptor(classId: ClassId): IrConstructorSymbol =
      classes.descriptor(classId).constructors.single()
  }

  inner class Functions internal constructor() {
    /** `RecordRegistry.register(codec)` */
    val register: IrSimpleFunctionSymbol by lazy {
      classes.recordRegistry.functions.single { it.owner.name == Identifiers.Names.REGISTER }
    }

    /** `RecordReader.readIsPresent(): Boolean` */
    val readIsPresent: IrSimpleFunctionSymbol by lazy {
      reader(Identifiers.Names.READ_IS_PRESENT.identifier)
    }

    /** `RecordReader.read<T>(type): T` */
    val read: IrSimpleFunctionSymbol by lazy { reader("read") }

    /** One of `RecordReader`'s typed readers: `readInt()`, `readDoubleOrNull()`, … */
    fun reader(name: String): IrSimpleFunctionSymbol =
      classes.recordReader.functions.single { it.owner.name.asString() == name }

    /**
     * `RecordWriter.write(value: Int)` / `write(value: Int?)` - the overload for [primitiveClass]
     * in the [isNullable] flavour.
     */
    fun writePrimitive(primitiveClass: IrClassSymbol, isNullable: Boolean): IrSimpleFunctionSymbol =
      classes.recordWriter.functions.single { function ->
        val parameters = function.owner.parameters.filter { it.kind == IrParameterKind.Regular }
        function.owner.name == Identifiers.Names.WRITE &&
          parameters.size == 1 &&
          parameters[0].type.classOrNull == primitiveClass &&
          parameters[0].type.isMarkedNullable() == isNullable
      }

    /** `RecordWriter.write(value: Any?, schema: TypeDescriptor)` */
    val writeWithDescriptor: IrSimpleFunctionSymbol by lazy {
      classes.recordWriter.functions.single { function ->
        val parameters = function.owner.parameters.filter { it.kind == IrParameterKind.Regular }
        function.owner.name == Identifiers.Names.WRITE && parameters.size == 2
      }
    }

    /**
     * `Module.define$ExpoModulesV2(builder): String?`
     */
    val define: IrSimpleFunctionSymbol by lazy {
      classes.module.functions.single { it.owner.name == Identifiers.Names.DEFINE_FUNCTION }
    }

    /** `ModuleBuilder.function(jsName, vararg argTypes, returns, methodName, isAsync)` */
    val builderFunction: IrSimpleFunctionSymbol by lazy {
      classes.moduleBuilder.functions.single { it.owner.name == Identifiers.Names.FUNCTION }
    }

    /** `ModuleBuilder.property(jsName, type, mutable, propertyName, setterType)` */
    val builderProperty: IrSimpleFunctionSymbol by lazy {
      classes.moduleBuilder.functions.single { it.owner.name == Identifiers.Names.PROPERTY }
    }

    /** `Trampoline.arguments(payloadLength): TrampolineArguments` */
    val arguments: IrSimpleFunctionSymbol by lazy {
      classes.trampoline.functions.single { it.owner.name == Identifiers.Names.ARGUMENTS }
    }

    /** `Trampoline.writeResult(value, type): Int` */
    val writeResult: IrSimpleFunctionSymbol by lazy {
      classes.trampoline.functions.single { it.owner.name == Identifiers.Names.WRITE_RESULT }
    }

    /** `TrampolineArguments.finish()` */
    val finish: IrSimpleFunctionSymbol by lazy {
      classes.trampolineArguments.functions.single { it.owner.name == Identifiers.Names.FINISH }
    }

    /** `TrampolineArguments.next<T>(type): T` */
    val next: IrSimpleFunctionSymbol by lazy {
      classes.trampolineArguments.functions.single { it.owner.name == Identifiers.Names.NEXT }
    }

    /** One of `TrampolineArguments`' typed readers: `nextString()`, `nextIntOrNull()`, … */
    fun argumentsReader(name: String): IrSimpleFunctionSymbol =
      classes.trampolineArguments.functions.single { it.owner.name.asString() == name }

    /** `Bridge.fromJni(value, type): Any?` */
    val fromJni: IrSimpleFunctionSymbol by lazy {
      classes.bridge.functions.single { it.owner.name == Identifiers.Names.FROM_JNI }
    }

    /** `Bridge.toJni(value, type): Any?` */
    val toJni: IrSimpleFunctionSymbol by lazy {
      classes.bridge.functions.single { it.owner.name == Identifiers.Names.TO_JNI }
    }

    /** `Promise.launch(type, buffered, block): Job` */
    val promiseLaunch: IrSimpleFunctionSymbol by lazy {
      classes.promise.functions.single { it.owner.name == Identifiers.Names.LAUNCH }
    }
  }
}
