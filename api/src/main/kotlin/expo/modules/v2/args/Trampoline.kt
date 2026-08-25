package expo.modules.v2.args

import expo.modules.v2.binary.newSharedView
import expo.modules.v2.binary.rewind
import expo.modules.v2.types.TypeDescriptor
import expo.modules.v2.types.anyConverter
import io.github.expo.kolibri.CalledFromNative
import io.github.expo.kolibri.binary.BinaryBuffer
import java.nio.BufferOverflowException

object Trampoline {
  const val MAX_ARGUMENTS: Int = 8
  const val OVERFLOW_ARGUMENTS: Int = -1
  const val OVERFLOW_RESULT: Int = -1

  private val _overflowArguments = ThreadLocal.withInitial {
    object {
      val slots = arrayOfNulls<Any?>(MAX_ARGUMENTS)

      fun prepare(): Array<Any?> {
        slots.fill(null)
        return slots
      }
    }
  }

  private val overflowArguments
    get() = _overflowArguments.get()


  private val _overflowResult = ThreadLocal.withInitial {
    object {
      var value: Any? = null
    }
  }

  private var overflowResult
    get() = _overflowResult.get().value
    set(value) {
      _overflowResult.get().value = value
    }

  private val _argumentsView = ThreadLocal.withInitial {
    BufferTrampolineArguments(BinaryBuffer.newSharedView())
  }

  private val argumentsView get() = _argumentsView.get()

  private val _resultView = ThreadLocal.withInitial {
    BinaryBuffer.newSharedView()
  }

  private val resultView get() = _resultView.get().also { it.rewind() }

  @JvmStatic
  fun arguments(payloadLength: Int): TrampolineArguments {
    if (payloadLength == OVERFLOW_ARGUMENTS) {
      return OverflowTrampolineArguments(overflowArguments.slots)
    }

    return argumentsView.also { it.rewind(payloadLength) }
  }

  @JvmStatic
  @CalledFromNative(by = "expo-modules-v2/jni/JTrampoline.h")
  fun prepareOverflowArguments(): Array<Any?> = overflowArguments.prepare()

  @JvmStatic
  fun writeResult(value: Any?, type: TypeDescriptor): Int =
    writeResultTo(resultView, value, type)

  internal fun writeResultTo(buf: BinaryBuffer, value: Any?, type: TypeDescriptor): Int {
    val converter = type.anyConverter

    return try {
      converter.writeToBuffer(buf, value)
      buf.position
    } catch (_: BufferOverflowException) {
      overflowResult = if (converter.isPassthrough) {
        value
      } else {
        converter.toJni(value)
      }

      OVERFLOW_RESULT
    }
  }

  @JvmStatic
  @CalledFromNative(by = "expo-modules-v2/jni/JTrampoline.h")
  fun takeOverflowResult(): Any? {
    return overflowResult
  }
}
