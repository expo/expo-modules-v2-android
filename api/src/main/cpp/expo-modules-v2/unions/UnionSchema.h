#pragma once

#include <cstdint>
#include <optional>
#include <string>
#include <vector>

#include <expo-modules-v2/descriptor/ExpectedType.h>

namespace expo::modules::v2 {
  /**
   * The kind of JavaScript value a union variant takes - what [selectUnionVariant] picks a variant
   * by. MUST stay in sync with Kotlin's `UnionVariantKind`.
   */
  enum class UnionVariantKind : int {
    BOOLEAN = 0,
    NUMBER = 1,
    STRING = 2,
    ARRAY = 3,
    BINARY = 4,
    // A plain object: a `Map`, or a record.
    OBJECT = 5,
    SHARED_OBJECT = 6,
    // Any object at all: a `JavaScriptObject`.
    ANY_OBJECT = 7,
    // Any value at all: `Any` or `JavaScriptValue`.
    ANY = 8,
    // One string, the variant's tag: an `object` variant, which carries no value.
    LITERAL = 9,
  };

  inline constexpr size_t kUnionVariantKindCount = 10;

  struct UnionVariantSpec {
    UnionVariantKind kind;
    // The string a LITERAL variant crosses as, or the discriminator value of an OBJECT variant.
    std::string tag;
    // What the variant crosses as. A LITERAL variant has no value and carries UNIT.
    ExpectedType type;
  };

  struct UnionSchema {
    int32_t id;
    std::string name;
    // The property that names an object's variant, when the union has one.
    std::optional<std::string> discriminator;
    bool bufferSafe = true;
    std::vector<UnionVariantSpec> variants;
    // The variants of each kind, in the order they are declared: within a kind, the first that
    // takes a value gets it. LITERAL variants are listed under STRING too, since both take strings.
    std::vector<int> byKind[kUnionVariantKindCount];
  };
} // namespace expo::modules::v2
