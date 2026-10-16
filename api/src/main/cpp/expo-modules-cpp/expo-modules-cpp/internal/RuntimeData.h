#pragma once

#include <memory>
#include <optional>
#include <string>
#include <string_view>
#include <type_traits>
#include <unordered_map>
#include <utility>
#include <vector>

#include <expo-modules-cpp/Defines.h>
#include <expo-modules-cpp/NativeObject.h>
#include <expo-modules-cpp/internal/ListenerTable.h>
#include <expo-modules-cpp/internal/Log.h>
#include <expo-modules-cpp/internal/ObjectNode.h>
#include <expo-modules-cpp/internal/PromiseTable.h>
#include <expo-modules-cpp/internal/RuntimeLink.h>
#include <expo-modules-cpp/internal/Specs.h>

namespace expo::modules::v2::cpp {
  class Context;
  class Module;
  class SharedObject;
} // namespace expo::modules::v2::cpp

namespace expo::modules::v2::cpp::detail {
  /**
   * Everything the library keeps for one `js::Runtime`: Kotlin's `RuntimeObjects` and
   * `AsyncRuntimeState` together. It belongs to the JS thread and is torn down on it, while the
   * runtime is alive, because it holds `js::Value`s that need the runtime to be released.
   *
   * Owned by `AttachedRuntime`. An exported function, which receives only a `js::Runtime&`, reaches
   * it with [find].
   */
  class EXPO_API RuntimeData {
  public:
    RuntimeData(
      js::Runtime& rt,
      std::shared_ptr<Context> context,
      std::shared_ptr<Scheduler> scheduler,
      std::shared_ptr<RuntimeLink> link
    );

    ~RuntimeData();

    RuntimeData(const RuntimeData&) = delete;
    RuntimeData& operator=(const RuntimeData&) = delete;

    /**
     * The data of [rt], or null when no `AttachedRuntime` serves it. For the JS thread, which is
     * also the thread that destroys it; any other thread reaches the runtime with [findLink].
     */
    [[nodiscard]] static RuntimeData* find(const js::Runtime& rt) noexcept;

    /** [find], or a `std::logic_error` saying what was missing. */
    [[nodiscard]] static RuntimeData& require(const js::Runtime& rt);

    /**
     * The link of [rt], or null when no `AttachedRuntime` serves it. Unlike [find], whose answer a
     * thread other than the JS thread cannot use - the data dies on the JS thread at any moment -
     * the link is copied while the registry is locked, so it outlives the runtime being detached.
     * Safe from any thread.
     */
    [[nodiscard]] static std::shared_ptr<RuntimeLink> findLink(const js::Runtime& rt) noexcept;

    /** [findLink], or a `std::logic_error` saying what was missing. */
    [[nodiscard]] static std::shared_ptr<RuntimeLink> requireLink(const js::Runtime& rt);

    [[nodiscard]] js::Runtime& runtime() const noexcept { return *runtime_; }

    [[nodiscard]] const std::shared_ptr<Context>& context() const noexcept { return context_; }

    [[nodiscard]] const std::shared_ptr<RuntimeLink>& link() const noexcept { return link_; }

    [[nodiscard]] PromiseTable& promises() noexcept { return promises_; }

    [[nodiscard]] ListenerTable& listeners() noexcept { return listeners_; }

    // --- JavaScript objects of native objects -----------------------------------------------

    /** The live JavaScript object standing for [id] in this runtime, or `undefined`. */
    [[nodiscard]] js::Value lookup(NativeObject::Id id);

    void store(NativeObject::Id id, const js::Object& object);

    /**
     * The JavaScript object for a shared object: the one JavaScript already holds when it is still
     * alive, a new facade otherwise. [declared] is the class the caller converted it as; the
     * object's own class, once known, wins, so a base-typed result still reaches JavaScript as the
     * class it is.
     */
    [[nodiscard]] js::Value facadeFor(const std::shared_ptr<SharedObject>& object, const ClassSpec& declared);

    /** The `module` the context serves under [name], as a JavaScript object, or `undefined`. */
    [[nodiscard]] js::Value moduleObject(std::string_view name);

