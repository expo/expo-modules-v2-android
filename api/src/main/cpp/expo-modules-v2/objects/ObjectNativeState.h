#pragma once

#include <jni.h>
#include <jsi/jsi.h>

#include <memory>
#include <span>
#include <string_view>
#include <utility>

#include <expo-jsi/ChainedNativeState.h>

#include <expo-modules-v2/descriptor/EventSpec.h>
#include <expo-modules-v2/objects/ObjectState.h>

namespace expo::modules::v2::objects {
  /**
   * The chain node every JavaScript object standing for a Kotlin instance carries: a module object
   * or a shared-object facade. It points at the instance's one `ObjectState`, and the derived node
   * owns whatever is specific to this JavaScript object, such as the binders of one export table.
   *
   * Invariant: a node holds nothing that belongs to one runtime - no `jsi::Value`, `Object`,
   * `WeakObject` or `Runtime*`. Whatever rides in an object's native-state slot rides along when a
   * worklet runtime copies that object, so it must make sense in any runtime. Per-runtime data,
   * such as the JavaScript object for an instance, lives in `RuntimeObjects`.
   *
   * All derived nodes share this tag, so `of` finds either kind; [as] narrows by `Kind`, since
   * this build has no RTTI.
   */
  class ObjectNativeState : public ::expo::jsi::ChainedNativeStateOf<ObjectNativeState> {
  public:
    using Kind = ObjectState::Kind;

    [[nodiscard]] const std::shared_ptr<ObjectState>& state() const { return state_; }

    [[nodiscard]] Kind kind() const { return state_->kind(); }

    [[nodiscard]] ObjectId::Value objectId() const { return state_->objectId(); }

    /** The Kotlin instance, or null once it was released. */
    [[nodiscard]] jobject instance() const { return state_->instance(); }

    /** The events this JavaScript object can emit; the emitter members check names against it. */
    [[nodiscard]] virtual std::span<const descriptor::EventSpec> events() const { return {}; }

    /** The declared event named [name], or null. */
    [[nodiscard]] const descriptor::EventSpec* eventSpec(const std::string_view name) const {
      for (const descriptor::EventSpec& spec: events()) {
        if (spec.name == name) {
          return &spec;
        }
      }
      return nullptr;
    }

    /** The node on [object], or null if it stands for no Kotlin instance. */
    static std::shared_ptr<ObjectNativeState> of(
      facebook::jsi::Runtime& rt,
      const facebook::jsi::Object& object
    ) {
      return ::expo::jsi::ChainedNativeState::find<ObjectNativeState>(rt, object);
    }

    /** [node] as the concrete `T`, or null if it is not of `T::kKind`. */
    template<typename T>
    static std::shared_ptr<T> as(const std::shared_ptr<ObjectNativeState>& node) {
      return node != nullptr && node->kind() == T::kKind ? std::static_pointer_cast<T>(node) : nullptr;
    }

  protected:
    explicit ObjectNativeState(std::shared_ptr<ObjectState> state) : state_(std::move(state)) {
    }

  private:
    std::shared_ptr<ObjectState> state_;
  };
} // namespace expo::modules::v2::objects
