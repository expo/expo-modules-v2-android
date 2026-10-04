package io.github.expo.modules.v2

/**
 * Marks a `@JS val` of an `@ExpoModule` or an `@ExpoSharedObject` as a constant. JavaScript calls
 * its getter once, on the first read, and from then on reads the value as a plain read-only
 * property, without a call into Kotlin. A shared object keeps the value on each instance, so each
 * instance calls the getter once, and the value stays readable after the instance is released.
 *
 * A `@JS val` that only holds a value, such as `val version = 3`, is a constant without
 * this annotation: a final `val` with the default getter, of a number, `Boolean`, `String`, enum,
 * `Duration`, `File`, `URL`, `URI` or `Path`. A collection or a record is not, because Kotlin can
 * still change what is inside it; annotate it when its contents never change.
 */
@Target(AnnotationTarget.PROPERTY)
@Retention(AnnotationRetention.BINARY)
annotation class Constant