    /** The names the context serves. */
    [[nodiscard]] std::vector<std::string> moduleNames() const;

    /** The one object carrying `addListener` and its siblings; every module and prototype inherits it. */
    [[nodiscard]] const js::Object& emitterPrototype();

    /**
     * The prototype of [spec]'s facades in this runtime. A prototype is built once per runtime, with
     * its members installed the first time an instance is made.
     */
    [[nodiscard]] const js::Object& classPrototype(const ClassSpec& spec, bool installMembers);

    /** `Object.defineProperty`, fetched once. */
    [[nodiscard]] const js::Function& definePropertyFunction();

    /** Drops the entries whose JavaScript object was collected, and their listeners. */
    void sweep();

    /**
     * Delivers an event: calls the listeners [id] has for [eventIndex], with the arguments [make]
     * builds. Does nothing when there are none or the JavaScript object is gone. A failure is
     * logged, never thrown - the emitter's native code must not see a listener's or a conversion's
     * error.
     */
    template<typename MakeArgs>
    void emit(const NativeObject::Id id, const uint32_t eventIndex, MakeArgs&& make) {
      if (!listeners_.isObserved(id, eventIndex)) {
        return;
      }
      js::Value object = lookup(id);
      if (!object.isObject()) {
        return;
      }

      try {
        const auto args = make(*runtime_);
        listeners_.call(*runtime_, object.getObject(*runtime_), eventIndex, args.data(), args.size());
      } catch (const std::exception& error) {
        logError(std::string("Delivering an event failed: ") + error.what());
      }
    }

    /**
     * A per-runtime object of type [S], made on first use with `S(js::Runtime&)` or `S()`. Where
     * the library caches what is expensive to look up - a record's property names, say.
     */
    template<typename S>
    S& local() {
      // Its address is the key: unique to [S], and to the library that instantiated it, so two
      // libraries that both use a type never read each other's slot as theirs.
      static const char kTag = 0;
      for (auto& slot: locals_) {
        if (slot.first == &kTag) {
          return static_cast<Local<S>&>(*slot.second).value;
        }
      }
      auto created = [&] {
        if constexpr (std::is_constructible_v<S, js::Runtime&>) {
          return std::make_unique<Local<S>>(*runtime_);
        } else {
          return std::make_unique<Local<S>>();
        }
      }();
      S& value = created->value;
      locals_.emplace_back(&kTag, std::move(created));
      return value;
    }

  private:
    struct LocalBase {
      virtual ~LocalBase() = default;
    };

    template<typename S>
    struct Local final : LocalBase {
      S value;

      Local() : value() {
      }

      explicit Local(js::Runtime& rt) : value(rt) {
      }
    };

    struct Prototype {
      js::Object object;
      bool installed = false;
    };

    js::Object buildModuleObject(const std::shared_ptr<Module>& module);

    /** Drops the listeners of [id], whose JavaScript object is gone, and tells native code. */
    void dropListenersOf(NativeObject::Id id);

    js::Runtime* runtime_;
    std::shared_ptr<Context> context_;
    /** The one strong reference to the scheduler: the link every other thread holds is weak. */
    std::shared_ptr<Scheduler> scheduler_;
    std::shared_ptr<RuntimeLink> link_;

    PromiseTable promises_;
    ListenerTable listeners_;

    /** Weak: whoever needs an object alive - a module host, a JavaScript variable - holds it. */
    std::unordered_map<NativeObject::Id, js::WeakObject> objects_;
    size_t sweepThreshold_ = kMinSweepThreshold;

    /** The module objects built so far, by name. The strong reference that keeps them. */
    std::unordered_map<std::string, js::Object> modules_;

    std::unordered_map<const ClassSpec*, Prototype> prototypes_;
    std::optional<js::Object> emitterPrototype_;
    std::optional<js::Function> defineProperty_;
    std::vector<std::pair<const void*, std::unique_ptr<LocalBase>>> locals_;

    static constexpr size_t kMinSweepThreshold = 32;
  };
} // namespace expo::modules::v2::cpp::detail
