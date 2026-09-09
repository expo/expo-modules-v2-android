#pragma once

#include <string>

#include <expo-modules-v2/descriptor/ExpectedType.h>

namespace expo::modules::v2::descriptor {
  /** One event a module or shared object declares: its name, and how an emitted payload crosses. */
  struct EventSpec {
    std::string name;
    ExpectedType payloadType;
  };
} // namespace expo::modules::v2::descriptor
