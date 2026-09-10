#pragma once

#include <jsi/jsi.h>

#include <expo-modules-v2/descriptor/SharedClassSpec.h>

namespace expo::modules::v2::sharedobjects {
  facebook::jsi::Function createClassConstructor(
    facebook::jsi::Runtime& rt,
    const descriptor::SharedClassSpec& spec
  );
} // namespace expo::modules::v2::sharedobjects
