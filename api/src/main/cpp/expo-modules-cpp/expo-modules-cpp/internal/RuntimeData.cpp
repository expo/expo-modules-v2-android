#include <expo-modules-cpp/internal/RuntimeData.h>

#include <algorithm>
#include <atomic>
#include <mutex>
#include <stdexcept>

#include <expo-modules-cpp/Context.h>
#include <expo-modules-cpp/Module.h>
#include <expo-modules-cpp/Event.h>
#include <expo-modules-cpp/internal/Emitter.h>
#include <expo-modules-cpp/internal/Invoke.h>
#include <expo-modules-cpp/internal/Members.h>
#include <expo-modules-cpp/internal/SharedObjects.h>

namespace expo::modules::v2::cpp::detail {
  namespace {
    /** An exported function has only a `js::Runtime&`, so its data has to be reachable from one. */
    std::mutex& registryMutex() {
      static std::mutex& mutex = *new std::mutex();
      return mutex;
    }

    std::unordered_map<const js::Runtime*, RuntimeData*>& registry() {
      static auto& tables = *new std::unordered_map<const js::Runtime*, RuntimeData*>();
      return tables;
    }

    /** Bumped whenever the registry changes, so a thread's remembered answer knows it went stale. */
    std::atomic<uint64_t>& generation() {
      static std::atomic<uint64_t> value{0};
      return value;
    }

    struct Memo {
      const js::Runtime* runtime = nullptr;
      RuntimeData* data = nullptr;
      uint64_t generation = 0;
    };

    thread_local Memo tMemo;
  } // namespace

  RuntimeData::RuntimeData(
    js::Runtime& rt,
    std::shared_ptr<Context> context,
    std::shared_ptr<Scheduler> scheduler,
    std::shared_ptr<RuntimeLink> link
  ) : runtime_(&rt), context_(std::move(context)), scheduler_(std::move(scheduler)), link_(std::move(link)), listeners_(rt) {
    {
      const std::lock_guard lock(registryMutex());
      const bool attached = registry().contains(runtime_);
      if (attached) {
        throw std::logic_error("This js::Runtime already has an AttachedRuntime");
      }
      registry()[runtime_] = this;
      generation().fetch_add(1, std::memory_order_release);
    }
    link_->data_ = this;
  }

  RuntimeData::~RuntimeData() {
    // The listeners go first, while everything is still reachable: native code hears about each
    // event that loses its last observer here, and an event whose last observer was this runtime
    // fires its stop hook.
    listeners_.dropAll([this](NativeObject& owner, const uint32_t index) {
      owner.events()[index]->detach(link_.get());
    });

    // From here on a job that was already posted finds the runtime gone and does nothing.
    link_->data_ = nullptr;

    // Everything below holds `js::Value`s, which need the runtime to be released - and it is alive
    // for as long as this object is.
    promises_.clear();
    modules_.clear();
    objects_.clear();
    prototypes_.clear();
    emitterPrototype_.reset();
    defineProperty_.reset();
    while (!locals_.empty()) {
      locals_.pop_back();
    }

    {
      const std::lock_guard lock(registryMutex());
      registry().erase(runtime_);
      generation().fetch_add(1, std::memory_order_release);
    }
  }

  RuntimeData* RuntimeData::find(const js::Runtime& rt) noexcept {
    const uint64_t current = generation().load(std::memory_order_acquire);
    if (tMemo.runtime == &rt && tMemo.generation == current) {
      return tMemo.data;
    }

    RuntimeData* found = nullptr;
    {
      const std::lock_guard lock(registryMutex());
      const auto it = registry().find(&rt);
      found = it == registry().end() ? nullptr : it->second;
    }
    tMemo = Memo{.runtime = &rt, .data = found, .generation = current};
    return found;
  }

  RuntimeData& RuntimeData::require(const js::Runtime& rt) {
    RuntimeData* const data = find(rt);
    if (data == nullptr) [[unlikely]] {
      throw std::logic_error(
        "This js::Runtime has no cpp::AttachedRuntime: attach one before exporting anything from it"
      );
    }
    return *data;
  }

  std::shared_ptr<RuntimeLink> RuntimeData::findLink(const js::Runtime& rt) noexcept {
    // Under the lock the entry is alive: the destructor erases it before its members go.
    const std::lock_guard lock(registryMutex());
    const auto it = registry().find(&rt);
    return it == registry().end() ? nullptr : it->second->link_;
  }

