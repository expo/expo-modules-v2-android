#pragma once

#include <jni.h>

#include <atomic>
#include <memory>
#include <vector>

#include <kolibri/Ref.h>

#include <expo-modules-v2/binders/FunctionBinder.h>
#include <expo-modules-v2/binders/PropertyBinder.h>
#include <expo-modules-v2/objects/ObjectState.h>
#include <expo-modules-v2/sharedobjects/SharedObjectClassSpec.h>

namespace expo::modules::v2::sharedobjects {
  /**
   * The runtime-agnostic state of one shared object: its instance, its class's binders and its
   * released flag. Every runtime's facade of the instance shares it, so release is global.
   */
  class SharedObjectState final : public objects::ObjectState {
  public:
    static constexpr Kind kKind = Kind::SharedObject;

    SharedObjectState(
      kolibri::GlobalRef<> instance,
      objects::ObjectId::Value objectId,
      const SharedObjectClassSpec& spec
    );

    ~SharedObjectState() override;

    [[nodiscard]] const SharedObjectClassSpec& spec() const { return *spec_; }

    [[nodiscard]] bool released() const { return released_.load(std::memory_order_acquire); }

    [[nodiscard]] const FunctionBinder& function(uint32_t index) const {
      return functionBinders_[index];
    }

    [[nodiscard]] const PropertyBinder& property(uint32_t index) const {
      return propertyBinders_[index];
    }

    void release();

  private:
    const SharedObjectClassSpec* spec_;

    std::vector<FunctionBinder> functionBinders_;
    std::vector<PropertyBinder> propertyBinders_;

    std::atomic<bool> released_{false};
  };
} // namespace expo::modules::v2::sharedobjects
