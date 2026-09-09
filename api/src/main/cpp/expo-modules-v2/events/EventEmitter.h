#pragma once

#include <jsi/jsi.h>

#include <string_view>

namespace expo::modules::v2::events {
  /**
   * Defines the `EventEmitter` members on [target], in the shape of expo-modules-core's:
   * `addListener`, `removeListener`, `removeAllListeners`, `listenerCount` and `emit`.
   * Non-enumerable and non-configurable, like `release`.
   *
   * Called once per runtime, on the prototype `RuntimeObjects::eventEmitterPrototype` hands out:
   * every module object has it as its prototype and every shared-object class prototype inherits
   * from it. Each member resolves `this` to its `ObjectNativeState`, checks the event name against
   * the events the object declares, and keeps the listeners in this runtime's `ListenerTable`. The
   * first listener of an event in a runtime, and the removal of its last one, are reported to
   * Kotlin through `EventSupport.observe`.
   */
  void installEmitterMethods(facebook::jsi::Runtime& rt, const facebook::jsi::Object& target);

  /** Whether [name] is one of the members [installEmitterMethods] defines. */
  [[nodiscard]] bool isEmitterMemberName(std::string_view name) noexcept;
} // namespace expo::modules::v2::events
