#include <expo-jsi/LazyObject.h>

#include <utility>

namespace expo::jsi {
  LazyObject::LazyObject(LazyObjectInitializer initializer)
    : initializer_(std::move(initializer)) {
  }

  LazyObject::~LazyObject() {
    backedObject_ = nullptr;
  }

  facebook::jsi::Value LazyObject::get(facebook::jsi::Runtime& rt, const facebook::jsi::PropNameID& name) {
    if (!backedObject_) {
      if (name.utf8(rt) == "$$typeof") {
        // React Native asks for this property for some reason, we can just ignore it.
        return facebook::jsi::Value::undefined();
      }
      initializeBackedObject(rt);
    }
    return backedObject_ ? backedObject_->getProperty(rt, name) : facebook::jsi::Value::undefined();
  }

  void LazyObject::set(facebook::jsi::Runtime& rt, const facebook::jsi::PropNameID& name,
                       const facebook::jsi::Value& value) {
    if (!backedObject_) {
      initializeBackedObject(rt);
    }
    if (backedObject_) {
      backedObject_->setProperty(rt, name, value);
    }
  }

  std::vector<facebook::jsi::PropNameID> LazyObject::getPropertyNames(facebook::jsi::Runtime& rt) {
    if (!backedObject_) {
      initializeBackedObject(rt);
    }
    if (!backedObject_) {
      return {};
    }
    const facebook::jsi::Array propertyNames = backedObject_->getPropertyNames(rt);
    const size_t count = propertyNames.size(rt);
    std::vector<facebook::jsi::PropNameID> names;
    names.reserve(count);
    for (size_t i = 0; i < count; i++) {
      names.push_back(
        facebook::jsi::PropNameID::forString(
          rt,
          propertyNames.getValueAtIndex(rt, i).getString(rt)
        )
      );
    }
    return names;
  }
} // namespace expo::jsi
