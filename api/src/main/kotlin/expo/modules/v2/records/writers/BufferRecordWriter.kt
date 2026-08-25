package expo.modules.v2.records.writers

import expo.modules.v2.types.TypeDescriptor
import expo.modules.v2.types.anyConverter
import io.github.expo.kolibri.binary.BinaryBuffer

internal class BufferRecordWriter(
  private val buffer: BinaryBuffer,
) : RecordWriter {
  override fun write(value: Boolean) {
    buffer.putBoolean(value)
  }

  override fun write(value: Boolean?) = writeNullable(value) { putBoolean(it) }

  override fun write(value: Int) {
    buffer.putInt(value)
  }

  override fun write(value: Int?) = writeNullable(value) { putInt(it) }

  override fun write(value: Long) {
    buffer.putLong(value)
  }

  override fun write(value: Long?) = writeNullable(value) { putLong(it) }

  override fun write(value: Float) {
    buffer.putFloat(value)
  }

  override fun write(value: Float?) = writeNullable(value) { putFloat(it) }

  override fun write(value: Double) {
    buffer.putDouble(value)
  }

  override fun write(value: Double?) = writeNullable(value) { putDouble(it) }

  override fun write(value: Any?, schema: TypeDescriptor) {
    schema.anyConverter.writeToBuffer(buffer, value)
  }

  private inline fun <T : Any> writeNullable(
    value: T?,
    put: BinaryBuffer.(T) -> Unit,
  ) {
    if (value == null) {
      buffer.putBoolean(false)
    } else {
      buffer.putBoolean(true)
      buffer.put(value)
    }
  }
}
