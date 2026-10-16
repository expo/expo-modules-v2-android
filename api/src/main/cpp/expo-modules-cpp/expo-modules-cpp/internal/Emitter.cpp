#include <expo-modules-cpp/internal/Emitter.h>

#include <memory>
#include <string>
#include <string_view>
#include <utility>

#include <expo-modules-cpp/Errors.h>
#include <expo-modules-cpp/Event.h>
#include <expo-modules-cpp/SharedObject.h>
#include <expo-modules-cpp/internal/Members.h>
#include <expo-modules-cpp/internal/ObjectNode.h>
#include <expo-modules-cpp/internal/RuntimeData.h>

namespace expo::modules::v2::cpp::detail {
  namespace {
    constexpr std::string_view kAddListener = "addListener";
    constexpr std::string_view kRemoveListener = "removeListener";
    constexpr std::string_view kRemoveAllListeners = "removeAllListeners";
    constexpr std::string_view kListenerCount = "listenerCount";
    constexpr std::string_view kEmit = "emit";

    /** The object a member was called on, and the tables of the runtime it was called in. */
    struct Emitter {
      /** The `this` object. */
      js::Object self;
      /** Borrowed from [self], which the member holds for the whole call. */
      ObjectNode* node;
      RuntimeData* data;

      [[nodiscard]] NativeObject& object() const { return node->object(); }
    };

    [[noreturn]] EXPO_COLD EXPO_NOINLINE void fail(js::Runtime& rt, const std::string& message) {
      throwJSError(rt, message);
    }

    /** What a member does with a shared object that was released. */
    enum class Released { kThrow, kAllow };

    /**
     * Listening to a released object is a mistake, and so is emitting on it. Taking listeners off one
     * is not: cleanup that runs after the owner released the object must not fail.
     */
    Emitter emitterOf(js::Runtime& rt, const js::Value& thisValue, const std::string_view member, const Released released = Released::kThrow) {
      if (thisValue.isObject()) [[likely]] {
        js::Object self = thisValue.getObject(rt);
        if (ObjectNode* const node = ObjectNode::borrow(rt, self)) [[likely]] {
          if (released == Released::kThrow && !node->isModule() && static_cast<SharedObject&>(node->object()).isReleased()) [[unlikely]] {
            fail(rt, "Cannot call '" + std::string(member) + "' on a released shared object");
          }
          RuntimeData* const data = RuntimeData::find(rt);
          if (data == nullptr) [[unlikely]] {
            fail(rt, "The event emitter is not available in this runtime");
          }
          return Emitter{.self = std::move(self), .node = node, .data = data};
        }
      }
      fail(rt, "'" + std::string(member) + "' called on something that is not a module or shared object");
    }

    std::string declaredEvents(const NativeObject& object) {
      std::string names;
      for (const EventBase* const event: object.events()) {
        names += names.empty() ? "'" : ", '";
        names += event->name();
        names += '\'';
      }
      return names.empty() ? "none" : names;
    }

    /** The declared event the first argument names; throws for anything else. */
    EventBase& eventOf(
      js::Runtime& rt,
      const Emitter& emitter,
      const js::Value* args,
      const size_t count,
      const std::string_view member
    ) {
      if (count < 1 || !args[0].isString()) [[unlikely]] {
        fail(rt, "'" + std::string(member) + "' expects an event name as its first argument");
      }
      const std::string name = args[0].getString(rt).utf8(rt);
      for (EventBase* const event: emitter.object().events()) {
        if (event->name() == name) {
          return *event;
        }
      }
      fail(rt, "'" + name + "' is not an event of this object - it declares: " + declaredEvents(emitter.object()));
    }

    js::Function listenerOf(js::Runtime& rt, const js::Value* args, const size_t count, const std::string_view member) {
      if (count < 2 || !args[1].isObject() || !args[1].getObject(rt).isFunction(rt)) [[unlikely]] {
        fail(rt, "'" + std::string(member) + "' expects a listener function as its second argument");
      }
      return args[1].getObject(rt).getFunction(rt);
    }

    /** Detaches the runtime from [event] once nothing in it listens any more. */
    void detachFrom(const Emitter& emitter, const uint32_t eventIndex) {
      emitter.object().events()[eventIndex]->detach(emitter.data->link().get());
    }

