#pragma once

#include <jni.h>

#include <atomic>
#include <memory>
#include <vector>

#include <kolibri/Ref.h>

#include <expo-modules-v2/binders/FunctionBinder.h>
#include <expo-modules-v2/binders/PropertyBinder.h>
#include <expo-modules-v2/sharedobjects/SharedObjectClassSpec.h>

namespace expo::modules::v2::sharedobjects {
  class SharedObjectState {
  public:
    SharedObjectState(
      kolibri::GlobalRef<> instance,
      int objectId,
      const SharedObjectClassSpec& spec
    );

    ~SharedObjectState();

    SharedObjectState(const SharedObjectState&) = delete;

    SharedObjectState& operator=(const SharedObjectState&) = delete;

    [[nodiscard]] int objectId() const { return objectId_; }

    [[nodiscard]] const SharedObjectClassSpec& spec() const { return *spec_; }

    [[nodiscard]] bool released() const { return released_.load(std::memory_order_acquire); }

    [[nodiscard]] jobject instance() const { return instance_ ? instance_->get() : nullptr; }

    [[nodiscard]] const FunctionBinder& function(uint32_t index) const {
      return functionBinders_[index];
    }

    [[nodiscard]] const PropertyBinder& property(uint32_t index) const {
      return propertyBinders_[index];
    }

    void release();

  private:
    int objectId_;
    const SharedObjectClassSpec* spec_;

    std::shared_ptr<kolibri::GlobalRef<>> instance_;

    std::vector<FunctionBinder> functionBinders_;
    std::vector<PropertyBinder> propertyBinders_;

    std::atomic<bool> released_{false};
  };
} // namespace expo::modules::v2::sharedobjects
