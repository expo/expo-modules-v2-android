package io.github.expo.modules.v2.compiler.ir

import io.github.expo.modules.v2.compiler.BufferChoice
import io.github.expo.modules.v2.compiler.Identifiers
import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.descriptors.ClassKind
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrParameterKind
import org.jetbrains.kotlin.ir.declarations.IrProperty
import org.jetbrains.kotlin.ir.expressions.IrConst
import org.jetbrains.kotlin.ir.expressions.IrConstructorCall
import org.jetbrains.kotlin.ir.expressions.IrGetEnumValue
import org.jetbrains.kotlin.ir.types.IrSimpleType
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.types.IrTypeProjection
import org.jetbrains.kotlin.ir.types.classOrNull
import org.jetbrains.kotlin.ir.types.isInt
import org.jetbrains.kotlin.ir.types.isMarkedNullable
import org.jetbrains.kotlin.ir.types.makeNullable
import org.jetbrains.kotlin.ir.types.typeWith
import org.jetbrains.kotlin.ir.util.defaultType
import org.jetbrains.kotlin.ir.util.getAnnotation
import org.jetbrains.kotlin.ir.util.hasAnnotation
import org.jetbrains.kotlin.ir.util.isSubclassOf
import org.jetbrains.kotlin.ir.util.kotlinFqName
import org.jetbrains.kotlin.ir.util.primaryConstructor
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.utils.addToStdlib.ifTrue

/** What a value is, for the purposes of transport and of reading it back. */
internal enum class ValueKind {
  /** `Int`/`Long`/`Float`/`Double`/`Boolean`: a register-width JNI slot that cannot be buffered. */
  UNBOXED_SCALAR,

  BOXED_SCALAR,
  STRING,

  /**
   * `Any`: a dynamically tagged slot with no fixed binary encoding. Only an explicit `Buffer.YES`
   * rides the buffer, through the tagged dynamic codec. A result that holds a JSI handle or `Unit`
   * falls back to its JNI slot at run time.
   */
  DYNAMIC,
  UNIT,
  PRIMITIVE_ARRAY,

  /** `JavaScriptValue`/`JavaScriptObject`: a live handle, so it can never be serialized. */
  JS_HANDLE,

  /** `TypedArray`, whose bridge is the live `JavaScriptObject` it wraps. */
  TYPED_ARRAY,

  /** `Duration`, whose bridge is a `Double` - unboxed when the declaration is not nullable. */
  CONVERTED_TO_DOUBLE,

  /** `File`/`URL`/`URI`/`Path`, whose bridge is a `String`. */
  CONVERTED_TO_STRING,

  /** An enum class, whose bridge is a `String`: the entry's name, or its one `String` property. */
  ENUM,

  /** An enum class with one `Int` property, whose bridge is that `Int` - unboxed when not nullable. */
  INT_ENUM,
  LIST,
  MAP,
  SET,
  ARRAY,
  RECORD,

  SHARED_OBJECT,
}

internal enum class Crossing {
  /** JavaScript to Kotlin: an argument. */
  INBOUND,

  /** Kotlin to JavaScript: a function's result, or a property read. */
  RESULT,
}

/** One value of an export - an argument, a return value, or a property - and how it crosses. */
internal class ValuePlan(
  val type: IrType,
  val kind: ValueKind,
  val buffered: Boolean,
  /** Whether the Kotlin type is its own bridge type, so no conversion is needed either way. */
  val passthrough: Boolean,
  /** The trampoline's parameter or return type when this value keeps a JNI slot. */
  val jniType: IrType,
  /** The typed `TrampolineArguments` reader for a buffered passthrough leaf, else null. */
  val bufferReader: String?,
)

internal class TransportPolicy(context: IrPluginContext, private val symbols: SymbolFinder) {
  private val irBuiltIns = context.irBuiltIns

  fun supports(type: IrType): Boolean = try {
    plan(type, BufferChoice.AUTO, Crossing.INBOUND)
    true
  } catch (_: IllegalStateException) {
    false
  }

