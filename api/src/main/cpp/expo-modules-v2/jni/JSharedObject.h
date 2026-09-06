#pragma once

#include <string_view>

#include <kolibri/JavaClass.h>

namespace expo::modules::v2 {
  struct JSharedObject : kolibri::JavaClass<JSharedObject> {
    static constexpr std::string_view descriptor =
      "io/github/expo/modules/v2/sharedobjects/SharedObject";
  };
} // namespace expo::modules::v2
