package io.github.expo.modules.v2

/**
 * Marks an enum that crosses to JavaScript. An entry crosses by its name, or by the value of the
 * enum's only property when that property is a `String` or an `Int`:
 *
 * ```kotlin
 * enum class Style(val value: String) : Enumerable {
 *   LIGHT("light"),
 *   HEAVY("heavy"),
 * }
 * ```
 */
interface Enumerable
