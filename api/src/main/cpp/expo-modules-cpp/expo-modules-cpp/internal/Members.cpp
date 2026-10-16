#include <expo-modules-cpp/internal/Members.h>

#include <utility>

#include <expo-modules-cpp/internal/Invoke.h>
#include <expo-modules-cpp/internal/RuntimeData.h>

namespace expo::modules::v2::cpp::detail {
  js::Function makeHostFunction(js::Runtime& rt, const FunctionSpec& spec, std::shared_ptr<NativeObject> owner) {
    return js::Function::createFromHostFunction(
      rt,
      js::PropNameID::forUtf8(rt, spec.name),
      spec.arity,
      [owner = std::move(owner), spec = &spec](
      js::Runtime& runtime,
      const js::Value& thisValue,
      const js::Value* args,
      const size_t count
    ) -> js::Value {
        return callFunction(*spec, runtime, thisValue, args, count, owner.get());
      }
    );
  }

  void defineData(
    RuntimeData& data,
    const js::Object& object,
    const std::string& name,
    js::Value value,
    const PropertyFlags flags
  ) {
    js::Runtime& rt = data.runtime();
    js::Object descriptor(rt);
    descriptor.setProperty(rt, "value", std::move(value));
    descriptor.setProperty(rt, "writable", flags.writable);
    descriptor.setProperty(rt, "enumerable", flags.enumerable);
    descriptor.setProperty(rt, "configurable", flags.configurable);
    data.definePropertyFunction().call(rt, object, js::String::createFromUtf8(rt, name), descriptor);
  }

  void defineAccessor(
    RuntimeData& data,
    const js::Object& object,
    const std::string& name,
    js::Function getter,
    const js::Function* const setter,
    const PropertyFlags flags
  ) {
    js::Runtime& rt = data.runtime();
    js::Object descriptor(rt);
    descriptor.setProperty(rt, "get", std::move(getter));
    if (setter != nullptr) {
      descriptor.setProperty(rt, "set", js::Value(rt, *setter));
    }
    descriptor.setProperty(rt, "enumerable", flags.enumerable);
    descriptor.setProperty(rt, "configurable", flags.configurable);
    data.definePropertyFunction().call(rt, object, js::String::createFromUtf8(rt, name), descriptor);
  }

  js::Function makeConstantGetter(
    js::Runtime& rt,
    const ConstantSpec& spec,
    std::shared_ptr<NativeObject> owner,
    const bool hidden
  ) {
    return js::Function::createFromHostFunction(
      rt,
      js::PropNameID::forUtf8(rt, spec.name),
      0,
      [owner = std::move(owner), spec = &spec, hidden](
      js::Runtime& runtime,
      const js::Value& thisValue,
      const js::Value*,
      size_t
    ) -> js::Value {
        js::Value value = callFunction(spec->getter, runtime, thisValue, nullptr, 0, owner.get());

        if (thisValue.isObject()) {
          RuntimeData* data = RuntimeData::find(runtime);
          if (data != nullptr) {
            try {
              defineData(
                *data,
                thisValue.getObject(runtime),
                spec->name,
                js::Value(runtime, value),
                PropertyFlags{.writable = false, .enumerable = !hidden, .configurable = false}
              );
            } catch (const js::JSError&) {
              // The getter was called on an object that cannot take the property, such as a frozen
              // one. The value is still right; only the caching is lost.
            }
          }
        }
        return value;
      }
    );
  }

  void defineProperties(
    RuntimeData& data,
    const js::Object& object,
    const std::vector<PropertySpec>& properties,
    const std::vector<ConstantSpec>& constants,
    const std::shared_ptr<NativeObject>& owner,
    const bool hiddenConstants
  ) {
    js::Runtime& rt = data.runtime();

    for (const PropertySpec& property: properties) {
      js::Function getter = makeHostFunction(rt, property.getter, owner);
      if (property.setter.has_value()) {
        const js::Function setter = makeHostFunction(rt, *property.setter, owner);
        defineAccessor(data, object, property.name, std::move(getter), &setter, {.enumerable = true});
      } else {
        defineAccessor(data, object, property.name, std::move(getter), nullptr, {.enumerable = true});
      }
    }

    for (const ConstantSpec& constant: constants) {
      if (constant.lazy) {
        // Configurable, so the first read can replace the accessor with the value.
        defineAccessor(
          data,
          object,
          constant.name,
          makeConstantGetter(rt, constant, owner, hiddenConstants),
          nullptr,
          {.enumerable = !hiddenConstants, .configurable = true}
        );
      } else {
        defineData(
          data,
          object,
          constant.name,
          callFunction(constant.getter, rt, js::Value::undefined(), nullptr, 0, owner.get()),
          {.enumerable = !hiddenConstants}
        );
      }
    }
  }
} // namespace expo::modules::v2::cpp::detail
