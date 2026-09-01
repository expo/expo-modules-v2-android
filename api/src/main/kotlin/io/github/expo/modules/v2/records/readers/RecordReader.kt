package io.github.expo.modules.v2.records.readers

import io.github.expo.modules.v2.types.TypeDescriptor

interface RecordReader {
  fun readIsPresent(): Boolean

  fun readBoolean(): Boolean
  fun readBooleanOrNull(): Boolean?

  fun readInt(): Int
  fun readIntOrNull(): Int?

  fun readLong(): Long
  fun readLongOrNull(): Long?

  fun readFloat(): Float
  fun readFloatOrNull(): Float?

  fun readDouble(): Double
  fun readDoubleOrNull(): Double?

  fun <T> read(schema: TypeDescriptor): T
}
