#pragma once

#include <cstdint>
#include <memory>
#include <optional>
#include <unordered_map>
#include <vector>

#include <expo-modules-cpp/Defines.h>
#include <expo-modules-cpp/NativeObject.h>

namespace expo::modules::v2::cpp::detail {
  /**
   * The listeners JavaScript added to the objects of one runtime. It belongs to the JS thread and to
   * the runtime: `RuntimeData` owns it.
   *
   * The listener functions themselves are kept on the emitter's own JavaScript object, under a
   * private symbol, and not here. A native table that held them would be a root: a listener that
   * mentions its emitter - `obj.addListener('change', () => obj.value)` - would keep the emitter
   * alive through the table, and with it the native object, for as long as the runtime lives. Kept on
   * the object, the listener and the emitter are an ordinary cycle that the collector takes.
   *
   * What the table keeps is native bookkeeping: which events of which object this runtime observes,
   * so it can tell native code when the object goes away without removing its listeners.
   *
   * On the object the listeners are an array indexed by event, and each event's listeners a flat
   * array of `ticket, function` pairs. A ticket is what a subscription's `remove` holds in place of
   * the function: a number, so the subscription owns no JavaScript value.
   */
  class ListenerTable {
  public:
    explicit ListenerTable(js::Runtime& rt);

    ListenerTable(const ListenerTable&) = delete;
    ListenerTable& operator=(const ListenerTable&) = delete;

    struct Added {
      uint64_t ticket;
      /** The first listener for that event on that object: native code starts observing. */
      bool first;
    };

    /** Adds [listener] to [self], which is [owner]'s JavaScript object. */
    Added add(
      js::Runtime& rt,
      const js::Function& defineProperty,
      const std::shared_ptr<NativeObject>& owner,
      const js::Object& self,
      uint32_t eventIndex,
      const js::Function& listener
    );

    /** Removes one occurrence of [listener]; true when it was the last one for that event. */
    bool remove(js::Runtime& rt, const js::Object& self, NativeObject::Id id, uint32_t eventIndex, const js::Function& listener);

    /** Removes the listener a subscription stands for; true when it was the last one for that event. */
    bool removeTicket(js::Runtime& rt, const js::Object& self, NativeObject::Id id, uint32_t eventIndex, uint64_t ticket);

    /** Removes every listener of that event; true when there was at least one. */
    bool removeAll(js::Runtime& rt, const js::Object& self, NativeObject::Id id, uint32_t eventIndex);

    [[nodiscard]] size_t count(js::Runtime& rt, const js::Object& self, uint32_t eventIndex) const;

    /** Whether any listener of that event is registered; no JavaScript is touched. */
    [[nodiscard]] bool isObserved(NativeObject::Id id, uint32_t eventIndex) const;

    [[nodiscard]] bool has(NativeObject::Id id) const { return entries_.contains(id); }

    /**
     * Calls the listeners [self] has for [eventIndex]. As on the web, a throwing listener neither
     * stops the others nor reaches the emitter, and a listener that adds or removes listeners
     * changes the next call, not this one.
     */
    void call(js::Runtime& rt, const js::Object& self, uint32_t eventIndex, const js::Value* args, size_t count) const;

    /**
     * Forgets [id], calling `onStop(owner, eventIndex)` for each event that still had a listener:
     * the object went away, so nothing else will ever remove them. Nothing is reported once the
     * owner itself is gone.
     */
    template<typename OnStop>
    void drop(const NativeObject::Id id, OnStop&& onStop) {
      const auto found = entries_.find(id);
      if (found == entries_.end()) {
        return;
      }
      // Detached first: `onStop` runs user code, which may reach back into this table.
      Entry entry = std::move(found->second);
      entries_.erase(found);

      const std::shared_ptr<NativeObject> owner = entry.owner.lock();
      if (owner == nullptr) {
        return;
      }
      for (const uint32_t eventIndex: entry.events) {
        onStop(*owner, eventIndex);
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
      /** Weak on purpose: listeners must not keep an object alive that JavaScript let go of. */
      std::weak_ptr<NativeObject> owner;
      /** The events that have a listener. */
      std::vector<uint32_t> events;
    };

    /** The array of per-event listener arrays on [self], if it has one. */
    [[nodiscard]] std::optional<js::Array> storeOf(js::Runtime& rt, const js::Object& self) const;

    /** [storeOf], creating it - out of reach of `Object.keys` and of anything that rewrites it. */
    js::Array storeFor(js::Runtime& rt, const js::Function& defineProperty, const js::Object& self, size_t eventCount);

    /** The flat `ticket, function` array of one event in [store], if it has listeners. */
    [[nodiscard]] static std::optional<js::Array> listenersIn(js::Runtime& rt, const js::Array& store, uint32_t eventIndex);

    /** Drops the pair at [position] from [store]'s array for the event; true when it was the last. */
    bool erasePair(
      js::Runtime& rt,
      const js::Array& store,
      NativeObject::Id id,
      uint32_t eventIndex,
      const js::Array& listeners,
      size_t position
    );

    void markObserved(const std::shared_ptr<NativeObject>& owner, uint32_t eventIndex);

    void unmarkObserved(NativeObject::Id id, uint32_t eventIndex);

    /** The private symbol the listeners are stored under, and the property name made from it. */
    js::Value symbol_;
    js::PropNameID key_;

    uint64_t lastTicket_ = 0;
    std::unordered_map<NativeObject::Id, Entry> entries_;
  };
} // namespace expo::modules::v2::cpp::detail
