#pragma once

#include <memory>

#include <expo-modules-v2/objects/ObjectId.h>
#include <expo-modules-v2/objects/ObjectState.h>

namespace expo::modules::v2::objects {
  /**
   * The process-wide, runtime-agnostic registry: `ObjectId` -> the one `ObjectState` of that
   * Kotlin instance. Entries are weak: the JavaScript objects standing for the instance own the
   * state through their chain nodes, and a state removes itself when the last of them goes.
   *
   * Thread-safe; any runtime's thread may look up or adopt.
   */
  class ObjectRegistry {
  public:
    /** The live state for [objectId], or null. */
    [[nodiscard]] static std::shared_ptr<ObjectState> find(ObjectId::Value objectId);

    /** The live state for [objectId] as `T`, or null if there is none or it is another kind. */
    template<typename T>
    [[nodiscard]] static std::shared_ptr<T> find(const ObjectId::Value objectId) {
      return ObjectState::as<T>(find(objectId));
    }

    /**
     * Registers [created] unless a live state already exists for its id, in which case that one
     * wins and [created] is dropped. Returns the state to use.
     */
    [[nodiscard]] static std::shared_ptr<ObjectState> adopt(std::shared_ptr<ObjectState> created);

    template<typename T>
    [[nodiscard]] static std::shared_ptr<T> adopt(std::shared_ptr<T> created) {
      return ObjectState::as<T>(adopt(std::shared_ptr<ObjectState>(std::move(created))));
    }

    /** Drops the entry for [objectId] if its state is gone. Called by `~ObjectState`. */
    static void forget(ObjectId::Value objectId);
  };
} // namespace expo::modules::v2::objects
