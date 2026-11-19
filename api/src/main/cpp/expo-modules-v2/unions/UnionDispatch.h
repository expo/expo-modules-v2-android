#pragma once

#include <cstddef>
#include <optional>
#include <string>

#include <jsi/jsi.h>

#include <expo-modules-v2/unions/UnionSchema.h>
#include <expo-modules-v2/utils/Throw.h>

namespace expo::modules::v2 {
  /**
   * Whether a variant of [kind] can take [value] at all, by the kind of JavaScript value it is: a
   * cheap check that spares a conversion attempt bound to fail. A plain object is neither an array
   * nor a function.
   */
  [[nodiscard]] bool unionVariantMightTake(
    facebook::jsi::Runtime& rt,
    UnionVariantKind kind,
    const facebook::jsi::Value& value
  );

  /**
   * Whether a number variant of [type] holds [number] exactly: an Int or a Long only a whole number
   * in its range. A union offers a number to the narrowest variant that holds it, so `Int | Double`
   * gives 1.5 to the Double.
   */
  [[nodiscard]] bool unionNumberFits(const ExpectedType& type, double number) noexcept;

  /**
   * Whether [value] looks like what [variant] converts, without converting it. A record needs an
   * object with every field it needs, each of the right kind of JavaScript value; a list or map
   * needs its first element or value of the right kind; a ByteArray an ArrayBuffer or a view of
   * one; a shared object one of its class. What is nested deeper is not looked into: the
   * conversion still checks that.
   */
  [[nodiscard]] bool unionVariantMightConvert(
    facebook::jsi::Runtime& rt,
    const UnionVariantSpec& variant,
    const facebook::jsi::Value& value
  );

  /** Throws a `ConversionError` listing what [schema] takes. */
  [[noreturn]] void throwNoUnionVariant(
    const UnionSchema& schema,
    const facebook::jsi::Value& value
  );

  /** [variant] at [index] of [schema], or a `ConversionError` when the index came back out of range. */
  [[nodiscard]] const UnionVariantSpec& unionVariantAt(
    const UnionSchema& schema,
    int index
  );

  /**
   * Converts [value] with the first variant of [schema] whose conversion takes it, in the order
   * they are declared: `attempt(index, variant)` converts with that variant and returns the result,
   * or throws a `ConversionError` to pass the value on to the next one. An `object` variant is
   * offered only its tag. [value] is neither null nor undefined: a nullable union handles those
   * before it gets here.
   *
   * When every variant refuses it, the last candidate has the final word: one that was tried
   * rethrows its own error, such as the record field that is missing; one a cheap check ruled out
   * converts it after all, as a plain value of its type would - a lone Int truncates 1.5.
   */
  template<typename Attempt>
  auto convertUnion(
    facebook::jsi::Runtime& rt,
    const UnionSchema& schema,
    const facebook::jsi::Value& value,
    Attempt&& attempt
  ) -> decltype(attempt(size_t{}, schema.variants.front())) {
    std::optional<size_t> last;
    std::optional<ConversionError> lastError;
    std::optional<std::string> string;
    for (size_t index = 0; index < schema.variants.size(); index++) {
      const UnionVariantSpec& variant = schema.variants[index];
      if (!unionVariantMightTake(rt, variant.kind, value)) {
        continue;
      }
      const bool ruledOut =
        (variant.kind == UnionVariantKind::NUMBER && !unionNumberFits(variant.type, value.getNumber())) ||
        (variant.checkBeforeTrying && !unionVariantMightConvert(rt, variant, value));
      if (ruledOut) {
        last = index;
        lastError.reset();
        continue;
      }
      if (variant.kind == UnionVariantKind::LITERAL) {
        if (!string) {
          string = value.getString(rt).utf8(rt);
        }
        if (*string != variant.tag) {
          continue;
        }
      }

      try {
        return attempt(index, variant);
      } catch (const ConversionError& error) {
        last = index;
        lastError = error;
      }
    }

    if (lastError) {
      throw *lastError;
    }
    if (last) {
      return attempt(*last, schema.variants[*last]);
    }
    throwNoUnionVariant(schema, value);
  }
} // namespace expo::modules::v2
