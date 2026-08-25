package expo.modules.v2.records.readers

import expo.modules.v2.types.TypeDescriptor
import expo.modules.v2.types.anyConverter
import io.github.expo.kolibri.binary.BinaryBuffer

internal class BufferRecordReader(
  private val buffer: BinaryBuffer,
) : RecordReader {
  override fun readIsPresent(): Boolean = buffer.getBoolean()

  override fun readBoolean(): Boolean = buffer.getBoolean()

  override fun readBooleanOrNull(): Boolean? = readNullable { getBoolean() }

  override fun readInt(): Int = buffer.getInt()

  override fun readIntOrNull(): Int? = readNullable { getInt() }

  override fun readLong(): Long = buffer.getLong()

  override fun readLongOrNull(): Long? = readNullable { getLong() }

  override fun readFloat(): Float = buffer.getFloat()

  override fun readFloatOrNull(): Float? = readNullable { getFloat() }

  override fun readDouble(): Double = buffer.getDouble()

  override fun readDoubleOrNull(): Double? = readNullable { getDouble() }

  @Suppress("UNCHECKED_CAST")
  override fun <T> read(schema: TypeDescriptor): T =
    schema.anyConverter.readFromBuffer(buffer) as T

  private inline fun <T : Any> readNullable(get: BinaryBuffer.() -> T): T? {
    if (!buffer.getBoolean()) {
      return null
    }
    return buffer.get()
  }
}
