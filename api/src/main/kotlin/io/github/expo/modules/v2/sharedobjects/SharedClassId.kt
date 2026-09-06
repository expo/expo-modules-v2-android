package io.github.expo.modules.v2.sharedobjects

import java.util.concurrent.atomic.AtomicInteger

@JvmInline
internal value class SharedClassId(val value: Int) {
  override fun toString(): String = "SharedClassId($value)"

  operator fun inc(): SharedClassId = SharedClassId(value.inc())

  companion object {
    private val nextClassId = AtomicInteger(1)

    fun next(): SharedClassId = SharedClassId(nextClassId.incrementAndGet())
  }
}
