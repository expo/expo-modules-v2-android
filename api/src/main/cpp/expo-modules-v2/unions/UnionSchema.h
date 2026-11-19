#pragma once

#include <string>
#include <vector>

#include <expo-modules-v2/descriptor/ExpectedType.h>

namespace expo::modules::v2 {
  /**
   * The kind of JavaScript value a union variant takes: what [convertUnion] checks before it offers
   * the variant a value. MUST classify types like `jsKindOf` in the compiler plugin's `UnionCheckers`.
   */
  enum class UnionVariantKind {
    BOOLEAN,
    NUMBER,
    STRING,
    ARRAY,
    BINARY,
    // A plain object: a `Map`, or a record.
    OBJECT,
    SHARED_OBJECT,
    // Any object at all: a `JavaScriptObject`.
    ANY_OBJECT,
    // Any value at all: `Any` or `JavaScriptValue`.
    ANY,
    // One string, the variant's tag: an `object` variant, which carries no value.
    LITERAL,
  };

  struct UnionVariantSpec {
    UnionVariantKind kind;
    // The string a LITERAL variant crosses as.
    std::string tag;
    // What the variant crosses as. A LITERAL variant has no value and carries UNIT.
    ExpectedType type;
    // A list, object, binary or shared-object variant tried before another that can take the same
    // value: [convertUnion] checks the value with [unionVariantMightConvert] before it converts,
    // so a miss throws nothing.
    bool checkBeforeTrying = false;
  };

  /**
   * A union, spelled out in the type codes where it is used. A variant that holds the same union
   * again, such as `List<Tree>` in `Tree`, points back at this schema instead of repeating it.
   */
  struct UnionSchema {
    // TODO(@lukmccall): do we need name?
    std::string name;
    // In the order they are declared: the order a value is offered to them.
    std::vector<UnionVariantSpec> variants;
  };
} // namespace expo::modules::v2
