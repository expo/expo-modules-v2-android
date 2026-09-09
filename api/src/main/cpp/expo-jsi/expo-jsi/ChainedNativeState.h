#pragma once

#include <jsi/jsi.h>
#include <memory>

namespace expo::jsi {
  /**
   * Chainable native state. A facebook::jsi::Object has exactly ONE NativeState slot, so independent pieces
   * of native data that want to ride on the same JS object must share it: a ChainedNativeState is
   * a singly-linked node in that slot. [attach] pushes a new node in front of whatever chain the
   * object already carries, and [find] walks the chain by concrete type — so e.g. a module object
   * can carry a ModuleNativeState today and gain further states later without either knowing
   * about the other.
   *
   * This build compiles Hermes (and jsi) with -fno-rtti, so the type lookup cannot use
   * dynamic_cast. Instead, every concrete state derives through [ChainedNativeStateOf], a CRTP
   * base that mints one process-unique tag per type; [find] compares tags and static-casts.
   *
   * Invariants (unverifiable without RTTI — keep them by construction):
   * - Every native state attached to a JS object in this codebase goes through [attach]. A
   *   foreign, non-chained NativeState in the slot would be indistinguishable from a chain head
   *   and mis-cast.
   * - A node belongs to at most one object's chain. [attach] rejects a node that already has a
   *   tail, but cannot see a tail-less node already installed on another object.
   * - A node holds nothing that belongs to one runtime (no jsi::Value/Object/WeakObject, no
   *   Runtime*). A worklet runtime that copies an object carries its native-state slot, and with it
   *   the whole chain, into another runtime. Per-runtime data lives beside the runtime instead.
   *
   * Attaching a second state of the same type shadows the earlier one for [find]: the chain is
   * front-pushed and the walk returns the first hit.
   */
  class ChainedNativeState : public facebook::jsi::NativeState {
  public:
    static void attach(
      facebook::jsi::Runtime& rt,
      const facebook::jsi::Object& object,
      std::shared_ptr<ChainedNativeState> state
    );

    template<typename T>
    static std::shared_ptr<T> find(facebook::jsi::Runtime& rt, const facebook::jsi::Object& object) {
      return std::static_pointer_cast<T>(find(rt, object, T::staticTag()));
    }

  protected:
    using StateTag = const void*;

    [[nodiscard]] virtual StateTag tag() const noexcept = 0;

  private:
    static std::shared_ptr<ChainedNativeState> find(
      facebook::jsi::Runtime& rt,
      const facebook::jsi::Object& object,
      StateTag tag
    );

    std::shared_ptr<ChainedNativeState> next_;
  };

  template<typename Derived>
  class ChainedNativeStateOf : public ChainedNativeState {
  public:
    [[nodiscard]] static StateTag staticTag() noexcept {
      static constexpr char tag = 0;
      return &tag;
    }

  protected:
    [[nodiscard]] StateTag tag() const noexcept final {
      return staticTag();
    }
  };
} // namespace expo::jsi
