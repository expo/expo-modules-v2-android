#pragma once

#include <jni.h>

#include <kolibri/Ref.h>
#include <kolibri/class.h>

#include <expo-modules-v2/objects/ObjectState.h>

namespace expo::modules::v2 {
  /**
   * The one runtime-agnostic state of a module instance: the instance itself and its class. Export
   * tables are not part of it, because the same instance can be registered under several names with
   * different exports; those live in each module object's `ModuleNativeState`.
   */
  class ModuleState final : public objects::ObjectState {
  public:
    static constexpr Kind kKind = Kind::Module;

    ModuleState(JNIEnv* env, objects::ObjectId::Value objectId, kolibri::GlobalRef<> instance);

    ~ModuleState() override;

    /**
     * The instance's class: the one global ref every binder of every export table of this instance
     * borrows to resolve its method id against.
     */
    [[nodiscard]] jclass javaClass() const { return reinterpret_cast<jclass>(javaClass_.get()); }

  private:
    kolibri::GlobalRef<kolibri::JClass> javaClass_;
  };
} // namespace expo::modules::v2
