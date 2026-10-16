#include <expo-modules-cpp/internal/ListenerTable.h>

#include <algorithm>

#include <expo-modules-cpp/internal/Log.h>

namespace expo::modules::v2::cpp::detail {
  namespace {
    constexpr size_t kPair = 2;

    js::Value makeSymbol(js::Runtime& rt) {
      return rt.global().getPropertyAsFunction(rt, "Symbol").call(rt, js::String::createFromAscii(rt, "expo.listeners"));
    }

    void callOne(
      js::Runtime& rt,
      const js::Function& listener,
      const js::Object& thisObject,
      const js::Value* args,
      const size_t count
    ) {
      try {
        listener.callWithThis(rt, thisObject, args, count);
      } catch (const js::JSError& error) {
        logError("An event listener threw: " + error.getMessage() + "\n" + error.getStack());
      }
    }

    js::Function functionAt(js::Runtime& rt, const js::Array& listeners, const size_t position) {
      return listeners.getValueAtIndex(rt, position + 1).asObject(rt).asFunction(rt);
    }
  } // namespace

  ListenerTable::ListenerTable(js::Runtime& rt)
    : symbol_(makeSymbol(rt)), key_(js::PropNameID::forSymbol(rt, symbol_.getSymbol(rt))) {
  }

  std::optional<js::Array> ListenerTable::storeOf(js::Runtime& rt, const js::Object& self) const {
    const js::Value store = self.getProperty(rt, key_);
    if (!store.isObject()) {
      return std::nullopt;
    }
    js::Object object = store.getObject(rt);
    if (!object.isArray(rt)) [[unlikely]] {
      return std::nullopt;
    }
    return std::move(object).getArray(rt);
  }

  js::Array ListenerTable::storeFor(
    js::Runtime& rt,
    const js::Function& defineProperty,
    const js::Object& self,
    const size_t eventCount
  ) {
    if (std::optional<js::Array> existing = storeOf(rt, self)) {
      return std::move(*existing);
    }

    // As long as the object has events, since an array cannot be written past its end - and the
    // store cannot be replaced once it is defined.
    js::Array created(rt, eventCount);
    js::Object descriptor(rt);
    descriptor.setProperty(rt, "value", created);
    descriptor.setProperty(rt, "writable", false);
    descriptor.setProperty(rt, "enumerable", false);
    descriptor.setProperty(rt, "configurable", false);
    defineProperty.call(rt, self, js::Value(rt, symbol_), descriptor);
    return created;
  }

  std::optional<js::Array> ListenerTable::listenersIn(js::Runtime& rt, const js::Array& store, const uint32_t eventIndex) {
    if (eventIndex >= store.size(rt)) {
      return std::nullopt;
    }
    const js::Value listeners = store.getValueAtIndex(rt, eventIndex);
    if (!listeners.isObject()) {
      return std::nullopt;
    }
    js::Object object = listeners.getObject(rt);
    if (!object.isArray(rt)) [[unlikely]] {
      return std::nullopt;
    }
    return std::move(object).getArray(rt);
  }

  ListenerTable::Added ListenerTable::add(
    js::Runtime& rt,
    const js::Function& defineProperty,
    const std::shared_ptr<NativeObject>& owner,
    const js::Object& self,
    const uint32_t eventIndex,
    const js::Function& listener
  ) {
    const js::Array store = storeFor(rt, defineProperty, self, owner->events().size());

    // A new array, never an edit in place: whoever is calling the listeners holds the old one.
    const std::optional<js::Array> existing = listenersIn(rt, store, eventIndex);
    const size_t length = existing.has_value() ? existing->size(rt) : 0;
    js::Array grown(rt, length + kPair);
    for (size_t index = 0; index < length; index++) {
      grown.setValueAtIndex(rt, index, existing->getValueAtIndex(rt, index));
    }

    const uint64_t ticket = ++lastTicket_;
    grown.setValueAtIndex(rt, length, js::Value(static_cast<double>(ticket)));
    grown.setValueAtIndex(rt, length + 1, listener);
    store.setValueAtIndex(rt, eventIndex, grown);

    const bool first = length == 0;
    if (first) {
      markObserved(owner, eventIndex);
    }
    return Added{.ticket = ticket, .first = first};
  }

  bool ListenerTable::erasePair(
    js::Runtime& rt,
    const js::Array& store,
    const NativeObject::Id id,
    const uint32_t eventIndex,
    const js::Array& listeners,
    const size_t position
  ) {
    const size_t length = listeners.size(rt);
    if (length == kPair) {
      store.setValueAtIndex(rt, eventIndex, js::Value::undefined());
      unmarkObserved(id, eventIndex);
      return true;
    }

    // A new array, never an edit in place: whoever is calling the listeners holds the old one.
    js::Array rebuilt(rt, length - kPair);
    size_t next = 0;
    for (size_t index = 0; index < length; index++) {
      if (index == position || index == position + 1) {
        continue;
      }
      rebuilt.setValueAtIndex(rt, next++, listeners.getValueAtIndex(rt, index));
    }
    store.setValueAtIndex(rt, eventIndex, rebuilt);
    return false;
  }