  fun plan(type: IrType, choice: BufferChoice, crossing: Crossing): ValuePlan {
    val kind = kindOf(type)
    val buffered = when (choice) {
      BufferChoice.NO -> false
      BufferChoice.YES -> canBuffer(kind, type, allowDynamic = true)
      BufferChoice.AUTO -> canBuffer(kind, type) && prefersBuffer(kind, crossing)
    }

    return ValuePlan(
      type = type,
      kind = kind,
      buffered = buffered,
      passthrough = isPassthrough(kind, type),
      jniType = jniType(kind, type),
      bufferReader = buffered.ifTrue { bufferReader(kind, type) },
    )
  }

  fun kindOf(type: IrType): ValueKind {
    val fqName = type.classOrNull?.owner?.kotlinFqName?.asString() ?: unsupported(type)
    val isNullable = type.isMarkedNullable()
    return when (fqName) {
      "kotlin.Int", "kotlin.Long", "kotlin.Float", "kotlin.Double", "kotlin.Boolean" ->
        if (isNullable) {
          ValueKind.BOXED_SCALAR
        } else {
          ValueKind.UNBOXED_SCALAR
        }

      "kotlin.String" -> ValueKind.STRING
      "kotlin.Any" -> ValueKind.DYNAMIC
      "kotlin.Unit" -> ValueKind.UNIT
      "kotlin.IntArray", "kotlin.LongArray", "kotlin.FloatArray", "kotlin.DoubleArray",
      "kotlin.BooleanArray", "kotlin.ByteArray",
        -> ValueKind.PRIMITIVE_ARRAY

      "io.github.expo.modules.v2.jsi.JavaScriptValue", "io.github.expo.modules.v2.jsi.JavaScriptObject" ->
        ValueKind.JS_HANDLE

      "io.github.expo.modules.v2.TypedArray" -> ValueKind.TYPED_ARRAY

      "kotlin.time.Duration" -> ValueKind.CONVERTED_TO_DOUBLE
      "java.io.File", "java.net.URL", "java.net.URI", "java.nio.file.Path" ->
        ValueKind.CONVERTED_TO_STRING

      "kotlin.collections.List", "kotlin.collections.MutableList" -> ValueKind.LIST
      "kotlin.collections.Map", "kotlin.collections.MutableMap" -> ValueKind.MAP
      "kotlin.collections.Set", "kotlin.collections.MutableSet" -> ValueKind.SET
      "kotlin.Array" -> ValueKind.ARRAY
      else -> {
        val owner = type.classOrNull?.owner
        when {
          owner?.hasAnnotation(Identifiers.Classes.RecordAnnotation) == true -> ValueKind.RECORD
          owner?.isSubclassOf(symbols.classes.sharedObject.owner) == true -> ValueKind.SHARED_OBJECT
          owner?.kind == ClassKind.ENUM_CLASS ->
            if (owner.isIntBackedEnum()) {
              ValueKind.INT_ENUM
            } else {
              ValueKind.ENUM
            }
          else -> unsupported(type)
        }
      }
    }
  }

  private fun isPassthrough(kind: ValueKind, type: IrType): Boolean =
    when (kind) {
      ValueKind.UNBOXED_SCALAR, ValueKind.BOXED_SCALAR, ValueKind.STRING, ValueKind.DYNAMIC,
      ValueKind.UNIT, ValueKind.PRIMITIVE_ARRAY, ValueKind.JS_HANDLE,
        -> true
      // A shared object is its own bridge value, so nothing has to happen on the way through -
      // except for a `SharedRef<T>` named by its type argument, where only Kotlin can see whether
      // the ref carries a T. The JNI boundary sees every SharedRef as the same erased class.
      ValueKind.SHARED_OBJECT -> !isTypedSharedRef(type)
      // A List or Map hands its elements straight through when they need no conversion themselves.
      ValueKind.LIST -> elementTypes(type).all { isPassthrough(kindOf(it), it) }
      ValueKind.MAP -> elementTypes(type).drop(1).all { isPassthrough(kindOf(it), it) }
      // A Set or an Array always rebuilds its container, and a record always rebuilds itself.
      ValueKind.SET, ValueKind.ARRAY, ValueKind.RECORD,
      ValueKind.CONVERTED_TO_DOUBLE, ValueKind.CONVERTED_TO_STRING, ValueKind.ENUM, ValueKind.INT_ENUM,
      ValueKind.TYPED_ARRAY,
        -> false
    }

