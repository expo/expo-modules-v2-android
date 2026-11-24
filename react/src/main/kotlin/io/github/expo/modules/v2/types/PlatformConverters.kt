package io.github.expo.modules.v2.types

import android.net.Uri
import io.github.expo.modules.v2.cache.ClassCache
import io.github.expo.modules.v2.converters.AndroidUriConverter

/**
 * The converters for the leaf types only this platform has, added to [TypeConverterRegistry]'s
 * built-in table: the Android framework classes `:api`, a plain JVM module, cannot name.
 */
internal fun ClassCache.ClassCacheBuilder.platformConverters() {
  +entry(
    Uri::class.java,
    ::AndroidUriConverter
  )
}
