#pragma once

#include <jsi/jsi.h>

namespace expo::modules::v2::sharedobjects {
  struct SharedObjectClassSpec;

  void installPrototypeMembers(
    facebook::jsi::Runtime& rt,
    const facebook::jsi::Object& prototype,
    const SharedObjectClassSpec& spec
  );
} // namespace expo::modules::v2::sharedobjects
