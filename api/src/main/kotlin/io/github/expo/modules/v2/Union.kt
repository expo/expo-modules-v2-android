package io.github.expo.modules.v2

/**
 * Marks a sealed interface (or sealed class) that crosses to JavaScript as one value of several
 * shapes. Each direct subtype is one variant, and how it is declared decides what JavaScript sees:
 *
 * - a class with a single constructor property (a `value class` works) - that property's value
 * - an `@Record` class - an object
 * - an `object` - a string, its [Tag]
 *
 * ```kotlin
 * @Union
 * sealed interface Dimension {
 *   @JvmInline value class Points(val value: Double) : Dimension   // number
 *   @Tag("auto") data object Auto : Dimension                      // "auto"
 *   @Record data class Percent(val percent: Double) : Dimension    // { percent: number }
 * }
 * ```
 *
 * A value goes to the first variant declared that takes it, so declare the narrower one first:
 *
 * - `Int` before `Double`: a whole number is an `Int`, anything else a `Double`
 * - `URL` before `String`: a string `URL` refuses is a `String`
 * - a record before one that needs fewer of its fields: an object goes to the first record whose
 *   every required field it has
 * - a shared-object subclass before its superclass
 * - `JavaScriptObject` after every other object variant, and `Any` or `JavaScriptValue` last: they
 *   take everything that reaches them
 *
 * A variant that can never be picked - `String` before `URL` - is a compile error.
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.BINARY)
annotation class Union

/**
 * The string an `object` variant of a [Union] crosses to JavaScript as. Without it, the variant is
 * named after its class.
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.BINARY)
annotation class Tag(val value: String)