  bool ListenerTable::remove(
    js::Runtime& rt,
    const js::Object& self,
    const NativeObject::Id id,
    const uint32_t eventIndex,
    const js::Function& listener
  ) {
    const std::optional<js::Array> store = storeOf(rt, self);
    if (!store.has_value()) {
      return false;
    }
    const std::optional<js::Array> listeners = listenersIn(rt, *store, eventIndex);
    if (!listeners.has_value()) {
      return false;
    }

    const js::Value wanted(rt, listener);
    const size_t length = listeners->size(rt);
    for (size_t position = 0; position + 1 < length; position += kPair) {
      if (js::Value::strictEquals(rt, wanted, listeners->getValueAtIndex(rt, position + 1))) {
        return erasePair(rt, *store, id, eventIndex, *listeners, position);
      }
    }
    return false;
  }

  bool ListenerTable::removeTicket(
    js::Runtime& rt,
    const js::Object& self,
    const NativeObject::Id id,
    const uint32_t eventIndex,
    const uint64_t ticket
  ) {
    const std::optional<js::Array> store = storeOf(rt, self);
    if (!store.has_value()) {
      return false;
    }
    const std::optional<js::Array> listeners = listenersIn(rt, *store, eventIndex);
    if (!listeners.has_value()) {
      return false;
    }

    const size_t length = listeners->size(rt);
    for (size_t position = 0; position + 1 < length; position += kPair) {
      const js::Value candidate = listeners->getValueAtIndex(rt, position);
      if (candidate.isNumber() && candidate.getNumber() == static_cast<double>(ticket)) {
        return erasePair(rt, *store, id, eventIndex, *listeners, position);
      }
    }
    return false;
  }

  bool ListenerTable::removeAll(js::Runtime& rt, const js::Object& self, const NativeObject::Id id, const uint32_t eventIndex) {
    const std::optional<js::Array> store = storeOf(rt, self);
    if (!store.has_value() || !listenersIn(rt, *store, eventIndex).has_value()) {
      return false;
    }
    store->setValueAtIndex(rt, eventIndex, js::Value::undefined());
    unmarkObserved(id, eventIndex);
    return true;
  }

  size_t ListenerTable::count(js::Runtime& rt, const js::Object& self, const uint32_t eventIndex) const {
    const std::optional<js::Array> store = storeOf(rt, self);
    if (!store.has_value()) {
      return 0;
    }
    const std::optional<js::Array> listeners = listenersIn(rt, *store, eventIndex);
    return listeners.has_value() ? listeners->size(rt) / kPair : 0;
  }

  bool ListenerTable::isObserved(const NativeObject::Id id, const uint32_t eventIndex) const {
    const auto entry = entries_.find(id);
    return entry != entries_.end() && std::ranges::find(entry->second.events, eventIndex) != entry->second.events.end();
  }

  void ListenerTable::call(
    js::Runtime& rt,
    const js::Object& self,
    const uint32_t eventIndex,
    const js::Value* args,
    const size_t count
  ) const {
    const std::optional<js::Array> store = storeOf(rt, self);
    if (!store.has_value()) {
      return;
    }
    const std::optional<js::Array> listeners = listenersIn(rt, *store, eventIndex);
    if (!listeners.has_value()) {
      return;
    }

    const size_t length = listeners->size(rt);
    if (length == kPair) {
      // The common case: one listener, no snapshot. The function is taken before the call, so the
      // listener removing itself cannot pull the array out from under us.
      const js::Function only = functionAt(rt, *listeners, 0);
      callOne(rt, only, self, args, count);
      return;
    }

    // A snapshot, because a listener may add or remove listeners: the ones added now are not
    // called, and the ones removed now are called one last time - Node's EventEmitter rule.
    std::vector<js::Function> snapshot;
    snapshot.reserve(length / kPair);
    for (size_t position = 0; position + 1 < length; position += kPair) {
      snapshot.push_back(functionAt(rt, *listeners, position));
    }
    for (const js::Function& listener: snapshot) {
      callOne(rt, listener, self, args, count);
    }
  }

  void ListenerTable::markObserved(const std::shared_ptr<NativeObject>& owner, const uint32_t eventIndex) {
    auto [entry, inserted] = entries_.try_emplace(owner->objectId());
    if (inserted) {
      entry->second.owner = owner;
    }
    if (std::ranges::find(entry->second.events, eventIndex) == entry->second.events.end()) {
      entry->second.events.push_back(eventIndex);
    }
  }

  void ListenerTable::unmarkObserved(const NativeObject::Id id, const uint32_t eventIndex) {
    const auto entry = entries_.find(id);
    if (entry == entries_.end()) {
      return;
    }
    std::vector<uint32_t>& events = entry->second.events;
    events.erase(std::remove(events.begin(), events.end(), eventIndex), events.end());
    if (events.empty()) {
      entries_.erase(entry);
    }
  }
} // namespace expo::modules::v2::cpp::detail
