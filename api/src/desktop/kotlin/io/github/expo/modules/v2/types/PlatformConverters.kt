package io.github.expo.modules.v2.types

import io.github.expo.modules.v2.cache.ClassCache

/**
 * The converters for the leaf types only this platform has, added to [TypeConverterRegistry]'s
 * built-in table. The desktop JVM has none beyond the ones every platform shares.
 */
internal fun ClassCache.ClassCacheBuilder.platformConverters() = Unit
