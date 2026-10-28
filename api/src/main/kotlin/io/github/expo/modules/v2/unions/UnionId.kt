package io.github.expo.modules.v2.unions

@JvmInline
internal value class UnionId(val value: Int) {
  override fun toString(): String = "UnionId($value)"

  operator fun inc(): UnionId =
    UnionId(value.inc())
}
