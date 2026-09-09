#include <expo-modules-v2/events/EventEmitter.h>

#include <array>
#include <memory>
#include <string>
#include <utility>

#include <kolibri/env.h>

#include <expo-modules-v2/async/AsyncRuntimeState.h>
#include <expo-modules-v2/descriptor/EventSpec.h>
#include <expo-modules-v2/events/ListenerTable.h>
#include <expo-modules-v2/jni/JEventSupport.h>
#include <expo-modules-v2/objects/ObjectNativeState.h>
#include <expo-modules-v2/objects/RuntimeObjects.h>

namespace expo::modules::v2::events {
  namespace {
    constexpr std::string_view kAddListener = "addListener";
    constexpr std::string_view kRemoveListener = "removeListener";
    constexpr std::string_view kRemoveAllListeners = "removeAllListeners";
    constexpr std::string_view kListenerCount = "listenerCount";

    constexpr std::array kMemberNames = {
      kAddListener, kRemoveListener, kRemoveAllListeners, kListenerCount,
    };

    /** The emitter a member was called on: its node, and the runtime tables it lives in. */
    struct Emitter {
      std::shared_ptr<objects::ObjectNativeState> node;
      objects::RuntimeObjects* table;
      async::AsyncRuntimeState* async;

      [[nodiscard]] objects::ObjectId::Value objectId() const { return node->objectId(); }

      [[nodiscard]] ListenerTable& listeners() const { return table->listeners(); }

      [[nodiscard]] jobject instance() const { return node->instance(); }

      void observe(const int eventIndex, const bool observing) const {
        const jobject context = async->context();
        if (context == nullptr) {
          return;
        }
        JEventSupport::observe(kolibri::getEnv(), instance(), eventIndex, context, observing);
      }
    };

    /** A declared event: its spec, and its index, which names it in the table and to Kotlin. */
    struct Declared {
      const descriptor::EventSpec& spec;
      int index;
    };

    Emitter emitterOf(
      facebook::jsi::Runtime& rt,
      const facebook::jsi::Object& object,
      const std::string_view member
    ) {
      std::shared_ptr<objects::ObjectNativeState> node = objects::ObjectNativeState::of(rt, object);
      if (node == nullptr) {
        throw facebook::jsi::JSError(
          rt,
          "'" + std::string(member) + "' called on something that is not a module or shared object"
        );
      }
      if (node->instance() == nullptr) {
        throw facebook::jsi::JSError(
          rt,
          "Cannot call '" + std::string(member) + "' on a released shared object"
        );
      }

      objects::RuntimeObjects* table = objects::RuntimeObjects::find(rt);
      async::AsyncRuntimeState* async = async::AsyncRuntimeState::find(rt);
      if (table == nullptr || async == nullptr) {
        throw facebook::jsi::JSError(rt, "The event emitter is not available in this runtime");
      }

      return Emitter{.node = std::move(node), .table = table, .async = async};
    }

    std::string declaredEvents(const objects::ObjectNativeState& node) {
      std::string names;
      for (const descriptor::EventSpec& spec: node.events()) {
        names += names.empty() ? "'" : ", '";
        names += spec.name;
        names += '\'';
      }
      return names.empty() ? "none" : names;
    }

    /** The declared event the first argument names; throws for anything else. */
    Declared eventOf(
      facebook::jsi::Runtime& rt,
      const Emitter& emitter,
      const facebook::jsi::Value* args,
      const size_t count,
      const std::string_view member
    ) {
      if (count < 1 || !args[0].isString()) {
        throw facebook::jsi::JSError(
          rt,
          "'" + std::string(member) + "' expects an event name as its first argument"
        );
      }
      const std::string name = args[0].getString(rt).utf8(rt);
      const int index = emitter.node->eventIndexOf(name);
      if (index < 0) {
        throw facebook::jsi::JSError(
          rt,
          "'" + name + "' is not an event of this object - it declares: " +
          declaredEvents(*emitter.node)
        );
      }
      return Declared{.spec = *emitter.node->eventAt(index), .index = index};
    }

    facebook::jsi::Function listenerOf(
      facebook::jsi::Runtime& rt,
      const facebook::jsi::Value* args,
      const size_t count,
      const std::string_view member
    ) {
      if (count < 2 || !args[1].isObject() || !args[1].getObject(rt).isFunction(rt)) {
        throw facebook::jsi::JSError(
          rt,
          "'" + std::string(member) + "' expects a listener function as its second argument"
        );
      }
      return args[1].getObject(rt).getFunction(rt);
    }

    void removeListener(
      facebook::jsi::Runtime& rt,
      const facebook::jsi::Object& emitterObject,
      const int eventIndex,
      const facebook::jsi::Function& listener
    ) {
      const Emitter emitter = emitterOf(rt, emitterObject, kRemoveListener);
      if (emitter.listeners().remove(rt, emitter.objectId(), eventIndex, listener)) {
        emitter.observe(eventIndex, false);
      }
    }

    /** What a subscription's `remove` needs: the emitter to find the listener on, and the listener. */
    struct Subscription {
      facebook::jsi::Value emitter;
      facebook::jsi::Value listener;
      int eventIndex;
    };

