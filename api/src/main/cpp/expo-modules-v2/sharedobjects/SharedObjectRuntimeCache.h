#pragma once

#include <jsi/jsi.h>

#include <memory>
#include <unordered_map>

namespace expo::modules::v2::sharedobjects {
  class SharedObjectState;

  class SharedObjectRuntimeCache {
  public:
    explicit SharedObjectRuntimeCache(facebook::jsi::Runtime& rt);

    ~SharedObjectRuntimeCache();

    SharedObjectRuntimeCache(const SharedObjectRuntimeCache&) = delete;
    SharedObjectRuntimeCache& operator=(const SharedObjectRuntimeCache&) = delete;

    [[nodiscard]] static SharedObjectRuntimeCache* find(const facebook::jsi::Runtime& rt) noexcept;

    [[nodiscard]] facebook::jsi::Value lookupFacade(facebook::jsi::Runtime& rt, int objectId);

    void storeFacade(
      facebook::jsi::Runtime& rt,
      int objectId,
      const facebook::jsi::Object& facade,
      const std::shared_ptr<SharedObjectState>& state
    );

    [[nodiscard]] const facebook::jsi::Object& prototypeFor(
      facebook::jsi::Runtime& rt,
      int classId,
      bool installMembers
    );

    void sweep(facebook::jsi::Runtime& rt);

  private:
    struct Entry {
      facebook::jsi::WeakObject facade;
      std::weak_ptr<SharedObjectState> state;
    };

    struct Prototype {
      facebook::jsi::Object object;
      bool installed = false;
    };

    facebook::jsi::Runtime* runtime_;
    std::unordered_map<int, Entry> entries_;
    std::unordered_map<int, Prototype> prototypes_;
    size_t sweepThreshold_ = kMinSweepThreshold;

    static constexpr size_t kMinSweepThreshold = 32;
  };
}

