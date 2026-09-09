#pragma once

#include <jni.h>
#include <jsi/jsi.h>

#include <cstddef>
#include <memory>
#include <string>
#include <string_view>
#include <unordered_map>
#include <utility>
#include <vector>

#include <expo-modules-v2/objects/ObjectId.h>
#include <expo-modules-v2/objects/ObjectState.h>

namespace expo::modules::v2::events {
  /**
   * One runtime's event listeners, keyed by the `ObjectId` of the object they were added on and
   * then by event name. Owned by `RuntimeObjects`, so it lives and dies with its runtime on that
   * runtime's thread - which is what lets it hold `jsi::Value`s at all. Nothing here may ride on
   * a JavaScript object's native state (see `ObjectNativeState`).
   *
   * Semantics follow expo-modules-core's `EventEmitter`: listeners are called in insertion order
   * on a snapshot, so one that removes itself (or another) still runs once, and a listener that
   * throws does not stop the rest.
   */
  class ListenerTable {
  public:
    ListenerTable() = default;

    ListenerTable(const ListenerTable&) = delete;
    ListenerTable& operator=(const ListenerTable&) = delete;

    /** Adds [listener]; true when it is the first one for that event on that object. */
    bool add(
      facebook::jsi::Runtime& rt,
      const std::shared_ptr<objects::ObjectState>& state,
      const std::string& name,
      const facebook::jsi::Function& listener
    );

    /** Removes one occurrence of [listener]; true when it was the last one for that event. */
    bool remove(
      facebook::jsi::Runtime& rt,
      objects::ObjectId::Value objectId,
      const std::string& name,
      const facebook::jsi::Function& listener
    );

    /** Removes every listener of that event; true when there was at least one. */
    bool removeAll(objects::ObjectId::Value objectId, const std::string& name);

    [[nodiscard]] size_t count(objects::ObjectId::Value objectId, std::string_view name) const;

    [[nodiscard]] bool has(objects::ObjectId::Value objectId) const {
      return entries_.contains(objectId);
    }

    void call(
      facebook::jsi::Runtime& rt,
      objects::ObjectId::Value objectId,
      const std::string& name,
      const facebook::jsi::Object& thisObject,
      const facebook::jsi::Value* args,
      size_t count
    );

    /**
     * Drops every listener of [objectId], calling `onStop(instance, name)` for each event that
     * still had one - the object went away, so nothing will ever remove them.
     *
     * Nothing is reported once the state is gone: a shared object's state dies with its last
     * facade, and its release already detached every observer on the Kotlin side.
     */
    template<typename OnStop>
    void drop(const objects::ObjectId::Value objectId, OnStop&& onStop) {
      const auto found = entries_.find(objectId);
      if (found == entries_.end()) {
        return;
      }
      // Detached first: `onStop` reaches Kotlin, and Kotlin may emit straight back into this table.
      Entry entry = std::move(found->second);
      entries_.erase(found);

      const std::shared_ptr<objects::ObjectState> state = entry.state.lock();
      const jobject instance = state == nullptr ? nullptr : state->instance();
      if (instance == nullptr) {
        return;
      }
      for (const auto& [name, listeners]: entry.byName) {
        if (!listeners.empty()) {
          onStop(instance, name);
        }
      }
    }

    /** [drop] for every object: the runtime is going away and takes every listener with it. */
    template<typename OnStop>
    void dropAll(OnStop&& onStop) {
      while (!entries_.empty()) {
        drop(entries_.begin()->first, onStop);
      }
    }

    void clear() { entries_.clear(); }

  private:
    struct Entry {
      /**
       * The instance's state, which owns the Kotlin ref. Weak on purpose: listeners must not keep
       * an object alive that JavaScript has let go of, or its release would wait for a sweep.
       */
      std::weak_ptr<objects::ObjectState> state;
      std::unordered_map<std::string, std::vector<facebook::jsi::Value>> byName;
    };

    std::unordered_map<objects::ObjectId::Value, Entry> entries_;
  };
} // namespace expo::modules::v2::events
