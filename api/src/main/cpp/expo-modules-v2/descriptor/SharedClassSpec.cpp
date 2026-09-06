#include <expo-modules-v2/descriptor/SharedClassSpec.h>

#include <stdexcept>
#include <string>

#include <expo-modules-v2/sharedobjects/SharedObjectClassRegistry.h>

namespace expo::modules::v2::descriptor {
  const StaticFunctionBinder& SharedClassSpec::binder() {
    if (binder_.has_value()) {
      return *binder_;
    }

    const jclass clazz = sharedobjects::SharedObjectClassRegistry::javaClassOf(classId);
    if (clazz == nullptr) {
      throw std::runtime_error(
        "No shared object class is registered for id " + std::to_string(classId)
      );
    }

    return binder_.emplace(constructor, clazz);
  }
}
