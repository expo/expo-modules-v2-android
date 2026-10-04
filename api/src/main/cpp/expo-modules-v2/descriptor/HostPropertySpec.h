#pragma once

#include <jni.h>
#include <jsi/jsi.h>

#include <optional>
#include <string>

#include <expo-modules-v2/descriptor/HostFunctionSpec.h>

namespace expo::modules::v2::descriptor {
  /** A property JavaScript reads once and then keeps as a plain value: Kotlin's `@Constant`. */
  inline constexpr int kPropertyFlagConstant = 1;

  struct HostPropertySpec {
    std::string name;
    HostFunctionSpec getter;
    std::optional<HostFunctionSpec> setter;
    bool isConstant = false;

    [[nodiscard]] bool hasSetter() const { return setter.has_value(); }

    [[nodiscard]] facebook::jsi::Value get(facebook::jsi::Runtime& rt, const jobject receiver) const {
      return getter.invoke(rt, receiver, nullptr, 0);
    }

    void set(facebook::jsi::Runtime& rt, const jobject receiver, const facebook::jsi::Value& value) const {
      setter->invoke(rt, receiver, &value, 1);
    }
  };
} // namespace expo::modules::v2::descriptor
