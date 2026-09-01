package io.github.expo.modules.v2.records

/**
 * The [RecordCodec] the compiler plugin generated for [T].
 *
 * @throws IllegalArgumentException when [T] carries no generated codec — the class is missing
 *   `@io.github.expo.modules.v2.annotations.Record`, or the module that declares it was built without the
 *   Expo Modules Gradle plugin.
 */
inline fun <reified T : Record> codecFor(): RecordCodec<T> = codecFor(T::class.java)

/** The [RecordCodec] registered for [clazz]. See the reified [codecFor]. */
@Suppress("UNCHECKED_CAST")
fun <T : Record> codecFor(clazz: Class<T>): RecordCodec<T> {
  val type = RecordRegistry.typeFor(clazz)
    ?: throw IllegalArgumentException(
      "${clazz.name} has no RecordCodec - annotate it with @io.github.expo.modules.v2.annotations.Record",
    )
  return type.codec as RecordCodec<T>
}
