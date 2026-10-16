#pragma once

#include <memory>
#include <utility>

#include <expo-jsi/ChainedNativeState.h>

#include <expo-modules-cpp/Defines.h>
#include <expo-modules-cpp/NativeObject.h>
#include <expo-modules-cpp/internal/Specs.h>

namespace expo::modules::v2::cpp::detail {
  /**
   * The chain node every JavaScript object standing for a native object carries: a module object
   * or the facade of a shared object. It owns the native object, so the object lives as long as
   * JavaScript can reach it.
   *
   * Like every node in a chain, it holds nothing that belongs to one runtime - no `js::Value`, no
   * `Runtime*` - because a worklet runtime that copies the object carries the node along. What does
   * belong to a runtime lives in `RuntimeData`.
   */
  class EXPO_API ObjectNode final : public ::expo::jsi::ChainedNativeStateOf<ObjectNode> {
  public:
    /** [spec] is the class of a shared object, and null for a module. */
    ObjectNode(std::shared_ptr<NativeObject> object, const ClassSpec* spec)
      : object_(std::move(object)), spec_(spec) {
    }

    [[nodiscard]] NativeObject& object() const noexcept { return *object_; }

    [[nodiscard]] const std::shared_ptr<NativeObject>& shared() const noexcept { return object_; }

    /** The class the object's JavaScript object was made for: null for a module. */
    [[nodiscard]] const ClassSpec* spec() const noexcept { return spec_; }

    [[nodiscard]] bool isModule() const noexcept { return spec_ == nullptr; }

    /** The node on [object], or null when it stands for no native object. */
    static std::shared_ptr<ObjectNode> of(js::Runtime& rt, const js::Object& object) {
      return ::expo::jsi::ChainedNativeState::find<ObjectNode>(rt, object);
    }

    /** [of] without a `shared_ptr` copy: valid while [object] is held. */
    static ObjectNode* borrow(js::Runtime& rt, const js::Object& object) {
      return ::expo::jsi::ChainedNativeState::findBorrowed<ObjectNode>(rt, object);
    }

  private:
    std::shared_ptr<NativeObject> object_;
    const ClassSpec* spec_;
  };
} // namespace expo::modules::v2::cpp::detail
