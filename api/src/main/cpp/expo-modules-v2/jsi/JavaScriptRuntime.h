#pragma once

#include <jni.h>
#include <jsi/jsi.h>
#include <memory>
#include <optional>
#include <string_view>

#include <expo-modules-v2/async/AsyncRuntimeState.h>
#include <expo-modules-v2/jsi/JavaScriptObject.h>
#include <kolibri/native_method.h>

namespace expo::modules::v2::jsi {
  class JavaScriptValue;

  class JavaScriptRuntime : public kolibri::NativeObject<JavaScriptRuntime> {
  public:
    static constexpr std::string_view descriptor = "io/github/expo/modules/v2/jsi/JavaScriptRuntime";

    static void registerNatives(JNIEnv* env);

    [[nodiscard]] facebook::jsi::Runtime& runtime() const;

    /**
     * Runs the engine's microtask queue until it is empty, so `.then` callbacks fire.
     *
     * Nothing drains it implicitly - `evaluate` deliberately does not, because draining
     * re-enters JavaScript at a point no caller expects. Returns whether the queue is now
     * empty.
     */
    jboolean drainMicrotasks() const;

    /** The promise table for this runtime, or null if async support failed to attach. */
    [[nodiscard]] async::AsyncRuntimeState* asyncState() {
      return asyncState_.has_value() ? &*asyncState_ : nullptr;
    }

    ~JavaScriptRuntime() override;

  protected:
    /**
     * Owning: this runtime creates and destroys the engine's `jsi::Runtime` (the standalone
     * desktop shape, where `:hermes` mints the VM).
     */
    JavaScriptRuntime(
      JNIEnv* env,
      jobject registry,
      jobject asyncContext,
      std::unique_ptr<facebook::jsi::Runtime> runtime,
      std::string_view engineName,
      std::string_view globalName = "expo"
    );

    /**
     * Non-owning: the host already owns the `jsi::Runtime` and outlives this object (React
     * Native, which hands out its runtime through `ReactContext.javaScriptContextHolder`).
     *
     * [globalName] is what the module host object is installed under. A host whose runtime
     * already carries another `expo` global must pass a different name, or it would replace it.
     */
    JavaScriptRuntime(
      JNIEnv* env,
      jobject registry,
      jobject asyncContext,
      facebook::jsi::Runtime& runtime,
      std::string_view engineName,
      std::string_view globalName
    );

  private:
    jobject evaluate(JNIEnv* env, jstring script, jstring sourceURL) const;

    jobject createObject(JNIEnv* env) const;

    jobject getGlobal(JNIEnv* env) const;

    void installExpoModulesHostObject(
      JNIEnv* env,
      jobject registry,
      std::string_view engineName,
      std::string_view globalName
    );

    /**
     * Set only when this object created the runtime. Declared before `runtime_` so the raw
     * pointer is never left dangling while the members below are still being destroyed.
     */
    std::unique_ptr<facebook::jsi::Runtime> ownedRuntime_;

    /** The runtime every method works through, owned or borrowed. Never null. */
    facebook::jsi::Runtime* runtime_;

    /**
     * Declared after `runtime_`/`ownedRuntime_` so it is destroyed BEFORE them: its destructor
     * drops the pending `jsi::Function`s, which need the runtime still alive.
     */
    std::optional<async::AsyncRuntimeState> asyncState_;
  };
} // namespace expo::modules::v2::jsi
