package io.github.expo.modules.v2.types

import android.net.Uri
import io.github.expo.modules.v2.converters.androidUriConverter

/**
 * The converter factories for the leaf types only this platform has, added to
 * [TypeConverterRegistry]'s built-in table: the Android framework classes `:api`, a plain JVM
 * module, cannot name.
 */
internal fun MutableMap<Class<*>, SimpleConverterFactory>.platformConverters() {
  put(
    Uri::class.java,
    TypeConverterRegistry.leaf(androidUriConverter(false), androidUriConverter(true)),
  )
}
