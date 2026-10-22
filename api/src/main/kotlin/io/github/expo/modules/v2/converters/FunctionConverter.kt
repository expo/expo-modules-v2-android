package io.github.expo.modules.v2.converters

import io.github.expo.kolibri.binary.BinaryBuffer
import io.github.expo.modules.v2.jsi.CurrentRuntime
import io.github.expo.modules.v2.jsi.JSFunction
import io.github.expo.modules.v2.jsi.JSFunctionCaller
import io.github.expo.modules.v2.jsi.JSSuspendFunction
import io.github.expo.modules.v2.jsi.JavaScriptFunction
import io.github.expo.modules.v2.types.CppType
import io.github.expo.modules.v2.types.TypeCodes
import io.github.expo.modules.v2.types.TypeDescriptor
import io.github.expo.modules.v2.types.anyConverter

/**
 * A JavaScript function as the Kotlin lambda its parameter declares: `(A, B) -> R`, or
 * `suspend (A, B) -> R`. It only crosses inbound; a lambda is not something JavaScript can receive.
 *
 * Wire format: the head code, then the suspend flag, the parameter count, the codes of each
 * parameter, and the codes of the return type.
 */
internal class FunctionConverter(
  descriptor: TypeDescriptor.Function,
) : TypeConverter<Any>(descriptor.isNullable) {
  private val isSuspend = descriptor.isSuspend

  private val params: Array<TypeConverter<Any>> =
    Array(descriptor.params.size) { descriptor.params[it].anyConverter }

  private val returns: TypeConverter<Any> = descriptor.returns.anyConverter

  private val returnsUnit = returns.codes.head == CppType.UNIT

  override val codes: TypeCodes = run {
    val head = CppType.FUNCTION.nullable(isNullable).code
    val parts = ArrayList<Int>()
    parts.add(head)
    parts.add(if (isSuspend) 1 else 0)
    parts.add(params.size)
    params.forEach { converter -> converter.codes.forEach(parts::add) }
    returns.codes.forEach(parts::add)
    TypeCodes(parts.toIntArray())
  }

  override val isPassthrough: Boolean get() = false

  override fun fromJni(value: Any?): Any? = handleNullable(value) { handle ->
    handle as JavaScriptFunction

    val asyncContext = CurrentRuntime.get()?.asyncContext
      ?: error("A JavaScript function reached Kotlin outside of a call from the JS thread")
    val caller = JSFunctionCaller(handle, params, returns, returnsUnit, asyncContext)

    if (isSuspend) {
      // The continuation takes one more JVM parameter than the lambda declares.
      JSSuspendFunction(caller, params.size + 1)
    } else {
      JSFunction(caller, params.size)
    }
  }

  override fun toJni(value: Any?): Any? =
    error("A Kotlin function cannot be handed to JavaScript")

  override fun writeToBuffer(buf: BinaryBuffer, value: Any?) =
    error("A JavaScript function is a live handle and cannot be written to the buffer")

  override fun readFromBuffer(buf: BinaryBuffer): Any? =
    error("A JavaScript function is a live handle and cannot be read from the buffer")
}
