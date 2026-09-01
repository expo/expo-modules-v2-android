package io.github.expo.modules.v2.binary

import io.github.expo.kolibri.binary.BinaryBuffer

internal fun BinaryBuffer.Companion.newSharedView(limit: Int = -1) =
  BinaryBuffer
    .shared()
    .duplicateView()
    .also {
      if (limit > 0) {
        it.limit = limit
      }
    }

internal fun BinaryBuffer.rewind(limit: Int = -1) = apply {
  position = 0
  if (limit > 0) {
    this.limit = limit
  }
}