    /**
     * The subscription `addListener` returns. It holds numbers and nothing from JavaScript: the
     * emitter and the listener are not its to keep alive, and a listener that holds its own
     * subscription - to unsubscribe from inside - would otherwise be a cycle through native memory
     * that no collection could break.
     */
    js::Value createSubscription(js::Runtime& rt, const NativeObject::Id id, const uint32_t eventIndex, const uint64_t ticket) {
      js::Object result(rt);
      result.setProperty(
        rt,
        "remove",
        js::Function::createFromHostFunction(
          rt,
          js::PropNameID::forAscii(rt, "remove"),
          0,
          [id, eventIndex, ticket](js::Runtime& runtime, const js::Value&, const js::Value*, size_t) -> js::Value {
            RuntimeData* const data = RuntimeData::find(runtime);
            if (data == nullptr) {
              return js::Value::undefined();
            }
            // An emitter that was collected took its listeners with it.
            const js::Value emitterValue = data->lookup(id);
            if (!emitterValue.isObject()) {
              return js::Value::undefined();
            }
            const js::Object self = emitterValue.getObject(runtime);
            ObjectNode* const node = ObjectNode::borrow(runtime, self);
            if (node != nullptr && data->listeners().removeTicket(runtime, self, id, eventIndex, ticket)) {
              node->object().events()[eventIndex]->detach(data->link().get());
            }
            return js::Value::undefined();
          }
        )
      );
      return js::Value(std::move(result));
    }

    js::Value addListener(js::Runtime& rt, const js::Value& thisValue, const js::Value* args, const size_t count) {
      const Emitter emitter = emitterOf(rt, thisValue, kAddListener);
      EventBase& event = eventOf(rt, emitter, args, count, kAddListener);
      const js::Function listener = listenerOf(rt, args, count, kAddListener);

      const ListenerTable::Added added = emitter.data->listeners().add(
        rt,
        emitter.data->definePropertyFunction(),
        emitter.node->shared(),
        emitter.self,
        event.index(),
        listener
      );
      if (added.first) {
        event.attach(emitter.data->link());
      }
      return createSubscription(rt, emitter.object().objectId(), event.index(), added.ticket);
    }

    js::Value removeListener(js::Runtime& rt, const js::Value& thisValue, const js::Value* args, const size_t count) {
      const Emitter emitter = emitterOf(rt, thisValue, kRemoveListener, Released::kAllow);
      const EventBase& event = eventOf(rt, emitter, args, count, kRemoveListener);
      const js::Function listener = listenerOf(rt, args, count, kRemoveListener);
      if (emitter.data->listeners().remove(rt, emitter.self, emitter.object().objectId(), event.index(), listener)) {
        detachFrom(emitter, event.index());
      }
      return js::Value::undefined();
    }

    js::Value removeAllListeners(js::Runtime& rt, const js::Value& thisValue, const js::Value* args, const size_t count) {
      const Emitter emitter = emitterOf(rt, thisValue, kRemoveAllListeners, Released::kAllow);
      const EventBase& event = eventOf(rt, emitter, args, count, kRemoveAllListeners);
      if (emitter.data->listeners().removeAll(rt, emitter.self, emitter.object().objectId(), event.index())) {
        detachFrom(emitter, event.index());
      }
      return js::Value::undefined();
    }

    js::Value listenerCount(js::Runtime& rt, const js::Value& thisValue, const js::Value* args, const size_t count) {
      const Emitter emitter = emitterOf(rt, thisValue, kListenerCount, Released::kAllow);
      const EventBase& event = eventOf(rt, emitter, args, count, kListenerCount);
      return js::Value(static_cast<double>(emitter.data->listeners().count(rt, emitter.self, event.index())));
    }

    /** `emit` from JavaScript: calls the listeners with the arguments, and tells native code nothing. */
    js::Value emit(js::Runtime& rt, const js::Value& thisValue, const js::Value* args, const size_t count) {
      const Emitter emitter = emitterOf(rt, thisValue, kEmit);
      const EventBase& event = eventOf(rt, emitter, args, count, kEmit);
      emitter.data->listeners().call(rt, emitter.self, event.index(), args + 1, count - 1);
      return js::Value::undefined();
    }

    void defineMember(
      RuntimeData& data,
      const js::Object& target,
      const std::string_view name,
      const unsigned int arity,
      js::HostFunctionType body
    ) {
      js::Runtime& rt = data.runtime();
      defineData(
        data,
        target,
        std::string(name),
        js::Value(js::Function::createFromHostFunction(rt, js::PropNameID::forAscii(rt, std::string(name)), arity, std::move(body))),
        PropertyFlags{}
      );
    }
  } // namespace

  void installEmitterMembers(RuntimeData& data, const js::Object& target) {
    defineMember(data, target, kAddListener, 2, &addListener);
    defineMember(data, target, kRemoveListener, 2, &removeListener);
    defineMember(data, target, kRemoveAllListeners, 1, &removeAllListeners);
    defineMember(data, target, kListenerCount, 1, &listenerCount);
    defineMember(data, target, kEmit, 1, &emit);
  }
} // namespace expo::modules::v2::cpp::detail
