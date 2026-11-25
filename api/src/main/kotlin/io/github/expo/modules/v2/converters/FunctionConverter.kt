package io.github.expo.modules.v2.converters

import io.github.expo.kolibri.binary.BinaryBuffer
import io.github.expo.modules.v2.async.Promise
import io.github.expo.modules.v2.binary.toBinaryFlag
import io.github.expo.modules.v2.jsi.JSFunctionCaller
import io.github.expo.modules.v2.jsi.JavaScriptFunction
import io.github.expo.modules.v2.jsi.jsFunction
import io.github.expo.modules.v2.jsi.jsSuspendFunction
import io.github.expo.modules.v2.types.CppType
import io.github.expo.modules.v2.types.TypeCodesWriter
import io.github.expo.modules.v2.types.TypeDescriptor
import io.github.expo.modules.v2.types.anyConverter

/**
 * A JavaScript function as the Kotlin lambda its parameter declares: `(A, B) -> R`, or
 * `suspend (A, B) -> R`.
 */
internal class FunctionConverter(
  descriptor: TypeDescriptor.Function,
) : TypeConverter<Any>(descriptor.isNullable) {
  private val isSuspend = descriptor.isSuspend

  private val params: Array<TypeConverter<Any>> =
    Array(descriptor.params.size) { descriptor.params[it].anyConverter }

  /** A `Promise<T>` result is not awaited: the lambda hands back a promise that settles later. */
  private val returnsPromise = descriptor.returns.let {
    it is TypeDescriptor.Parametrized && it.javaClass == Promise::class.java
  }

  /** What the function's result converts as: `T` for a `Promise<T>`, else the declared type. */
  private val returns: TypeConverter<Any> =
    if (returnsPromise) {
      (descriptor.returns as TypeDescriptor.Parametrized).params.single().anyConverter
    } else {
      descriptor.returns.anyConverter
    }

  private val returnsUnit = returns.kind == CppType.UNIT

  override val kind: CppType
    get() = CppType.FUNCTION

  override fun writeTypeParameters(out: TypeCodesWriter) {
    out.add(isSuspend.toBinaryFlag())
    out.add(params.size)
    params.forEach { it.writeTypeCodes(out) }
    returns.writeTypeCodes(out)
  }

  override val isPassthrough: Boolean
    get() = false

  override fun fromJni(value: Any?): Any? = handleNullable(value) { handle ->
    handle as JavaScriptFunction

    val asyncContext = handle.asyncContext
      ?: error("A JavaScript function reached Kotlin after its runtime was closed")
    val caller = JSFunctionCaller(
      handle,
      params,
      returns,
      returnsUnit,
      returnsPromise,
      asyncContext
    )

    if (isSuspend) {
      jsSuspendFunction(caller, params.size)
    } else {
      jsFunction(caller, params.size)
    }
  }

  // TODO(@lukmccall): We should be able to return lambda to JS
  override fun toJni(value: Any?): Any? =
    error("A Kotlin function cannot be handed to JavaScript")

  override fun writeToBuffer(buf: BinaryBuffer, value: Any?) =
    error("A JavaScript function is a live handle and cannot be written to the buffer")

  override fun readFromBuffer(buf: BinaryBuffer): Any? =
    error("A JavaScript function is a live handle and cannot be read from the buffer")
}
