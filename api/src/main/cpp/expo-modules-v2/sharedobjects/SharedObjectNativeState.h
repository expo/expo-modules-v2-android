#pragma once

#include <memory>
#include <utility>

#include <expo-jsi/ChainedNativeState.h>

namespace expo::modules::v2::sharedobjects {
  class SharedObjectState;

  class SharedObjectNativeState final
    : public ::expo::jsi::ChainedNativeStateOf<SharedObjectNativeState> {
  public:
    explicit SharedObjectNativeState(std::shared_ptr<SharedObjectState> state)
      : state_(std::move(state)) {
    }

    [[nodiscard]] const std::shared_ptr<SharedObjectState>& state() const { return state_; }

  private:
    std::shared_ptr<SharedObjectState> state_;
  };
} // namespace expo::modules::v2::sharedobjects
