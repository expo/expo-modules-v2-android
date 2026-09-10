#pragma once

#include <jni.h>

#include <atomic>

#include <kolibri/Ref.h>

#include <expo-modules-v2/objects/ObjectState.h>
#include <expo-modules-v2/sharedobjects/SharedObjectClassSpec.h>

namespace expo::modules::v2::sharedobjects {
  /**
   * The runtime-agnostic state of one shared object: its instance and its released flag. The
   * export table belongs to the class spec, one for every instance. Every runtime's facade of the
   * instance shares this state, so release is global.
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

    /** Calls the class's [index]th function on this instance. */
    facebook::jsi::Value invokeFunction(
      facebook::jsi::Runtime& rt,
      const uint32_t index,
      const facebook::jsi::Value* args,
      const size_t count
    ) const {
      return spec_->functions[index].invoke(rt, instance_.get(), args, count);
    }

    [[nodiscard]] facebook::jsi::Value getProperty(facebook::jsi::Runtime& rt, const uint32_t index) const {
      return spec_->properties[index].get(rt, instance_.get());
    }

    void setProperty(facebook::jsi::Runtime& rt, const uint32_t index, const facebook::jsi::Value& value) const {
      spec_->properties[index].set(rt, instance_.get(), value);
    }

    void release();

  private:
    /** Owned by `SharedObjectClassRegistry` for the life of the process. */
    const SharedObjectClassSpec* spec_;

    std::atomic<bool> released_{false};
  };
} // namespace expo::modules::v2::sharedobjects
