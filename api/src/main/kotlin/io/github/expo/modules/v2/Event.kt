package io.github.expo.modules.v2

/**
 * Exports a property holding an [io.github.expo.modules.v2.events.Event] as an event JavaScript
 * can subscribe to with `addListener`.
 *
 * The property must be a `val` of an `@ExpoModule` or `@ExpoSharedObject` class, initialized with
 * `event<T>(...)`. Its JavaScript name is the property name with a leading `on` removed
 * (`onChanged` -> `changed`), unless [name] says otherwise.
 */
@Target(AnnotationTarget.PROPERTY)
@Retention(AnnotationRetention.BINARY)
annotation class Event(
  /** Event name reported to JavaScript; defaults to the property's name without its `on` prefix. */
  val name: String = "",
)
