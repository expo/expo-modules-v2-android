#pragma once

#include <jni.h>

#include <atomic>
#include <cstddef>

#include <kolibri/defines.h>

#include <expo-modules-v2/objects/InstanceRef.h>
#include <expo-modules-v2/objects/ObjectState.h>
#include <expo-modules-v2/sharedobjects/SharedObjectClassSpec.h>

namespace expo::modules::v2::sharedobjects {
  /**
   * The runtime-agnostic state of one shared object: its instance, its released flag and the
   * memory it reported holding. The export table belongs to the class spec, one for every instance.
   * Every runtime's facade of the instance shares this state, so release is global.
   */
  class SharedObjectState final : public objects::ObjectState {
  public:
    static constexpr Kind kKind = Kind::SharedObject;

    SharedObjectState(
      objects::InstanceRef instance,
      objects::ObjectId::Value objectId,
      const SharedObjectClassSpec& spec,
      size_t additionalMemoryPressure
    );

    ~SharedObjectState() override;

    [[nodiscard]] const SharedObjectClassSpec& spec() const { return *spec_; }

    /**
     * The native memory, in bytes, the instance said it holds beyond its JavaScript facade. Asked
     * once, when the state is made, and charged to every facade so the collector weighs it.
     */
    [[nodiscard]] size_t additionalMemoryPressure() const { return additionalMemoryPressure_; }

    [[nodiscard]] bool released() const { return released_.load(std::memory_order_acquire); }

    /** Calls the class's [index]th function on this instance. */
    facebook::jsi::Value invokeFunction(
      facebook::jsi::Runtime& rt,
      const uint32_t index,
      const facebook::jsi::Value* args,
      const size_t count
    ) const {
      // Borrowing the global ref takes nothing to put back after the call, so this stays a tail
      // call; only an instance in a slot pays for reading it out, off this path.
      if (const jobject receiver = instance_.global(); receiver != nullptr) [[likely]] {
        return spec_->functions[index].invoke(rt, receiver, args, count);
      }
      return invokeFunctionInSlot(rt, index, args, count);
    }

    [[nodiscard]] facebook::jsi::Value getProperty(facebook::jsi::Runtime& rt, const uint32_t index) const {
      if (const jobject receiver = instance_.global(); receiver != nullptr) [[likely]] {
        return spec_->properties[index].get(rt, receiver);
      }
      return getPropertyInSlot(rt, index);
    }

    void setProperty(facebook::jsi::Runtime& rt, const uint32_t index, const facebook::jsi::Value& value) const {
      if (const jobject receiver = instance_.global(); receiver != nullptr) [[likely]] {
        return spec_->properties[index].set(rt, receiver, value);
      }
      setPropertyInSlot(rt, index, value);
    }

    void release();

  private:
    /**
     * The same three, for an instance in a slot or already released. Each promotes the instance on
     * the way, so the calls after it take the path above.
     */
    NOINLINE facebook::jsi::Value invokeFunctionInSlot(
      facebook::jsi::Runtime& rt,
      uint32_t index,
      const facebook::jsi::Value* args,
      size_t count
    ) const;

    NOINLINE facebook::jsi::Value getPropertyInSlot(facebook::jsi::Runtime& rt, uint32_t index) const;

    NOINLINE void setPropertyInSlot(
      facebook::jsi::Runtime& rt,
      uint32_t index,
      const facebook::jsi::Value& value
    ) const;

    /** Owned by `SharedObjectClassRegistry` for the life of the process. */
    const SharedObjectClassSpec* spec_;

    size_t additionalMemoryPressure_;

    std::atomic<bool> released_{false};
  };
} // namespace expo::modules::v2::sharedobjects
