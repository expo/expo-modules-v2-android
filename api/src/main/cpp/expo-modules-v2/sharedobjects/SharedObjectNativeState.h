#pragma once

#include <memory>
#include <utility>

#include <expo-modules-v2/objects/ObjectNativeState.h>
#include <expo-modules-v2/sharedobjects/SharedObjectState.h>

namespace expo::modules::v2::sharedobjects {
  /** The chain node on one facade. Everything it needs is in the instance's `SharedObjectState`. */
  class SharedObjectNativeState final : public objects::ObjectNativeState {
  public:
    static constexpr Kind kKind = Kind::SharedObject;

    explicit SharedObjectNativeState(std::shared_ptr<SharedObjectState> state)
      : ObjectNativeState(std::move(state)) {
    }

    [[nodiscard]] std::shared_ptr<SharedObjectState> shared() const {
      return std::static_pointer_cast<SharedObjectState>(state());
    }
  };
} // namespace expo::modules::v2::sharedobjects
