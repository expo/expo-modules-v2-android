#pragma once

#include <jsi/jsi.h>

#include <unordered_map>

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
   * Also owns the per-class shared-object prototypes, which are per runtime for the same reason.
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

    void sweep(facebook::jsi::Runtime& rt);

  private:
    struct Prototype {
      facebook::jsi::Object object;
      bool installed = false;
    };

    facebook::jsi::Runtime* runtime_;
    std::unordered_map<ObjectId::Value, facebook::jsi::WeakObject> entries_;
    std::unordered_map<int, Prototype> prototypes_;
    size_t sweepThreshold_ = kMinSweepThreshold;

    static constexpr size_t kMinSweepThreshold = 32;
  };
} // namespace expo::modules::v2::objects
