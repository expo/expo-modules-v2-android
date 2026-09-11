#pragma once

#include <jsi/jsi.h>

#include <string_view>

namespace expo::modules::v2::events {
  void installEmitterMethods(facebook::jsi::Runtime& rt, const facebook::jsi::Object& target);

  [[nodiscard]] bool isEmitterMemberName(std::string_view name) noexcept;
} // namespace expo::modules::v2::events
