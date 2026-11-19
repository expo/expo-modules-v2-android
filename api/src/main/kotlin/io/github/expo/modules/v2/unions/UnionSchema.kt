package io.github.expo.modules.v2.unions

import io.github.expo.modules.v2.types.TypeDescriptor

class UnionSchema(
  /** The union's class name, for error messages. */
  val name: String,
  /**
   * Whether every variant crosses as the same bridge value, such as `URL | String`. Native code
   * cannot tell those apart, so the union crosses as that bridge value alone - no variant index -
   * and Kotlin picks the variant: the first whose conversion takes the value.
   */
  val sameBridge: Boolean,
  vararg variants: UnionVariant,
) {
  val variants: List<UnionVariant> = variants.toList()
}

class UnionVariant(
  /** The string an `object` variant crosses as. */
  val tag: String,
  /** What the variant crosses as, or null for an `object`, which crosses as its [tag]. */
  val type: TypeDescriptor?,
)
