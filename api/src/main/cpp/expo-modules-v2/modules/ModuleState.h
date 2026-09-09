#pragma once

#include <kolibri/Ref.h>

#include <expo-modules-v2/objects/ObjectState.h>

namespace expo::modules::v2 {
  /**
   * The one runtime-agnostic state of a module instance: the instance itself. Export tables are
   * not part of it, because the same instance can be registered under several names with
   * different exports; those live in each module object's `ModuleNativeState`.
   */
  class ModuleState final : public objects::ObjectState {
  public:
    static constexpr Kind kKind = Kind::Module;

    ModuleState(const objects::ObjectId::Value objectId, kolibri::GlobalRef<> instance)
      : ObjectState(kKind, objectId, std::move(instance)) {
    }
  };
} // namespace expo::modules::v2
