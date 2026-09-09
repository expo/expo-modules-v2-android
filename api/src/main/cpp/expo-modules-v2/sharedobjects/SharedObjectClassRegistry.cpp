#include <expo-modules-v2/sharedobjects/SharedObjectClassRegistry.h>

#include <memory>
#include <mutex>
#include <stdexcept>
#include <string>
#include <unordered_map>
#include <utility>
#include <vector>

#include <kolibri/Ref.h>
#include <kolibri/class.h>
#include <kolibri/env.h>

#include <expo-modules-v2/jni/JSharedObjectRegistry.h>

namespace expo::modules::v2::sharedobjects {
  namespace {
    std::mutex& registryMutex() {
      static std::mutex mutex;
      return mutex;
    }

    std::unordered_map<int, std::unique_ptr<const SharedObjectClassSpec>>& registryMap() {
      static std::unordered_map<int, std::unique_ptr<const SharedObjectClassSpec>> map;
      return map;
    }

    struct ClassHandle {
      kolibri::GlobalRef<kolibri::JClass> javaClass;
      std::string jniDescriptor;
    };

    std::unordered_map<int, ClassHandle>& classHandles() {
      static auto* map = new std::unordered_map<int, ClassHandle>();
      return *map;
    }

    constexpr std::string kNoDescriptor;

    std::shared_ptr<descriptor::HostFunctionSpec> share(
      descriptor::HostFunctionSpec spec,
      const jclass declaredClass
    ) {
      auto shared = std::make_shared<descriptor::HostFunctionSpec>(std::move(spec));
      try {
        shared->resolveFunction(kolibri::getEnv(), declaredClass);
        shared->declaringClass = declaredClass;
      } catch (const std::exception&) {
        // Left unresolved - the call site raises the error with the context JavaScript needs.
      }
      return shared;
    }

    const ClassHandle* handleFor(const int classId) {
      if (const auto it = classHandles().find(classId); it != classHandles().end()) {
        return &it->second;
      }

      JNIEnv* env = kolibri::getEnv();
      const kolibri::Ref<kolibri::JClass> sharedClass =
        JSharedObjectRegistry::sharedClassOf(env, classId);
      if (sharedClass == nullptr) {
        return nullptr;
      }

      std::optional<std::string> jniDescriptor =
        JSharedObjectRegistry::classDescriptorOf(env, classId);
      if (!jniDescriptor.has_value()) {
        return nullptr;
      }

      return &classHandles().emplace(
        classId,
        ClassHandle{
          .javaClass = kolibri::GlobalRef<kolibri::JClass>::make(env, sharedClass.get()),
          .jniDescriptor = std::move(*jniDescriptor),
        }
      ).first->second;
    }
  } // namespace

  namespace {
    thread_local std::vector<jclass> tClassMemo;

    jclass memoized(const int classId) noexcept {
      const auto index = static_cast<size_t>(classId);
      return index < tClassMemo.size() ? tClassMemo[index] : nullptr;
    }

    void memoize(const int classId, jclass javaClass) {
      const auto index = static_cast<size_t>(classId);
      if (index >= tClassMemo.size()) {
        tClassMemo.resize(index + 1, nullptr);
      }
      tClassMemo[index] = javaClass;
    }
  } // namespace

  const SharedObjectClassSpec& SharedObjectClassRegistry::get(const int classId) {
    const std::lock_guard lock(registryMutex());
    if (const auto it = registryMap().find(classId); it != registryMap().end()) {
      return *it->second;
    }

    std::optional<JSharedObjectRegistry::ClassExports> exports =
      JSharedObjectRegistry::encodeClass(kolibri::getEnv(), classId);
    if (!exports.has_value()) {
      throw std::invalid_argument(
        "No shared object class is described for id " + std::to_string(classId)
      );
    }

    const ClassHandle* handle = handleFor(classId);
    if (handle == nullptr) {
      throw std::invalid_argument(
        "No shared object class is registered for id " + std::to_string(classId)
      );
    }

    const auto declaredClass = reinterpret_cast<jclass>(handle->javaClass.get());

    auto spec = std::make_unique<SharedObjectClassSpec>();
    spec->classId = classId;
    spec->name = std::move(exports->jsName);

    spec->functions.reserve(exports->descriptor.functions.size());
    for (descriptor::HostFunctionSpec& function: exports->descriptor.functions) {
      spec->functions.push_back(share(std::move(function), declaredClass));
    }

    spec->properties.reserve(exports->descriptor.properties.size());
    for (descriptor::HostPropertySpec& property: exports->descriptor.properties) {
      spec->properties.push_back(
        SharedObjectClassSpec::Property{
          .name = std::move(property.name),
          .getter = share(std::move(property.getter), declaredClass),
          .setter = property.setter.has_value()
                      ? share(std::move(*property.setter), declaredClass)
                      : nullptr,
        }
      );
    }

    spec->events = std::move(exports->descriptor.events);

    spec->validateExportNames();

    const auto entry = registryMap().emplace(classId, std::move(spec)).first;
    return *entry->second;
  }

  std::string SharedObjectClassRegistry::nameOf(const int classId) noexcept {
    try {
      return get(classId).name;
    } catch (const std::exception&) {
      // No export table yet, which is the common case in exactly the message this feeds: a
      // parameter naming a class no instance of which has ever crossed. The class itself is still
      // known, so name it from its descriptor rather than by id.
      try {
        const std::string& jniDescriptor = descriptorOf(classId);
        // "Lcom/example/TextRef;" -> "TextRef"
        const size_t lastSlash = jniDescriptor.rfind('/');
        if (lastSlash != std::string::npos && jniDescriptor.size() > lastSlash + 2) {
          return jniDescriptor.substr(lastSlash + 1, jniDescriptor.size() - lastSlash - 2);
        }
      } catch (const std::exception&) {
        // Fall through to the id.
      }
      return "shared object class #" + std::to_string(classId);
    }
  }

  jclass SharedObjectClassRegistry::javaClassOf(const int classId) {
    if (classId <= 0) {
      return nullptr;
    }
    if (jclass memo = memoized(classId); memo != nullptr) {
      return memo;
    }

    jclass resolved = nullptr;
    {
      const std::lock_guard lock(registryMutex());
      const ClassHandle* handle = handleFor(classId);
      resolved = handle == nullptr ? nullptr : reinterpret_cast<jclass>(handle->javaClass.get());
    }

    if (resolved != nullptr) {
      memoize(classId, resolved);
    }
    return resolved;
  }

  const std::string& SharedObjectClassRegistry::descriptorOf(const int classId) {
    const std::lock_guard lock(registryMutex());
    const ClassHandle* handle = handleFor(classId);
    return handle == nullptr ? kNoDescriptor : handle->jniDescriptor;
  }
} // namespace expo::modules::v2::sharedobjects
