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

  ChainedNativeState* ChainedNativeState::findBorrowed(
    facebook::jsi::Runtime& rt,
    const facebook::jsi::Object& object,
    StateTag tag
  ) {
    if (!object.hasNativeState(rt)) {
      return nullptr;
    }
    // The object keeps the chain alive for as long as the caller holds the object, so the walk can
    // borrow: one copy out of JSI, then plain pointers.
    const std::shared_ptr<facebook::jsi::NativeState> head = object.getNativeState(rt);
    auto* node = static_cast<ChainedNativeState*>(head.get());
    while (node != nullptr) {
      if (node->tag() == tag) {
        return node;
      }
      node = node->next_.get();
    }
    return nullptr;
  }
} // namespace expo::jsi
