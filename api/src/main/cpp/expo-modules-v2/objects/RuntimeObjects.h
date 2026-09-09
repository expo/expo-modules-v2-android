#pragma once

#include <jsi/jsi.h>

#include <optional>
#include <unordered_map>

#include <expo-modules-v2/events/ListenerTable.h>
#include <expo-modules-v2/objects/ObjectId.h>

namespace expo::modules::v2::objects {
  /**
   * One runtime's table of JavaScript objects that stand for Kotlin instances, keyed by
   * `ObjectId`. This is the per-runtime half of the mapping: the `WeakObject`s here belong to this
   * runtime and die with it, on its thread. The runtime-agnostic half - the state shared by every
   * runtime's object for one instance - is `ObjectState` on the object itself, and for shared
   * objects the process-wide state table in `SharedObjects`.
   *
   * Entries are weak: whoever needs the object alive (a module host object, a JS variable) holds
   * the strong reference.
   *
   * Also owns the per-class shared-object prototypes, which are per runtime for the same reason;
   * the one `EventEmitter` prototype they and every module object inherit from; and the event
   * listeners JavaScript added on those objects, which are `jsi::Value`s and so belong here rather
   * than on the objects' native state.
   *
   * Owned by `JavaScriptRuntime`; `find` reaches it from any `jsi::Runtime&`.
   */
  class RuntimeObjects {
  public:
    explicit RuntimeObjects(facebook::jsi::Runtime& rt);

    ~RuntimeObjects();

    RuntimeObjects(const RuntimeObjects&) = delete;
    RuntimeObjects& operator=(const RuntimeObjects&) = delete;

    [[nodiscard]] static RuntimeObjects* find(const facebook::jsi::Runtime& rt) noexcept;

    /** The live JavaScript object for [objectId] in this runtime, or undefined. */
    [[nodiscard]] facebook::jsi::Value lookup(facebook::jsi::Runtime& rt, ObjectId::Value objectId);

    void store(
      facebook::jsi::Runtime& rt,
      ObjectId::Value objectId,
      const facebook::jsi::Object& object
    );

    [[nodiscard]] const facebook::jsi::Object& prototypeFor(
      facebook::jsi::Runtime& rt,
      int classId,
      bool installMembers
    );

    /**
     * The one object carrying `addListener` and its siblings in this runtime. Every module object
     * has it as its prototype, and every shared-object class prototype inherits from it, so a
     * member is one function per runtime, whatever it was called on.
     */
    [[nodiscard]] const facebook::jsi::Object& eventEmitterPrototype(facebook::jsi::Runtime& rt);

    void sweep(facebook::jsi::Runtime& rt);

    [[nodiscard]] events::ListenerTable& listeners() { return listeners_; }

    /**
     * Drops every listener of [objectId] - its JavaScript object is gone - and tells Kotlin that
     * this runtime stopped observing each event that still had one.
     */
    void dropListeners(facebook::jsi::Runtime& rt, ObjectId::Value objectId);

    /**
     * Drops every listener this runtime holds, telling Kotlin about each event that loses its
     * observer here. Called by the runtime's teardown while the Kotlin context is still reachable;
     * the destructor only frees what is left.
     */
    void dropAllListeners(facebook::jsi::Runtime& rt);

  private:
    struct Prototype {
      facebook::jsi::Object object;
      bool installed = false;
    };

    facebook::jsi::Runtime* runtime_;
    std::unordered_map<ObjectId::Value, facebook::jsi::WeakObject> entries_;
    std::unordered_map<int, Prototype> prototypes_;
    std::optional<facebook::jsi::Object> eventEmitterPrototype_;
    events::ListenerTable listeners_;
    size_t sweepThreshold_ = kMinSweepThreshold;

    static constexpr size_t kMinSweepThreshold = 32;
  };
} // namespace expo::modules::v2::objects
