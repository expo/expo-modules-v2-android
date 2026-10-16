#include <expo-modules-cpp/NativeObject.h>

#include <atomic>
#include <mutex>
#include <stdexcept>
#include <string>

#include <expo-modules-cpp/Context.h>
#include <expo-modules-cpp/Traits.h>

namespace expo::modules::v2::cpp {
  namespace {
    std::atomic<NativeObject::Id>& nextId() {
      static std::atomic<NativeObject::Id> value{1};
      return value;
    }

    /** The context objects constructed on this thread are bound to, while a `ConstructionScope` lives. */
    thread_local Context* tConstructing = nullptr;

    /** Guards every object's context ref. Binding is rare and reading it is rarer. */
    std::mutex& bindMutex() {
      static std::mutex& mutex = *new std::mutex();
      return mutex;
    }
  } // namespace

  detail::ConstructionScope::ConstructionScope(Context* const context) noexcept : previous_(tConstructing) {
    tConstructing = context;
  }

  detail::ConstructionScope::~ConstructionScope() {
    tConstructing = previous_;
  }

  NativeObject::NativeObject() : id_(nextId().fetch_add(1, std::memory_order_relaxed)) {
    if (tConstructing != nullptr) {
      // No lock: nobody else can see the object yet.
      context_ = tConstructing->weak_from_this();
      bound_ = true;
    }
  }

  NativeObject::~NativeObject() = default;

  std::shared_ptr<Context> NativeObject::contextOrNull() const {
    std::shared_ptr<Context> context;
    {
      const std::lock_guard lock(bindMutex());
      context = context_.lock();
    }
    return context != nullptr && !context->isClosed() ? context : nullptr;
  }

  std::shared_ptr<Context> NativeObject::context() const {
    std::shared_ptr<Context> context;
    bool bound;
    {
      const std::lock_guard lock(bindMutex());
      bound = bound_;
      context = context_.lock();
    }
    if (!bound) {
      throw std::logic_error(
        "This object is not bound to a cpp::Context yet. A module is bound when it joins a "
        "context; a shared object when it is created by a call from JavaScript or first handed "
        "to it"
      );
    }
    if (context == nullptr || context->isClosed()) {
      throw std::logic_error("The cpp::Context of this object is closed");
    }
    return context;
  }

  void NativeObject::bindContext(const std::shared_ptr<Context>& context) {
    const std::lock_guard lock(bindMutex());
    const std::shared_ptr<Context> bound = context_.lock();
    if (bound == context) {
      return;
    }
    if (bound != nullptr && !bound->isClosed()) {
      throw std::logic_error(
        "This object already belongs to another cpp::Context. One instance can serve several "
        "runtimes only when they share one context"
      );
    }
    context_ = context;
    bound_ = true;
  }
} // namespace expo::modules::v2::cpp
