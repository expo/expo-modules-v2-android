#include <expo-modules-cpp/Context.h>

#include <algorithm>
#include <stdexcept>

#include <expo-modules-cpp/internal/Log.h>

namespace expo::modules::v2::cpp {
  namespace {
    std::mutex& factoriesMutex() {
      static std::mutex& mutex = *new std::mutex();
      return mutex;
    }

    /** Every module registered with `EXPO_MODULE`, in the order the libraries loaded. */
    std::vector<detail::ModuleFactory>& factories() {
      static std::vector<detail::ModuleFactory>& list = *new std::vector<detail::ModuleFactory>();
      return list;
    }

    std::vector<detail::ModuleFactory> factorySnapshot() {
      const std::lock_guard lock(factoriesMutex());
      return factories();
    }
  } // namespace

  void detail::registerModuleFactory(ModuleFactory factory) {
    const std::lock_guard lock(factoriesMutex());
    auto& list = factories();
    const bool taken = std::ranges::any_of(list, [&](const ModuleFactory& each) { return each.name == factory.name; });
    if (taken) {
      // Static initialization has nowhere to throw to, and the first registration is the one any
      // earlier lookup has already seen.
      logError("Module '" + factory.name + "' is registered twice; the second registration is ignored");
      return;
    }
    list.push_back(std::move(factory));
  }

  void detail::unregisterModuleFactory(const std::string_view name) {
    const std::lock_guard lock(factoriesMutex());
    std::erase_if(factories(), [&](const ModuleFactory& each) { return each.name == name; });
  }

  std::shared_ptr<Context> Context::create(const bool includeStaticModules) {
    return std::shared_ptr<Context>(new Context(includeStaticModules));
  }

  Context::Context(const bool includeStaticModules) : includeStaticModules_(includeStaticModules) {
  }

  Context::~Context() = default;

  bool Context::isClosed() const noexcept {
    return closed_.load(std::memory_order_acquire);
  }

  std::shared_ptr<Module> Context::module(const std::string_view name) {
    const std::lock_guard lock(mutex_);
    if (isClosed()) {
      return nullptr;
    }

    for (const Entry& entry: entries_) {
      if (entry.name == name) {
        return entry.instance;
      }
    }
    // The modules there are can still be reached while closing - their onDestroy may need a peer -
    // but no new one starts: it would be dropped with the rest without ever being destroyed.
    if (includeStaticModules_ && !closing_) {
      for (const detail::ModuleFactory& factory: factorySnapshot()) {
        if (factory.name == name) {
          return instantiate(factory);
        }
      }
    }
    return nullptr;
  }

  std::shared_ptr<Module> Context::moduleOfType(const void* const typeKey) {
    const std::lock_guard lock(mutex_);
    if (isClosed()) {
      return nullptr;
    }

    for (const Entry& entry: entries_) {
      if (entry.typeKey == typeKey) {
        return entry.instance;
      }
    }
    // The modules there are can still be reached while closing - their onDestroy may need a peer -
    // but no new one starts: it would be dropped with the rest without ever being destroyed.
    if (includeStaticModules_ && !closing_) {
      for (const detail::ModuleFactory& factory: factorySnapshot()) {
        if (factory.typeKey == typeKey) {
          return instantiate(factory);
        }
      }
    }
    return nullptr;
  }

  std::vector<std::string> Context::moduleNames() const {
    const std::lock_guard lock(mutex_);
    std::vector<std::string> names;
    for (const Entry& entry: entries_) {
      names.push_back(entry.name);
    }
    if (includeStaticModules_ && !isClosed()) {
      for (const detail::ModuleFactory& factory: factorySnapshot()) {
        if (std::ranges::find(names, factory.name) == names.end()) {
          names.push_back(factory.name);
        }
      }
    }
    return names;
  }

  void Context::addModule(std::string name, const std::shared_ptr<Module>& module, const void* const typeKey) {
    const std::lock_guard lock(mutex_);
    if (isClosed() || closing_) {
      throw std::logic_error("Cannot add module '" + name + "': the context is closed");
    }
    const bool taken = std::ranges::any_of(entries_, [&](const Entry& entry) { return entry.name == name; });
    if (taken) {
      throw std::invalid_argument("Module '" + name + "' is already registered");
    }

    module->bindContext(shared_from_this());
    module->prepare(name);
    entries_.push_back(Entry{.name = std::move(name), .instance = module, .typeKey = typeKey});
  }

  std::shared_ptr<Module> Context::instantiate(const detail::ModuleFactory& factory) {
    if (std::ranges::find(instantiating_, factory.name) != instantiating_.end()) {
      throw std::logic_error(
        "Module '" + factory.name + "' asked for itself while it was being defined"
      );
    }
    instantiating_.push_back(factory.name);
    struct Pop {
      std::vector<std::string>& names;

      ~Pop() { names.pop_back(); }
    } pop{instantiating_};

    std::shared_ptr<Module> module = [&] {
      const detail::ConstructionScope scope(this);
      return factory.create();
    }();
    module->bindContext(shared_from_this());
    module->prepare(factory.name);
    entries_.push_back(Entry{.name = factory.name, .instance = module, .typeKey = factory.typeKey});
    return module;
  }

  void Context::close() {
    std::vector<Entry> modules;
    {
      const std::lock_guard lock(mutex_);
      if (closing_) {
        return;
      }
      closing_ = true;
      modules = entries_;
    }

    for (const Entry& entry: modules) {
      try {
        entry.instance->onDestroy();
      } catch (const std::exception& error) {
        detail::logError("onDestroy of module '" + entry.name + "' failed: " + error.what());
      } catch (...) {
        detail::logError("onDestroy of module '" + entry.name + "' failed");
      }
    }

    {
      const std::lock_guard lock(mutex_);
      entries_.clear();
    }
    closed_.store(true, std::memory_order_release);
  }
} // namespace expo::modules::v2::cpp
