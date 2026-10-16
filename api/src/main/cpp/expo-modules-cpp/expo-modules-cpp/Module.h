#pragma once

#include <string>

#include <expo-modules-cpp/Defines.h>
#include <expo-modules-cpp/NativeObject.h>
#include <expo-modules-cpp/internal/Specs.h>

namespace expo::modules::v2::cpp {
  class ModuleDefinition;

  /**
   * A native module: what `expo.modules.<Name>` is in JavaScript. Derive from it, describe the
   * exports in [define], and register the class with `EXPO_MODULE`:
   *
   * ```cpp
   * class MathModule : public cpp::Module {
   * public:
   *   void define(cpp::ModuleDefinition& def) override {
   *     def.function("add", [](double a, double b) { return a + b; });
   *   }
   * };
   *
   * EXPO_MODULE(MathModule)
   * ```
   *
   * The context creates the instance the first time JavaScript or native code asks for it, and
   * keeps it until the context is closed. Events are `cpp::Event` members.
   */
  class EXPO_API Module : public NativeObject {
  public:
    ~Module() override;

    /**
     * Declares what JavaScript sees. Called once, when the module joins its context, on the thread
     * that asked for it. The module is already bound to the context, so `context()` works here.
     */
    virtual void define(ModuleDefinition& definition);

    /**
     * Called once when the context closes, while it still reads as open and the other modules are
     * reachable. A throw is logged and does not stop the other modules from being destroyed.
     */
    virtual void onDestroy() {}

  protected:
    Module() = default;

  private:
    friend class Context;
    friend class detail::RuntimeData;

    /** Runs [define] and keeps what it declared. [name] is the name the module is served under. */
    void prepare(const std::string& name);

    [[nodiscard]] const detail::ModuleSpec& spec() const noexcept { return spec_; }

    detail::ModuleSpec spec_;
  };
} // namespace expo::modules::v2::cpp
