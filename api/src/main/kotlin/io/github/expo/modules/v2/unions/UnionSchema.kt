package io.github.expo.modules.v2.unions

import io.github.expo.modules.v2.types.TypeDescriptor

class UnionSchema(
  val name: String,
  /** The property that names an object's variant, or empty when the union has none. */
  discriminator: String,
  /** Whether every variant can ride the binary buffer. */
  val bufferSafe: Boolean,
  vararg variants: UnionVariant,
) {
  val discriminator: String? = discriminator.ifEmpty { null }

  val variants: List<UnionVariant> = variants.toList()
}

class UnionVariant(
  /** The variant's class name, for error messages. */
  val name: String,
  /** The string an `object` variant crosses as, or the discriminator value of a record variant. */
  val tag: String,
  /** What the variant crosses as, or null for an `object`, which crosses as its [tag]. */
  val type: TypeDescriptor?,
)
