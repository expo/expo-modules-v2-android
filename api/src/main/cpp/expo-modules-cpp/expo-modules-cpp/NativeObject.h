#pragma once

#include <cstdint>
#include <memory>
#include <span>
#include <vector>

#include <expo-modules-cpp/Defines.h>

namespace expo::modules::v2::cpp {
  class Context;

  namespace detail {
    class EventBase;
    class RuntimeData;
    struct ClassSpec;

    /**
     * While one is alive on a thread, every native object constructed on that thread belongs to
     * [context] from its first line, so its constructor can already reach `context()`. A call that
     * returns a shared object opens one: that is how an object made by `new Counter()` or by an
     * export has a context in its constructor, as it does in Kotlin.
     */
    class EXPO_API ConstructionScope {
    public:
      explicit ConstructionScope(Context* context) noexcept;

      ~ConstructionScope();

      ConstructionScope(const ConstructionScope&) = delete;
      ConstructionScope& operator=(const ConstructionScope&) = delete;

    private:
      Context* previous_;
    };
  } // namespace detail

  /**
   * What a module and a shared object have in common - Kotlin's `ExpoObject`: an identity, the
   * context it belongs to, and the events it can emit.
   *
   * An instance is always owned by a `std::shared_ptr`: the context owns a module, and JavaScript
   * and native code share a shared object. Create one with `std::make_shared`.
   */
  class EXPO_API NativeObject : public std::enable_shared_from_this<NativeObject> {
  public:
    /** Unique in the process for as long as it lives, and never reused. */
    using Id = uint64_t;

    virtual ~NativeObject();

    NativeObject(const NativeObject&) = delete;
    NativeObject& operator=(const NativeObject&) = delete;

    [[nodiscard]] Id objectId() const noexcept { return id_; }

    /** The context this object belongs to, or null when it has none yet or that context is closed. */
    [[nodiscard]] std::shared_ptr<Context> contextOrNull() const;

    /**
     * The context this object belongs to. Throws when it has none yet or that context is closed.
     * A module is bound when it joins a context; a shared object when it is created in a call from
     * JavaScript or first handed to it.
     */
    [[nodiscard]] std::shared_ptr<Context> context() const;

    /** The events this object declares, in the order they were constructed. */
    [[nodiscard]] std::span<detail::EventBase* const> events() const noexcept { return events_; }

  protected:
    NativeObject();

  private:
    friend class Context;
    friend class detail::EventBase;
    friend class detail::RuntimeData;

    /**
     * Binds this object to [context]. Binding to the same context again does nothing, and a closed
     * one gives way to the new one; another open context is an error, because one instance can serve
     * several runtimes only when they share one context.
     */
    void bindContext(const std::shared_ptr<Context>& context);

    const Id id_;
    /** Both guarded by the bind mutex. `bound_` stays set after the context is gone. */
    std::weak_ptr<Context> context_;
    bool bound_ = false;
    std::vector<detail::EventBase*> events_;
  };
} // namespace expo::modules::v2::cpp
