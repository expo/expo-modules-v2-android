package io.github.expo.modules.v2.records.writers

import io.github.expo.modules.v2.types.TypeDescriptor

interface RecordWriter {
  fun write(value: Boolean)
  fun write(value: Boolean?)

  fun write(value: Int)
  fun write(value: Int?)

  fun write(value: Long)
  fun write(value: Long?)

  fun write(value: Float)
  fun write(value: Float?)

  fun write(value: Double)
  fun write(value: Double?)

  fun write(value: Any?, schema: TypeDescriptor)
}
