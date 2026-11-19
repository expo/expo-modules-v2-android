package io.github.expo.modules.v2.types

/** This type's codes on their own, for tests. The module descriptor writes them with [AnyType.writeTypeCodes]. */
internal val AnyType.codes: IntArray
  get() = TypeCodesWriter().also(::writeTypeCodes).toIntArray()
