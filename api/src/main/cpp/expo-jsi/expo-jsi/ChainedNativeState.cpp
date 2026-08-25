#include <expo-jsi/ChainedNativeState.h>

#include <stdexcept>
#include <utility>

namespace expo::jsi {
  void ChainedNativeState::attach(
    facebook::jsi::Runtime& rt,
    const facebook::jsi::Object& object,
    std::shared_ptr<ChainedNativeState> state
  ) {
    if (state == nullptr) {
      throw std::invalid_argument("Cannot attach a null native state");
    }
    if (state->next_ != nullptr) {
      throw std::logic_error("The native state is already part of a chain");
    }
    if (object.hasNativeState(rt)) {
      auto existing = std::static_pointer_cast<ChainedNativeState>(object.getNativeState(rt));
      if (existing == state) {
        return;
      }
      state->next_ = std::move(existing);
    }
    object.setNativeState(rt, std::move(state));
  }

  std::shared_ptr<ChainedNativeState> ChainedNativeState::find(
    facebook::jsi::Runtime& rt,
    const facebook::jsi::Object& object,
    StateTag tag
  ) {
    if (!object.hasNativeState(rt)) {
      return nullptr;
    }
    auto node = std::static_pointer_cast<ChainedNativeState>(object.getNativeState(rt));
    while (node != nullptr) {
      if (node->tag() == tag) {
        return node;
      }
      node = node->next_;
    }
    return nullptr;
  }
} // namespace expo::jsi
