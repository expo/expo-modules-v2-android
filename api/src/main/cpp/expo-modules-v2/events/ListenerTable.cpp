#include <expo-modules-v2/events/ListenerTable.h>

#include <algorithm>

#include <expo-modules-v2/utils/Log.h>

namespace expo::modules::v2::events {
  ListenerTable::EventListeners* ListenerTable::Entry::find(const int eventIndex) noexcept {
    for (EventListeners& named: byEvent) {
      if (named.eventIndex == eventIndex) {
        return &named;
      }
    }
    return nullptr;
  }

  const ListenerTable::EventListeners* ListenerTable::Entry::find(const int eventIndex) const noexcept {
    for (const EventListeners& named: byEvent) {
      if (named.eventIndex == eventIndex) {
        return &named;
      }
    }
    return nullptr;
  }

  namespace {
    void callOne(
      facebook::jsi::Runtime& rt,
      const facebook::jsi::Function& listener,
      const facebook::jsi::Object& thisObject,
      const facebook::jsi::Value* args,
      const size_t count
    ) {
      // As on the web, a throwing listener neither stops the others nor reaches the emitter: an
      // error in one module's (or the app's) code must not change another module's behaviour.
      try {
        listener.callWithThis(rt, thisObject, args, count);
      } catch (const facebook::jsi::JSError& error) {
        logLine("An event listener threw: " + error.getMessage() + "\n" + error.getStack());
      }
    }
  } // namespace

  bool ListenerTable::add(
    facebook::jsi::Runtime& rt,
    const std::shared_ptr<objects::ObjectState>& state,
    const int eventIndex,
    const facebook::jsi::Function& listener
  ) {
    auto [entry, inserted] = entries_.try_emplace(state->objectId());
    if (inserted) {
      entry->second.state = state;
    }

    EventListeners* named = entry->second.find(eventIndex);
    if (named == nullptr) {
      named = &entry->second.byEvent.emplace_back(EventListeners{.eventIndex = eventIndex, .listeners = {}});
    }
    named->listeners.emplace_back(rt, listener);
    return named->listeners.size() == 1;
  }

  bool ListenerTable::remove(
    facebook::jsi::Runtime& rt,
    const objects::ObjectId::Value objectId,
    const int eventIndex,
    const facebook::jsi::Function& listener
  ) {
    const auto entry = entries_.find(objectId);
    if (entry == entries_.end()) {
      return false;
    }
    EventListeners* named = entry->second.find(eventIndex);
    if (named == nullptr || named->listeners.empty()) {
      return false;
    }

    std::vector<facebook::jsi::Value>& listeners = named->listeners;
    const facebook::jsi::Value wanted(rt, listener);
    const auto found = std::ranges::find_if(listeners, [&](const facebook::jsi::Value& item) {
      return facebook::jsi::Value::strictEquals(rt, wanted, item);
    });
    if (found == listeners.end()) {
      return false;
    }

    listeners.erase(found);
    return listeners.empty();
  }

  bool ListenerTable::removeAll(const objects::ObjectId::Value objectId, const int eventIndex) {
    const auto entry = entries_.find(objectId);
    if (entry == entries_.end()) {
      return false;
    }
    EventListeners* eventListeners = entry->second.find(eventIndex);
    if (eventListeners == nullptr) {
      return false;
    }

    const bool hadListeners = !eventListeners->listeners.empty();
    eventListeners->listeners.clear();
    return hadListeners;
  }

  size_t ListenerTable::count(
    const objects::ObjectId::Value objectId,
    const int eventIndex
  ) const {
    const std::vector<facebook::jsi::Value>* listeners = find(objectId, eventIndex);
    return listeners == nullptr ? 0 : listeners->size();
  }

  const std::vector<facebook::jsi::Value>* ListenerTable::find(
    const objects::ObjectId::Value objectId,
    const int eventIndex
  ) const {
    const auto entry = entries_.find(objectId);
    if (entry == entries_.end()) {
      return nullptr;
    }
    const EventListeners* named = entry->second.find(eventIndex);
    if (named == nullptr || named->listeners.empty()) {
      return nullptr;
    }
    return &named->listeners;
  }

  void ListenerTable::call(
    facebook::jsi::Runtime& rt,
    const std::vector<facebook::jsi::Value>& listeners,
    const facebook::jsi::Object& thisObject,
    const facebook::jsi::Value* args,
    const size_t count
  ) {
    if (listeners.size() == 1) {
      // The common case: one listener, no snapshot. The function is taken before the call, so the
      // listener removing itself cannot pull the vector out from under us.
      const facebook::jsi::Function only = listeners.front().asObject(rt).asFunction(rt);
      callOne(rt, only, thisObject, args, count);
      return;
    }

    // A snapshot, because a listener may add or remove listeners: the ones added now are not
    // called, and the ones removed now are called one last time - Node's EventEmitter rule.
    std::vector<facebook::jsi::Function> snapshot;
    snapshot.reserve(listeners.size());
    for (const facebook::jsi::Value& listener: listeners) {
      snapshot.push_back(listener.asObject(rt).asFunction(rt));
    }
    for (const facebook::jsi::Function& listener: snapshot) {
      callOne(rt, listener, thisObject, args, count);
    }
  }
} // namespace expo::modules::v2::events
