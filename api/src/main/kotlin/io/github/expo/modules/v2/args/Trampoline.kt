package io.github.expo.modules.v2.args

import io.github.expo.kolibri.CalledFromNative
import io.github.expo.kolibri.binary.BinaryBuffer
import io.github.expo.modules.v2.binary.SlotOnlyValueException
import io.github.expo.modules.v2.binary.newSharedView
import io.github.expo.modules.v2.binary.rewind
import io.github.expo.modules.v2.converters.TypeConverter
import io.github.expo.modules.v2.types.TypeDescriptor
import io.github.expo.modules.v2.types.anyConverter
import java.nio.BufferOverflowException

@CalledFromNative(by = "expo-modules-v2/jni/JTrampoline.h")
object Trampoline {
  const val MAX_ARGUMENTS: Int = 8
  const val OVERFLOW_ARGUMENTS: Int = -1
  const val OVERFLOW_RESULT: Int = -1

  /**
   * What a trampoline call borrows from its thread. Another thread that reads this state through
   * [last] looks at nothing but [thread], which is final, so a racy read never finds it unset.
   */
  private class ThreadState(val thread: Thread) {
    val overflowSlots = arrayOfNulls<Any?>(MAX_ARGUMENTS)
    var overflowResult: Any? = null

    // The views over the shared buffer are made on first use, so a call whose payload only ever
    // overflows never asks native code for the buffer.
    private var _argumentsView: BufferTrampolineArguments? = null
    private var _resultView: BinaryBuffer? = null

    val argumentsView: BufferTrampolineArguments
      get() = _argumentsView
        ?: BufferTrampolineArguments(BinaryBuffer.newSharedView()).also { _argumentsView = it }

    val resultView: BinaryBuffer
      get() = _resultView ?: BinaryBuffer.newSharedView().also { _resultView = it }
  }

  private val states = ThreadLocal.withInitial { ThreadState(Thread.currentThread()) }

  /**
   * The state of the thread that called last. Bridge calls come from the JS thread, so this answers
   * nearly every call without a [ThreadLocal] lookup. Deliberately not volatile: a stale or foreign
   * value only sends the caller on to [states].
   */
  private var last: ThreadState? = null

  private val state: ThreadState
    get() {
      val cached = last
      if (cached != null && cached.thread === Thread.currentThread()) {
        return cached
      }
      return states.get().also { last = it }
    }

  @JvmStatic
  fun arguments(payloadLength: Int): TrampolineArguments {
    val state = state
    if (payloadLength == OVERFLOW_ARGUMENTS) {
      return OverflowTrampolineArguments(state.overflowSlots)
    }

    return state.argumentsView.also { it.rewind(payloadLength) }
  }

  @JvmStatic
  @CalledFromNative(by = "expo-modules-v2/jni/JTrampoline.h")
  fun prepareOverflowArguments(): Array<Any?> = state.overflowSlots.also { it.fill(null) }

  @JvmStatic
  fun writeResult(value: Any?, type: TypeDescriptor): Int =
    writeResultTo(state.resultView.also { it.rewind() }, value, type)

  internal fun writeResultTo(buf: BinaryBuffer, value: Any?, type: TypeDescriptor): Int {
    val converter = type.anyConverter

    return try {
      converter.writeToBuffer(buf, value)
      buf.position
    } catch (_: BufferOverflowException) {
      overflow(converter, value)
    } catch (_: SlotOnlyValueException) {
      overflow(converter, value)
    }
  }

  private fun overflow(converter: TypeConverter<Any>, value: Any?): Int {
    state.overflowResult = if (converter.isPassthrough) {
      value
    } else {
      converter.toJni(value)
    }

    return OVERFLOW_RESULT
  }

  @JvmStatic
  @CalledFromNative(by = "expo-modules-v2/jni/JTrampoline.h")
  fun takeOverflowResult(): Any? {
    return state.overflowResult
  }
}