  std::shared_ptr<RuntimeLink> RuntimeData::requireLink(const js::Runtime& rt) {
    std::shared_ptr<RuntimeLink> link = findLink(rt);
    if (link == nullptr) [[unlikely]] {
      throw std::logic_error(
        "This js::Runtime has no cpp::AttachedRuntime: attach one before exporting anything from it"
      );
    }
    return link;
  }

  js::Value RuntimeData::lookup(const NativeObject::Id id) {
    const auto found = objects_.find(id);
    if (found == objects_.end()) {
      return js::Value::undefined();
    }

    js::Value cached = found->second.lock(*runtime_);
    if (cached.isUndefined()) {
      objects_.erase(found);
      dropListenersOf(id);
    }
    return cached;
  }

  void RuntimeData::store(const NativeObject::Id id, const js::Object& object) {
    sweep();
    objects_.insert_or_assign(id, js::WeakObject(*runtime_, object));
  }

  void RuntimeData::sweep() {
    if (objects_.size() < sweepThreshold_) {
      return;
    }

    // Collected first, dropped after: dropping a listener tells native code, whose hook may make
    // another facade, and that would add to `objects_` in the middle of the walk.
    std::vector<NativeObject::Id> collected;
    for (auto it = objects_.begin(); it != objects_.end();) {
      if (it->second.lock(*runtime_).isUndefined()) {
        collected.push_back(it->first);
        it = objects_.erase(it);
      } else {
        ++it;
      }
    }
    sweepThreshold_ = std::max(kMinSweepThreshold, objects_.size() * 2);

    for (const NativeObject::Id id: collected) {
      dropListenersOf(id);
    }
  }

  void RuntimeData::dropListenersOf(const NativeObject::Id id) {
    if (!listeners_.has(id)) {
      return;
    }
    listeners_.drop(id, [this](NativeObject& owner, const uint32_t index) {
      owner.events()[index]->detach(link_.get());
    });
  }

  const js::Function& RuntimeData::definePropertyFunction() {
    if (!defineProperty_.has_value()) {
      defineProperty_.emplace(
        runtime_->global().getPropertyAsObject(*runtime_, "Object").getPropertyAsFunction(*runtime_, "defineProperty")
      );
    }
    return *defineProperty_;
  }

  const js::Object& RuntimeData::emitterPrototype() {
    if (!emitterPrototype_.has_value()) {
      js::Object prototype(*runtime_);
      installEmitterMembers(*this, prototype);
      emitterPrototype_.emplace(std::move(prototype));
    }
    return *emitterPrototype_;
  }

  std::vector<std::string> RuntimeData::moduleNames() const {
    return context_->moduleNames();
  }

  js::Value RuntimeData::moduleObject(const std::string_view name) {
    const std::string key(name);
    if (const auto cached = modules_.find(key); cached != modules_.end()) {
      return js::Value(*runtime_, cached->second);
    }

    const std::shared_ptr<Module> module = context_->module(name);
    if (module == nullptr) {
      return js::Value::undefined();
    }

    // Cached only once fully built: a failure while building retries from scratch next time.
    return js::Value(*runtime_, modules_.emplace(key, buildModuleObject(module)).first->second);
  }

  js::Object RuntimeData::buildModuleObject(const std::shared_ptr<Module>& module) {
    js::Runtime& rt = *runtime_;
    const ModuleSpec& spec = module->spec();

    js::Object object(rt);
    // Every module is an event emitter, whether or not it declares events: subscribing to an
    // unknown name then fails with a message that names the declared ones.
    object.setPrototype(rt, js::Value(rt, emitterPrototype()));

    for (const FunctionSpec& function: spec.functions) {
      object.setProperty(rt, js::PropNameID::forUtf8(rt, function.name), makeHostFunction(rt, function, module));
    }
    defineProperties(*this, object, spec.properties, spec.constants, module, /* hiddenConstants */ false);

    for (const ClassBinding& binding: spec.classes) {
      object.setProperty(rt, js::PropNameID::forUtf8(rt, binding.jsName), createClassConstructor(*this, binding));
    }

    ::expo::jsi::ChainedNativeState::attach(rt, object, std::make_shared<ObjectNode>(module, nullptr));
    // The strong reference is `modules_`; this weak entry lets native code find the object again.
    store(module->objectId(), object);
    return object;
  }
} // namespace expo::modules::v2::cpp::detail
