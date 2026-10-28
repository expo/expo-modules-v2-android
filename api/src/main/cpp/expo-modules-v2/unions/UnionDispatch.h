#pragma once

#include <cstddef>

#include <jsi/jsi.h>

#include <expo-modules-v2/unions/UnionSchema.h>

namespace expo::modules::v2 {
  /**
   * The index of the variant [value] is. The kind of JavaScript value it is narrows the variants
   * down, and of those the first declared that takes it wins: an `object` variant takes only its
   * tag, a whole-number variant only whole numbers in its range, a record only an object with every
   * field it needs - or, with a discriminator, the record that object's tag names. The last
   * candidate of a kind takes whatever it is offered, so its own conversion reports a bad value.
   *
   * Throws a `JSError` listing what the union takes when no variant fits. [value] is neither null
   * nor undefined: a nullable union handles those before it gets here.
   */
  [[nodiscard]] size_t selectUnionVariant(
    facebook::jsi::Runtime& rt,
    const UnionSchema& schema,
    const facebook::jsi::Value& value
  );

  /**
   * What JavaScript gets for [variant] around [payload], the value its type decoded to: the tag of an
   * `object` variant, and an object variant's value with the discriminator set.
   */
  [[nodiscard]] facebook::jsi::Value finishUnionValue(
    facebook::jsi::Runtime& rt,
    const UnionSchema& schema,
    const UnionVariantSpec& variant,
    facebook::jsi::Value payload
  );

  /** [variant] at [index] of [schema], or a `JSError` when the index came back out of range. */
  [[nodiscard]] const UnionVariantSpec& unionVariantAt(
    facebook::jsi::Runtime& rt,
    const UnionSchema& schema,
    int index
  );
} // namespace expo::modules::v2
