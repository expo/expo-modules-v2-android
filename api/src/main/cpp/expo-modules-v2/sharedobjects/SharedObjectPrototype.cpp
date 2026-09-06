#include <expo-modules-v2/sharedobjects/SharedObjectPrototype.h>

#include <optional>
#include <string>
#include <string_view>
#include <utility>

#include <expo-jsi/ChainedNativeState.h>

#include <expo-modules-v2/sharedobjects/SharedObjectClassSpec.h>
#include <expo-modules-v2/sharedobjects/SharedObjectState.h>

#include "SharedObjects.h"

namespace expo::modules::v2::sharedobjects {
  namespace {
    constexpr std::string_view kRelease = "release";

    constexpr std::string_view kObjectId = "__expoSharedObjectId";

    SharedObjectState* receiverOf(
      facebook::jsi::Runtime& rt,
      const facebook::jsi::Value& thisValue,
      const std::string_view member
    ) {
      if (thisValue.isObject()) {
        const facebook::jsi::Object self = thisValue.asObject(rt);
        if (const auto state = SharedObjects::stateOf(rt, self)) {
          return state.get();
        }
      }

      throw facebook::jsi::JSError(
        rt,
        "'" + std::string(member) + "' called on something that is not a shared object"
      );
    }

    SharedObjectState* liveReceiverOf(
      facebook::jsi::Runtime& rt,
      const facebook::jsi::Value& thisValue,
      const std::string_view member
    ) {
      SharedObjectState* state = receiverOf(rt, thisValue, member);
      if (state->released()) {
        throw facebook::jsi::JSError(
          rt,
          "Cannot access '" + std::string(member) + "' on a released " + state->spec().name
        );
      }
      return state;
    }

    void defineMember(
      facebook::jsi::Runtime& rt,
      const facebook::jsi::Object& prototype,
      const facebook::jsi::Function& defineProperty,
      const std::string& name,
      const facebook::jsi::Object& value
    ) {
      facebook::jsi::Object descriptor(rt);
      descriptor.setProperty(rt, "value", value);
      descriptor.setProperty(rt, "enumerable", false);
      descriptor.setProperty(rt, "configurable", false);

      defineProperty.call(
        rt,
        prototype,
        facebook::jsi::String::createFromUtf8(rt, name),
        descriptor
      );
    }

    void defineAccessor(
      facebook::jsi::Runtime& rt,
      const facebook::jsi::Object& prototype,
      const facebook::jsi::Function& defineProperty,
      const std::string& name,
      facebook::jsi::Function getter,
      std::optional<facebook::jsi::Function> setter,
      const bool enumerable = true
    ) {
      facebook::jsi::Object descriptor(rt);
      descriptor.setProperty(rt, "get", std::move(getter));
      if (setter.has_value()) {
        descriptor.setProperty(rt, "set", std::move(*setter));
      }
      descriptor.setProperty(rt, "enumerable", enumerable);
      descriptor.setProperty(rt, "configurable", false);

      defineProperty.call(
        rt,
        prototype,
        facebook::jsi::String::createFromUtf8(rt, name),
        descriptor
      );
    }

    [[nodiscard]] facebook::jsi::Function hostFunction(
      facebook::jsi::Runtime& rt,
      const std::string& name,
      const unsigned int arity,
      facebook::jsi::HostFunctionType body
    ) {
      return facebook::jsi::Function::createFromHostFunction(
        rt,
        facebook::jsi::PropNameID::forUtf8(rt, name),
        arity,
        std::move(body)
      );
    }
  } // namespace

  void installPrototypeMembers(
    facebook::jsi::Runtime& rt,
    const facebook::jsi::Object& prototype,
    const SharedObjectClassSpec& spec
  ) {
    const facebook::jsi::Function defineProperty = rt.global()
      .getPropertyAsObject(rt, "Object")
      .getPropertyAsFunction(rt, "defineProperty");

    constexpr std::string release(kRelease);
    defineMember(
      rt,
      prototype,
      defineProperty,
      release,
      hostFunction(
        rt,
        release,
        0,
        [](
        facebook::jsi::Runtime& rt,
        const facebook::jsi::Value& thisValue,
        const facebook::jsi::Value*,
        size_t) -> facebook::jsi::Value {
          receiverOf(rt, thisValue, kRelease)->release();
          return facebook::jsi::Value::undefined();
        }
      )
    );

    constexpr std::string objectId(kObjectId);
    defineAccessor(
      rt,
      prototype,
      defineProperty,
      objectId,
      hostFunction(
        rt,
        objectId,
        0,
        [](
        facebook::jsi::Runtime& rt,
        const facebook::jsi::Value& thisValue,
        const facebook::jsi::Value*,
        size_t) -> facebook::jsi::Value {
          return facebook::jsi::Value(receiverOf(rt, thisValue, kObjectId)->objectId());
        }
      ),
      std::nullopt,
      /* enumerable */ false
    );

    for (size_t i = 0; i < spec.functions.size(); i++) {
      const descriptor::HostFunctionSpec& function = *spec.functions[i];

      defineMember(
        rt,
        prototype,
        defineProperty,
        function.name,
        hostFunction(
          rt,
          function.name,
          static_cast<unsigned int>(function.argTypes.size()),
          [index = static_cast<uint32_t>(i), name = function.name](
          facebook::jsi::Runtime& rt,
          const facebook::jsi::Value& thisValue,
          const facebook::jsi::Value* args,
          const size_t count
        ) -> facebook::jsi::Value {
            return liveReceiverOf(rt, thisValue, name)->function(index).invoke(rt, args, count);
          }
        )
      );
    }

    for (size_t i = 0; i < spec.properties.size(); i++) {
      const SharedObjectClassSpec::Property& property = spec.properties[i];

      const uint32_t index = static_cast<uint32_t>(i);

      std::optional<facebook::jsi::Function> setter;
      if (property.setter != nullptr) {
        setter = hostFunction(
          rt,
          property.name,
          1,
          [index, name = property.name](
          facebook::jsi::Runtime& rt,
          const facebook::jsi::Value& thisValue,
          const facebook::jsi::Value* args,
          const size_t count
        ) -> facebook::jsi::Value {
            if (count < 1) {
              throw facebook::jsi::JSError(rt, "Setting '" + name + "' needs a value");
            }
            liveReceiverOf(rt, thisValue, name)->property(index).set(rt, args[0]);
            return facebook::jsi::Value::undefined();
          }
        );
      }

      defineAccessor(
        rt,
        prototype,
        defineProperty,
        property.name,
        hostFunction(
          rt,
          property.name,
          0,
          [index, name = property.name](
          facebook::jsi::Runtime& rt,
          const facebook::jsi::Value& thisValue,
          const facebook::jsi::Value*,
          size_t) -> facebook::jsi::Value {
            return liveReceiverOf(rt, thisValue, name)->property(index).get(rt);
          }
        ),
        std::move(setter)
      );
    }
  }
}
