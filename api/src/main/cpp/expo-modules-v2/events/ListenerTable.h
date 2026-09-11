#pragma once

#include <jni.h>
#include <jsi/jsi.h>

#include <memory>
#include <unordered_map>
#include <utility>
#include <vector>

#include <expo-modules-v2/objects/ObjectId.h>
#include <expo-modules-v2/objects/ObjectState.h>

namespace expo::modules::v2::events {
  class ListenerTable {
  public:
    ListenerTable() = default;

    ListenerTable(const ListenerTable&) = delete;
    ListenerTable& operator=(const ListenerTable&) = delete;

    /** Adds [listener]; true when it is the first one for that event on that object. */
    bool add(
      facebook::jsi::Runtime& rt,
      const std::shared_ptr<objects::ObjectState>& state,
      int eventIndex,
      const facebook::jsi::Function& listener
    );

    /** Removes one occurrence of [listener]; true when it was the last one for that event. */
    bool remove(
      facebook::jsi::Runtime& rt,
      objects::ObjectId::Value objectId,
      int eventIndex,
      const facebook::jsi::Function& listener
    );

    /** Removes every listener of that event; true when there was at least one. */
    bool removeAll(objects::ObjectId::Value objectId, int eventIndex);

    [[nodiscard]] size_t count(objects::ObjectId::Value objectId, int eventIndex) const;

    /**
     * The listeners of event [eventIndex] on [objectId], or null when there are none. Borrowed; a
     * caller that runs them uses [call] with the vector, which snapshots before calling.
     */
    [[nodiscard]] const std::vector<facebook::jsi::Value>* find(
      objects::ObjectId::Value objectId,
      int eventIndex
    ) const;

    [[nodiscard]] bool has(objects::ObjectId::Value objectId) const {
      return entries_.contains(objectId);
    }

    /** [call] for listeners already found with [find]. */
    static void call(
      facebook::jsi::Runtime& rt,
      const std::vector<facebook::jsi::Value>& listeners,
      const facebook::jsi::Object& thisObject,
      const facebook::jsi::Value* args,
      size_t count
    );

    /**
     * Drops every listener of [objectId], calling `onStop(instance, eventIndex)` for each event
     * that still had one - the object went away, so nothing will ever remove them.
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
      for (const EventListeners& named: entry.byEvent) {
        if (!named.listeners.empty()) {
          onStop(instance, named.eventIndex);
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
    /** The listeners of one event on one object. */
    struct EventListeners {
      int eventIndex;
      std::vector<facebook::jsi::Value> listeners;
    };

    struct Entry {
      /**
       * The instance's state, which owns the Kotlin ref. Weak on purpose: listeners must not keep
       * an object alive that JavaScript has let go of, or its release would wait for a sweep.
       */
      std::weak_ptr<objects::ObjectState> state;
      /**
       * An object declares a handful of events, and only the ones with a listener are here, so a
       * scan comparing indices beats a map: no hashing, and each compare is one load.
       */
      std::vector<EventListeners> byEvent;

      [[nodiscard]] EventListeners* find(int eventIndex) noexcept;
      [[nodiscard]] const EventListeners* find(int eventIndex) const noexcept;
    };

    std::unordered_map<objects::ObjectId::Value, Entry> entries_;
  };
} // namespace expo::modules::v2::events
