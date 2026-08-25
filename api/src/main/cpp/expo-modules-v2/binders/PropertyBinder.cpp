#include <expo-modules-v2/binders/PropertyBinder.h>

#include <utility>

namespace expo::modules::v2 {
  PropertyBinder::PropertyBinder(
    descriptor::HostPropertySpec spec,
    std::shared_ptr<kolibri::GlobalRef<>> instance
  ) : name_(std::move(spec.name)),
      getter_(std::move(spec.getter), instance) {
    if (spec.setter.has_value()) {
      setter_.emplace(std::move(*spec.setter), std::move(instance));
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
} // namespace expo::modules::v2