  /**
   * Whether the wire format can carry this value on the buffer at all. [allowDynamic] admits `Any`,
   * alone or inside a List/Map/Set/Array, but never as a record field: a record rides its own
   * transport.
   */
  fun canBuffer(kind: ValueKind, type: IrType, allowDynamic: Boolean = false): Boolean =
    when (kind) {
      ValueKind.DYNAMIC -> allowDynamic
      ValueKind.UNBOXED_SCALAR, ValueKind.UNIT, ValueKind.JS_HANDLE,
      ValueKind.TYPED_ARRAY, ValueKind.SHARED_OBJECT,
        -> false
      ValueKind.CONVERTED_TO_DOUBLE, ValueKind.INT_ENUM -> type.isMarkedNullable()
      ValueKind.BOXED_SCALAR, ValueKind.STRING, ValueKind.PRIMITIVE_ARRAY, ValueKind.CONVERTED_TO_STRING,
      ValueKind.ENUM,
        -> true
      ValueKind.LIST, ValueKind.MAP, ValueKind.SET, ValueKind.ARRAY, ValueKind.RECORD ->
        isBufferSafe(
          type,
          mutableSetOf(),
          allowDynamic,
        )
    }

  /**
   * Whether buffering measured faster than a JNI slot.
   */
  private fun prefersBuffer(kind: ValueKind, crossing: Crossing): Boolean =
    when (kind) {
      ValueKind.PRIMITIVE_ARRAY -> false
      ValueKind.STRING -> crossing != Crossing.RESULT
      else -> true
    }

  private fun isBufferSafe(
    type: IrType,
    visited: MutableSet<String>,
    allowDynamic: Boolean = false,
  ): Boolean {
    val kind = kindOf(type)
    if (kind == ValueKind.DYNAMIC) {
      return allowDynamic
    }
    if (kind == ValueKind.JS_HANDLE ||
      kind == ValueKind.TYPED_ARRAY || kind == ValueKind.SHARED_OBJECT
    ) {
      return false
    }
    if (!elementTypes(type).all { isBufferSafe(it, visited, allowDynamic) }) {
      return false
    }
    if (kind != ValueKind.RECORD) {
      return true
    }

    val recordClass = type.classOrNull?.owner ?: return true
    if (!visited.add(recordClass.kotlinFqName.asString())) {
      return true
    }
    if (recordDeclaresUnsafe(recordClass)) {
      return false
    }
    return recordFieldTypes(recordClass).all { isBufferSafe(it, visited) }
  }

  private fun recordDeclaresUnsafe(recordClass: IrClass): Boolean {
    val annotation = recordClass.getAnnotation(Identifiers.FqNames.RECORD_ANNOTATION) ?: return false
    val parameter = annotation.symbol.owner.parameters.firstOrNull {
      it.kind == IrParameterKind.Regular && it.name == Identifiers.Names.ARG_BUFFER_SAFE
    } ?: return false
    val argument = annotation.arguments[parameter.indexInParameters] ?: return false
    return (argument as? IrConst)?.value == false
  }

  private fun recordFieldTypes(recordClass: IrClass): List<IrType> =
    recordClass.primaryConstructor
      ?.parameters
      ?.filter { it.kind == IrParameterKind.Regular }
      ?.map { it.type }
      ?: emptyList()

