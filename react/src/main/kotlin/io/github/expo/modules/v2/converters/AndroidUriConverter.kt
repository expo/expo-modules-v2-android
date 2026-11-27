package io.github.expo.modules.v2.converters

import android.net.Uri
import io.github.expo.kolibri.binary.BinaryBuffer
import io.github.expo.modules.v2.types.CppType
import androidx.core.net.toUri

/**
 * `android.net.Uri`, which crosses as its string form. [Uri.parse] takes every string - it never
 * validates - so a union variant of it takes every string too, like `String` or `File`.
 */
class AndroidUriConverter(
  isNullable: Boolean
) : TypeConverter<Uri>(isNullable) {
  override val kind: CppType get() = CppType.STRING

  override val isPassthrough: Boolean get() = false

  override fun fromJni(value: Any?): Uri? =
    handleNullable(value) { (it as String).toUri() }

  override fun toJni(value: Uri?): Any? =
    value?.toString()

  override fun writeToBuffer(buf: BinaryBuffer, value: Uri?) =
    buf.writePresent(value) { putString(it.toString()) }

  override fun readFromBuffer(buf: BinaryBuffer): Uri? =
    buf.readPresent { getString().toUri() }
}

/** What the compiler plugin calls for an `android.net.Uri`, as for the types in LeafConverters.kt. */
fun androidUriConverter(isNullable: Boolean): TypeConverter<*> = AndroidUriConverter(isNullable)
