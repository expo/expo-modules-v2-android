#include <expo-modules-cpp/internal/ModuleHost.h>

#include <mutex>
#include <string>
#include <unordered_set>

#include <expo-modules-cpp/Errors.h>
#include <expo-modules-cpp/internal/RuntimeData.h>

namespace expo::modules::v2::cpp::detail {
  namespace {
    std::mutex& hostsMutex() {
      static std::mutex& mutex = *new std::mutex();
      return mutex;
    }

    std::unordered_set<const ModuleHost*>& hosts() {
      static auto& live = *new std::unordered_set<const ModuleHost*>();
      return live;
    }
  } // namespace

  ModuleHost::ModuleHost(std::shared_ptr<RuntimeLink> link, std::optional<js::Object> hostModules)
    : link_(std::move(link)), hostModules_(std::move(hostModules)) {
    const std::lock_guard lock(hostsMutex());
    hosts().insert(this);
  }

  ModuleHost::~ModuleHost() {
    const std::lock_guard lock(hostsMutex());
    hosts().erase(this);
  }

  std::shared_ptr<ModuleHost> ModuleHost::from(const std::shared_ptr<js::HostObject>& object) {
    if (object == nullptr) {
      return nullptr;
    }
    // [object] is held, so a host at this address is alive and is this one.
    const std::lock_guard lock(hostsMutex());
    return hosts().contains(static_cast<const ModuleHost*>(object.get())) ? std::static_pointer_cast<ModuleHost>(object) : nullptr;
  }

  js::Value ModuleHost::get(js::Runtime& rt, const js::PropNameID& name) {
    // Not cached here: the host caches its own modules, and a miss has to stay a miss for ours.
    if (hostModules_.has_value()) {
      js::Value hostModule = hostModules_->getProperty(rt, name);
      if (!hostModule.isUndefined()) {
        return hostModule;
      }
    }

    RuntimeData* const data = link_->data();
    if (data == nullptr) {
      throwJSError(rt, "The Expo modules of this runtime were detached");
    }

    const std::string moduleName = name.utf8(rt);
    try {
      return data->moduleObject(moduleName);
    } catch (const js::JSError&) {
      throw;
    } catch (const std::exception& error) {
      throwJSError(rt, "Failed to materialize module '" + moduleName + "': " + error.what());
    }
  }

  void ModuleHost::set(js::Runtime& rt, const js::PropNameID& name, const js::Value&) {
    throwJSError(rt, "expo.modules is read-only (attempted to set '" + name.utf8(rt) + "')");
  }

  std::vector<js::PropNameID> ModuleHost::getPropertyNames(js::Runtime& rt) {
    std::vector<js::PropNameID> output;
    // A name both sides have is listed once, the way `get` resolves it once.
    std::unordered_set<std::string> listed;
    const auto list = [&](std::string name) {
      if (listed.insert(name).second) {
        output.push_back(js::PropNameID::forUtf8(rt, name));
      }
    };

    if (hostModules_.has_value()) {
      const js::Array hostNames = hostModules_->getPropertyNames(rt);
      const size_t count = hostNames.size(rt);
      for (size_t i = 0; i < count; i++) {
        list(hostNames.getValueAtIndex(rt, i).toString(rt).utf8(rt));
      }
    }

    if (const RuntimeData* const data = link_->data()) {
      for (std::string& name: data->moduleNames()) {
        list(std::move(name));
      }
    }
    return output;
  }
} // namespace expo::modules::v2::cpp::detail
