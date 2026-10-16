#pragma once

#include <atomic>
#include <memory>
#include <mutex>
#include <string>
#include <string_view>
#include <type_traits>
#include <vector>

#include <expo-modules-cpp/Defines.h>
#include <expo-modules-cpp/Module.h>
#include <expo-modules-cpp/Traits.h>

namespace expo::modules::v2::cpp {
  namespace detail {
    /** Unique per type, and per library that instantiates it. */
    template<typename T>
    const void* typeKey() {
      static const char kTag = 0;
      return &kTag;
    }

    /** How a statically registered module is made. */
    struct ModuleFactory {
      std::string name;
      std::shared_ptr<Module> (*create)();
      const void* typeKey;
    };

    /** Adds [factory] to the list every context serves. Called by `EXPO_MODULE` as the library loads. */
    EXPO_API void registerModuleFactory(ModuleFactory factory);

    /**
     * Takes the factory named [name] off the list. A context that already made the module keeps it.
     * For tests that register a module of their own and must leave no trace in the process.
     */
    EXPO_API void unregisterModuleFactory(std::string_view name);

    template<typename T>
    struct ModuleRegistrar {
      explicit ModuleRegistrar(const std::string_view name) {
        registerModuleFactory(ModuleFactory{
          .name = std::string(name.empty() ? typeName<T>() : name),
          .create = [] { return std::static_pointer_cast<Module>(std::make_shared<T>()); },
          .typeKey = typeKey<T>(),
        });
      }
    };
  } // namespace detail

  /**
   * The modules a set of runtimes share: Kotlin's `ExpoContext`. It owns the module instances, so
   * one instance serves every runtime attached to the context, and closing it destroys them.
   *
   * Modules registered with `EXPO_MODULE` are served by every context that includes them, and are
   * created the first time they are asked for. Others are added by hand.
   */
  class EXPO_API Context : public std::enable_shared_from_this<Context> {
  public:
    /** A context. [includeStaticModules] serves the modules registered with `EXPO_MODULE`. */
    [[nodiscard]] static std::shared_ptr<Context> create(bool includeStaticModules = true);

    ~Context();

    Context(const Context&) = delete;
    Context& operator=(const Context&) = delete;

    /** Constructs a [T] with [args] and serves it as [name]. */
    template<typename T, typename... Args>
    std::shared_ptr<T> emplace(std::string name, Args&&... args) {
      // Bound while it is constructed, whatever context a call this runs inside has.
      const detail::ConstructionScope scope(this);
      return add(std::move(name), std::make_shared<T>(std::forward<Args>(args)...));
    }

    /** Serves [module] as [name]. Throws if the name is taken. */
    template<typename T>
    std::shared_ptr<T> add(std::string name, std::shared_ptr<T> module) {
      static_assert(std::is_base_of_v<Module, T>, "Only a cpp::Module can be added to a context");
      addModule(std::move(name), module, detail::typeKey<T>());
      return module;
    }

    /**
     * Constructs a [T] bound to this context at once, for a shared object made off the JS thread:
     * one made by a call from JavaScript is bound already, and any other when it is first handed over.
     */
    template<typename T, typename... Args>
    std::shared_ptr<T> make(Args&&... args) {
      static_assert(std::is_base_of_v<NativeObject, T>, "Only a native object belongs to a context");
      const detail::ConstructionScope scope(this);
      return std::make_shared<T>(std::forward<Args>(args)...);
    }

    /** The module of exactly type [T], created if it is registered and not yet made; null if there is none. */
    template<typename T>
    [[nodiscard]] std::shared_ptr<T> module() {
      return std::static_pointer_cast<T>(moduleOfType(detail::typeKey<T>()));
    }

    /** The module served as [name], created if it is registered and not yet made; null if there is none. */
    [[nodiscard]] std::shared_ptr<Module> module(std::string_view name);

    /** The names of every module this context serves, made or not. */
    [[nodiscard]] std::vector<std::string> moduleNames() const;

    /**
     * Destroys the modules: each one's `onDestroy` runs once, in the order they joined, while the
     * context still reads as open. A second call does nothing. Runtimes attached to the context
     * keep working with the module objects they hold, but nothing new is created.
     */
    void close();

    [[nodiscard]] bool isClosed() const noexcept;

  private:
    explicit Context(bool includeStaticModules);

    struct Entry {
      std::string name;
      std::shared_ptr<Module> instance;
      const void* typeKey;
    };

    void addModule(std::string name, const std::shared_ptr<Module>& module, const void* typeKey);

    std::shared_ptr<Module> moduleOfType(const void* typeKey);

    /** Makes the module [factory] describes and joins it. Called with the lock held. */
    std::shared_ptr<Module> instantiate(const detail::ModuleFactory& factory);

    const bool includeStaticModules_;
    mutable std::recursive_mutex mutex_;
    std::vector<Entry> entries_;
    /** Names whose module is being made: a module that asks for itself while it defines itself is a cycle. */
    std::vector<std::string> instantiating_;
    bool closing_ = false;
    std::atomic<bool> closed_{false};
  };
} // namespace expo::modules::v2::cpp

#define EXPO_DETAIL_CONCAT_(a, b) a##b
#define EXPO_DETAIL_CONCAT(a, b) EXPO_DETAIL_CONCAT_(a, b)
#define EXPO_DETAIL_MODULE_PICK(_1, _2, NAME, ...) NAME
#define EXPO_DETAIL_MODULE_1(Type) \
  static const ::expo::modules::v2::cpp::detail::ModuleRegistrar<Type> EXPO_DETAIL_CONCAT(expoModule, __LINE__){{}};
#define EXPO_DETAIL_MODULE_2(Type, Name) \
  static const ::expo::modules::v2::cpp::detail::ModuleRegistrar<Type> EXPO_DETAIL_CONCAT(expoModule, __LINE__){Name};

/**
 * Registers the module class [Type] with every `cpp::Context` that serves static modules, as
 * `expo.modules.<Type>`. A second argument names it otherwise: `EXPO_MODULE(MathModule, "Math")`.
 *
 * Use it once, at namespace scope in a source file. The registration runs as the library loads, so
 * a static library that holds only this has to be linked whole (or as an object library), or the
 * linker drops the file.
 */
#define EXPO_MODULE(...) \
  EXPO_DETAIL_MODULE_PICK(__VA_ARGS__, EXPO_DETAIL_MODULE_2, EXPO_DETAIL_MODULE_1)(__VA_ARGS__)