  /**
   * Whether this enum crosses as an `Int`: its one instance field is a non-null `Int`. The runtime's
   * `EnumConverter` reads the same field, so both sides agree on the bridge type.
   */
  private fun IrClass.isIntBackedEnum(): Boolean {
    val fields = declarations
      .filterIsInstance<IrProperty>()
      .mapNotNull { it.backingField }
      .filter { !it.isStatic }
    return fields.singleOrNull()?.type?.isInt() == true
  }

  fun isTypedSharedRef(type: IrType): Boolean {
    val owner = type.classOrNull?.owner ?: return false
    return owner.isSubclassOf(symbols.classes.sharedRef.owner) && elementTypes(type).size == 1
  }

  private fun elementTypes(type: IrType): List<IrType> =
    (type as? IrSimpleType)
      ?.arguments
      ?.mapNotNull { (it as? IrTypeProjection)?.type }
      ?: emptyList()

  private fun jniType(kind: ValueKind, type: IrType): IrType =
    when (kind) {
      ValueKind.UNBOXED_SCALAR, ValueKind.UNIT -> type
      ValueKind.BOXED_SCALAR, ValueKind.STRING, ValueKind.PRIMITIVE_ARRAY, ValueKind.JS_HANDLE,
      ValueKind.SHARED_OBJECT,
        -> type.makeNullable()

      ValueKind.DYNAMIC -> irBuiltIns.anyType.makeNullable()
      ValueKind.CONVERTED_TO_DOUBLE ->
        if (type.isMarkedNullable()) irBuiltIns.doubleType.makeNullable() else irBuiltIns.doubleType

      ValueKind.CONVERTED_TO_STRING, ValueKind.ENUM -> irBuiltIns.stringType.makeNullable()
      ValueKind.INT_ENUM ->
        if (type.isMarkedNullable()) irBuiltIns.intType.makeNullable() else irBuiltIns.intType
      ValueKind.TYPED_ARRAY -> symbols.classes.javaScriptObject.owner.defaultType.makeNullable()
      ValueKind.LIST, ValueKind.SET, ValueKind.ARRAY ->
        irBuiltIns.listClass.typeWith(irBuiltIns.anyType.makeNullable()).makeNullable()

      ValueKind.MAP, ValueKind.RECORD ->
        irBuiltIns.mapClass
          .typeWith(irBuiltIns.stringType, irBuiltIns.anyType.makeNullable())
          .makeNullable()
    }

  private fun bufferReader(kind: ValueKind, type: IrType): String? {
    val suffix = if (type.isMarkedNullable()) "OrNull" else ""
    return when (kind) {
      ValueKind.STRING -> "nextString$suffix"
      // A boxed scalar is nullable by construction: its non-null form is UNBOXED_SCALAR.
      ValueKind.BOXED_SCALAR -> "next${scalarName(type)}OrNull"
      ValueKind.PRIMITIVE_ARRAY -> "next${simpleName(type)}$suffix"
      else -> null
    }
  }

  private fun scalarName(type: IrType): String = simpleName(type)

  private fun simpleName(type: IrType): String =
    type.classOrNull?.owner?.name?.asString() ?: unsupported(type)

  private fun unsupported(type: IrType): Nothing =
    error("@JS does not support the value type $type - the frontend checker should have caught this")
}

/** The `Buffer` entry an annotation argument names, defaulting to `AUTO`. */
internal fun IrConstructorCall?.bufferChoice(
  argument: Name,
): BufferChoice {
  if (this == null) {
    return BufferChoice.AUTO
  }
  val parameter = symbol.owner.parameters.firstOrNull {
    it.kind == IrParameterKind.Regular && it.name == argument
  } ?: return BufferChoice.AUTO
  val entry = arguments[parameter.indexInParameters] as? IrGetEnumValue ?: return BufferChoice.AUTO
  return when (entry.symbol.owner.name.asString()) {
    Identifiers.Literals.BUFFER_YES -> BufferChoice.YES
    Identifiers.Literals.BUFFER_NO -> BufferChoice.NO
    Identifiers.Literals.BUFFER_AUTO -> BufferChoice.AUTO
    else -> error("Unsupported parameter type ${entry.symbol.owner.name}")
  }
}
