package expo.modules.v2.annotations

/**
 * Marks a class as an Expo Modules record - a plain Kotlin class whose primary-constructor
 * properties cross the JS bridge as the fields of a JavaScript object.
 *
 * The Expo Modules compiler plugin turns an annotated class into a record:
 *
 *  - it adds the `expo.modules.v2.records.Record` supertype, so the class does **not** declare it;
 *  - it generates a private nested `RecordCodec` and hangs it off the companion object, creating
 *    the companion when the class has none;
 *  - the codec registers itself in [expo.modules.v2.records.RecordRegistry] when the class loads.
 *
 * Reach the generated codec with [expo.modules.v2.records.codecFor]:
 *
 * ```kotlin
 * @Record
 * data class Point(val x: Double, val y: Double, val label: String? = null)
 *
 * val map = codecFor<Point>().toMap(Point(1.0, 2.0))
 * ```
 *
 * The fields are the primary-constructor `val`/`var` parameters, in declaration order. Properties
 * declared in the class body are **ignored** - `decode` calls the primary constructor positionally,
 * so a body property has no slot to be read back into. Treat them as derived values.
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.BINARY)
annotation class Record(
  /** Schema name reported to JavaScript; defaults to the class's simple name. */
  val name: String = "",
  /**
   * Whether every field of this record can ride the binary buffer.
   */
  val bufferSafe: Boolean = true,
)
