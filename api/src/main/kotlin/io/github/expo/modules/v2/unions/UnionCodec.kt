package io.github.expo.modules.v2.unions

/**
 * What the compiler plugin generates for an `@io.github.expo.modules.v2.Union` type: its variants,
 * and the three steps between a variant and the value it crosses as.
 */
interface UnionCodec<T : Any> {
  val unionClass: Class<T>
  val schema: UnionSchema

  /** The index in [schema] of the variant [value] is, or -1 when it is none of them. */
  fun indexOf(value: T): Int

  /** The value [value] crosses as: its property, the record itself, or null for an `object`. */
  fun unwrap(value: T): Any?

  /** The variant at [index] around [payload] - the inverse of [unwrap]. */
  fun wrap(index: Int, payload: Any?): T
}

/** What a generated [UnionCodec.wrap] throws for an index it has no variant for. */
fun noSuchVariant(union: String, index: Int): Nothing =
  throw IllegalArgumentException("$union has no variant #$index")
