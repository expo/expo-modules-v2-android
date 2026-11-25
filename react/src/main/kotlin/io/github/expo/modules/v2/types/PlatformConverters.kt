package io.github.expo.modules.v2.types

import android.graphics.Color
import android.net.Uri
import io.github.expo.modules.v2.cache.ClassCache
import io.github.expo.modules.v2.converters.AndroidUriConverter
import io.github.expo.modules.v2.converters.ColorConverter

/**
 * The converters for the leaf types only this platform has, added to [TypeConverterRegistry]'s
 * built-in table: the Android framework classes `:api`, a plain JVM module, cannot name.
 */
internal fun ClassCache.ClassCacheBuilder.platformConverters() {
  +entry(
    Uri::class.java,
    ::AndroidUriConverter
  )

  // `Color` itself exists on every API level; only its instance methods need API 26, and
  // `ColorConverter` makes its instances through `ColorCompat`.
  +entry(
    Color::class.java,
    ::ColorConverter
  )
}
