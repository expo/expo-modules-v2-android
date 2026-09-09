#pragma once

#include <jni.h>

#include <cstdint>
#include <memory>

#include <kolibri/Ref.h>

#include <expo-modules-v2/objects/ObjectId.h>

namespace expo::modules::v2::objects {
  /**
   * The runtime-agnostic state of one Kotlin instance JavaScript can hold: a module or a shared
   * object. There is at most one per instance, shared by every JavaScript object standing for it
   * in every runtime, and `ObjectRegistry` finds it by `ObjectId`.
   *
   * It holds nothing that belongs to a runtime. Per-runtime data - the JavaScript object itself -
   * lives in `RuntimeObjects`; per-JavaScript-object data - the binders built for one export
   * table - lives in the `ObjectNativeState` chain node that points here.
   */
  class ObjectState {
  public:
    enum class Kind : uint8_t { Module, SharedObject };

    virtual ~ObjectState();

    ObjectState(const ObjectState&) = delete;
    ObjectState& operator=(const ObjectState&) = delete;

    [[nodiscard]] Kind kind() const { return kind_; }

    [[nodiscard]] ObjectId::Value objectId() const { return objectId_; }

    /** The Kotlin instance, or null once it was released. */
    [[nodiscard]] jobject instance() const { return instance_.get(); }

    /**
     * The ref the binders point at. They are members of a state that owns this one, or of a node
     * that owns such a state, so they never outlive it.
     */
    [[nodiscard]] const kolibri::GlobalRef<>& instanceRef() const { return instance_; }

    /** [state] as the concrete `T`, or null if it is not of `T::kKind`. */
    template<typename T>
    static std::shared_ptr<T> as(const std::shared_ptr<ObjectState>& state) {
      return state != nullptr && state->kind() == T::kKind
               ? std::static_pointer_cast<T>(state)
               : nullptr;
    }

  protected:
    ObjectState(Kind kind, ObjectId::Value objectId, kolibri::GlobalRef<> instance);

    kolibri::GlobalRef<> instance_;

  private:
    Kind kind_;
    ObjectId::Value objectId_;
  };
} // namespace expo::modules::v2::objects
