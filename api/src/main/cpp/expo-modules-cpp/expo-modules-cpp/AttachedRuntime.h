#pragma once

#include <functional>
#include <memory>
#include <string>
#include <string_view>
#include <vector>

#include <expo-modules-cpp/Context.h>
#include <expo-modules-cpp/Defines.h>
#include <expo-modules-cpp/NativeObject.h>
#include <expo-modules-cpp/Scheduler.h>

namespace expo::modules::v2::cpp {
  namespace detail {
    class RuntimeData;
  }

  struct AttachOptions {
    /** The global the modules are installed under: `globalThis.expo.modules`. */
    std::string globalName = "expo";

    /**
     * Whether to install `<globalName>.modules`. Turn it off when something else serves the modules
     * object - the JNI bridge does, and asks this runtime for the ones written in C++.
     */
    bool installGlobal = true;
  };

  /**
   * Runs [job] on the JS thread of [runtime]: at once when the caller already is there, posted to it
   * otherwise. For code that holds a `js::Runtime&` from an earlier call and has to get back to it
   * from another thread - an operating system callback with a stored `js::Function` to call, say.
   *
   * Safe from any thread: only the identity of [runtime] is used off the JS thread. A job does not
   * run, and nothing is reported, when the runtime has no attachment, or lost it before the job ran.
   */
  EXPO_API void runOnJSThread(js::Runtime& runtime, std::function<void(js::Runtime&)> job);

  /** Whether the calling thread is the JS thread of [runtime]. False for a runtime without an attachment. */
  [[nodiscard]] EXPO_API bool isOnJSThread(js::Runtime& runtime);

  /**
   * A `cpp::Context` served to one `js::Runtime`: Kotlin's `AttachedRuntime`. It installs
   * `expo.modules`, so that `expo.modules.Math` is the module the context serves as `Math`, and it
   * keeps everything the library holds for the runtime.
   *
   * ```cpp
   * auto scheduler = std::make_shared<cpp::ManualScheduler>();
   * auto attached = cpp::AttachedRuntime::attach(runtime, cpp::Context::create(), scheduler);
   * runtime.evaluateJavaScript(source, "app.js");   // calls expo.modules.Math.add(1, 2)
   * ```
   *
   * The `js::Runtime` is the host's: this borrows it and never frees it. Attach on the JS thread,
   * and destroy the attachment on the JS thread before the runtime goes away - the library's
   * `js::Value`s are released against a live runtime. A runtime can have one attachment.
   */
  class EXPO_API AttachedRuntime {
  public:
    [[nodiscard]] static std::unique_ptr<AttachedRuntime> attach(
      js::Runtime& runtime,
      std::shared_ptr<Context> context,
      std::shared_ptr<Scheduler> scheduler,
      const AttachOptions& options = {}
    );

    ~AttachedRuntime();

    AttachedRuntime(const AttachedRuntime&) = delete;
    AttachedRuntime& operator=(const AttachedRuntime&) = delete;

    /** The module the context serves as [name], as a JavaScript object, or `undefined`. JS thread only. */
    [[nodiscard]] js::Value module(std::string_view name);

    /** The names of the modules the context serves. */
    [[nodiscard]] std::vector<std::string> moduleNames() const;

    /**
     * A host object serving this runtime's modules by name, for a caller that installs `expo.modules`
     * itself and asks this runtime for the modules it has - the JNI bridge does, so that modules
     * written in C++ and in Kotlin share one `expo.modules`. A name it does not have is `undefined`.
     */
    [[nodiscard]] std::shared_ptr<js::HostObject> modulesHost() const;

    /** The JavaScript object standing for [object] in this runtime, or `undefined` if it has none yet. */
    [[nodiscard]] js::Value objectOf(const NativeObject& object);

    [[nodiscard]] Context& context() const;

    [[nodiscard]] js::Runtime& runtime() const;

  private:
    explicit AttachedRuntime(std::unique_ptr<detail::RuntimeData> data);

    std::unique_ptr<detail::RuntimeData> data_;
  };
} // namespace expo::modules::v2::cpp