    facebook::jsi::Value createSubscription(
      facebook::jsi::Runtime& rt,
      const facebook::jsi::Object& emitterObject,
      const int eventIndex,
      const facebook::jsi::Function& listener
    ) {
      auto subscription = std::make_shared<Subscription>(
        Subscription{
          .emitter = facebook::jsi::Value(rt, emitterObject),
          .listener = facebook::jsi::Value(rt, listener),
          .eventIndex = eventIndex,
        }
      );

      facebook::jsi::Object result(rt);
      result.setProperty(
        rt,
        "remove",
        facebook::jsi::Function::createFromHostFunction(
          rt,
          facebook::jsi::PropNameID::forAscii(rt, "remove"),
          0,
          [subscription](
          facebook::jsi::Runtime& rt,
          const facebook::jsi::Value&,
          const facebook::jsi::Value*,
          size_t) -> facebook::jsi::Value {
            removeListener(
              rt,
              subscription->emitter.getObject(rt),
              subscription->eventIndex,
              subscription->listener.getObject(rt).getFunction(rt)
            );
            return facebook::jsi::Value::undefined();
          }
        )
      );
      return facebook::jsi::Value(rt, result);
    }

    facebook::jsi::Object thisObjectOf(
      facebook::jsi::Runtime& rt,
      const facebook::jsi::Value& thisValue,
      const std::string_view member
    ) {
      if (!thisValue.isObject()) {
        throw facebook::jsi::JSError(
          rt,
          "'" + std::string(member) + "' called on something that is not a module or shared object"
        );
      }
      return thisValue.getObject(rt);
    }

    facebook::jsi::Value addListener(
      facebook::jsi::Runtime& rt,
      const facebook::jsi::Value& thisValue,
      const facebook::jsi::Value* args,
      const size_t count
    ) {
      const facebook::jsi::Object self = thisObjectOf(rt, thisValue, kAddListener);
      const Emitter emitter = emitterOf(rt, self, kAddListener);
      const Declared event = eventOf(rt, emitter, args, count, kAddListener);
      const facebook::jsi::Function listener = listenerOf(rt, args, count, kAddListener);

      const bool first = emitter.listeners().add(rt, emitter.node->state(), event.index, listener);
      if (first) {
        emitter.observe(event.index, true);
      }
      return createSubscription(rt, self, event.index, listener);
    }

    facebook::jsi::Value removeListenerMember(
      facebook::jsi::Runtime& rt,
      const facebook::jsi::Value& thisValue,
      const facebook::jsi::Value* args,
      const size_t count
    ) {
      const facebook::jsi::Object self = thisObjectOf(rt, thisValue, kRemoveListener);
      const Emitter emitter = emitterOf(rt, self, kRemoveListener);
      const Declared event = eventOf(rt, emitter, args, count, kRemoveListener);
      const facebook::jsi::Function listener = listenerOf(rt, args, count, kRemoveListener);

      if (emitter.listeners().remove(rt, emitter.objectId(), event.index, listener)) {
        emitter.observe(event.index, false);
      }
      return facebook::jsi::Value::undefined();
    }

    facebook::jsi::Value removeAllListeners(
      facebook::jsi::Runtime& rt,
      const facebook::jsi::Value& thisValue,
      const facebook::jsi::Value* args,
      const size_t count
    ) {
      const facebook::jsi::Object self = thisObjectOf(rt, thisValue, kRemoveAllListeners);
      const Emitter emitter = emitterOf(rt, self, kRemoveAllListeners);
      const Declared event = eventOf(rt, emitter, args, count, kRemoveAllListeners);

      if (emitter.listeners().removeAll(emitter.objectId(), event.index)) {
        emitter.observe(event.index, false);
      }
      return facebook::jsi::Value::undefined();
    }

    facebook::jsi::Value listenerCount(
      facebook::jsi::Runtime& rt,
      const facebook::jsi::Value& thisValue,
      const facebook::jsi::Value* args,
      const size_t count
    ) {
      const facebook::jsi::Object self = thisObjectOf(rt, thisValue, kListenerCount);
      const Emitter emitter = emitterOf(rt, self, kListenerCount);
      const Declared event = eventOf(rt, emitter, args, count, kListenerCount);

      return facebook::jsi::Value(
        static_cast<double>(emitter.listeners().count(emitter.objectId(), event.index))
      );
    }

    void defineMember(
      facebook::jsi::Runtime& rt,
      const facebook::jsi::Object& target,
      const facebook::jsi::Function& defineProperty,
      const std::string_view name,
      const unsigned int arity,
      const facebook::jsi::HostFunctionType& body
    ) {
      facebook::jsi::Object descriptor(rt);
      descriptor.setProperty(
        rt,
        "value",
        facebook::jsi::Function::createFromHostFunction(
          rt,
          facebook::jsi::PropNameID::forUtf8(rt, std::string(name)),
          arity,
          body
        )
      );
      descriptor.setProperty(rt, "enumerable", false);
      descriptor.setProperty(rt, "configurable", false);

      defineProperty.call(
        rt,
        target,
        facebook::jsi::String::createFromUtf8(rt, std::string(name)),
        descriptor
      );
    }
  } // namespace

  void installEmitterMethods(facebook::jsi::Runtime& rt, const facebook::jsi::Object& target) {
    const facebook::jsi::Function defineProperty = rt.global()
      .getPropertyAsObject(rt, "Object")
      .getPropertyAsFunction(rt, "defineProperty");

    defineMember(rt, target, defineProperty, kAddListener, 2, &addListener);
    defineMember(rt, target, defineProperty, kRemoveListener, 2, &removeListenerMember);
    defineMember(rt, target, defineProperty, kRemoveAllListeners, 1, &removeAllListeners);
    defineMember(rt, target, defineProperty, kListenerCount, 1, &listenerCount);
  }

  bool isEmitterMemberName(const std::string_view name) noexcept {
    for (const std::string_view member: kMemberNames) {
      if (member == name) {
        return true;
      }
    }
    return false;
  }
} // namespace expo::modules::v2::events
