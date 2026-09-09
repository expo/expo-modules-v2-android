#include <expo-modules-v2/binders/PropertyBinder.h>

#include <utility>

namespace expo::modules::v2 {
  PropertyBinder::PropertyBinder(
    descriptor::HostPropertySpec spec,
    const kolibri::GlobalRef<>& instance
  ) : name_(std::move(spec.name)),
      getter_(std::move(spec.getter), instance) {
    if (spec.setter.has_value()) {
      setter_.emplace(std::move(*spec.setter), instance);
    }
  }

  PropertyBinder::PropertyBinder(
    std::string name,
    const std::shared_ptr<descriptor::HostFunctionSpec>& getter,
    const std::shared_ptr<descriptor::HostFunctionSpec>& setter,
    const kolibri::GlobalRef<>& instance
  ) : name_(std::move(name)),
      getter_(getter, instance) {
    if (setter != nullptr) {
      setter_.emplace(setter, instance);
    }
  }

  const std::string& PropertyBinder::name() const {
    return name_;
  }

  bool PropertyBinder::hasSetter() const {
    return setter_.has_value();
  }

  facebook::jsi::Function PropertyBinder::createGetter(facebook::jsi::Runtime& rt) const {
    return getter_.createFunction(rt);
  }

  facebook::jsi::Function PropertyBinder::createSetter(facebook::jsi::Runtime& rt) const {
    return setter_->createFunction(rt);
  }

  facebook::jsi::Value PropertyBinder::get(facebook::jsi::Runtime& rt) const {
    return getter_.invoke(rt, nullptr, 0);
  }

  void PropertyBinder::set(facebook::jsi::Runtime& rt, const facebook::jsi::Value& value) const {
    setter_->invoke(rt, &value, 1);
  }
} // namespace expo::modules::v2
