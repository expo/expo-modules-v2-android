package io.github.expo.modules.v2.types

/**
 * The converter factories for the leaf types only this platform has, added to
 * [TypeConverterRegistry]'s built-in table. The desktop JVM has none beyond the ones every
 * platform shares.
 */
internal fun MutableMap<Class<*>, SimpleConverterFactory>.platformConverters() = Unit
