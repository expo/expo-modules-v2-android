#pragma once

#include <chrono>
#include <memory>
#include <stdexcept>
#include <string>
#include <thread>

#include <hermes/hermes.h>
#include <jsi/instrumentation.h>

#include <expo-modules-cpp/Expo.h>

#include "TestHarness.h"

namespace js = facebook::jsi;
namespace cpp = expo::modules::v2::cpp;

/**
 * A Hermes runtime with a context attached to it, the way a host sets one up. The test thread is
 * the JS thread: the scheduler runs the jobs other threads post when [pump] drains it.
 */
class JsEnv {
public:
  /** [context] is shared with the caller, who closes it; without one the environment makes its own. */
  explicit JsEnv(std::shared_ptr<cpp::Context> context = nullptr)
    : runtime_(facebook::hermes::makeHermesRuntime()),
      scheduler_(std::make_shared<cpp::ManualScheduler>()),
      ownsContext_(context == nullptr),
      context_(context != nullptr ? std::move(context) : cpp::Context::create(/* includeStaticModules */ false)),
      attached_(cpp::AttachedRuntime::attach(*runtime_, context_, scheduler_)) {
  }

  ~JsEnv() {
    attached_.reset();
    if (ownsContext_) {
      context_->close();
    }
    runtime_.reset();
  }

  JsEnv(const JsEnv&) = delete;
  JsEnv& operator=(const JsEnv&) = delete;

  js::Runtime& rt() { return *runtime_; }

  cpp::Context& context() { return *context_; }

  cpp::ManualScheduler& scheduler() { return *scheduler_; }

  cpp::AttachedRuntime& attached() { return *attached_; }

  /** Evaluates [source] and returns the result as JavaScript's `String(...)` would print it. */
  std::string eval(const std::string& source) {
    try {
      return runtime_->evaluateJavaScript(std::make_shared<js::StringBuffer>(source), "test.js")
                      .toString(*runtime_)
                      .utf8(*runtime_);
    } catch (const js::JSError& error) {
      throw std::runtime_error("JavaScript threw: " + error.getMessage() + "\n" + error.getStack());
    }
  }

  void exec(const std::string& source) { (void) eval(source); }

  /** What [source] throws: its `message`, or "no error" when it does not throw. */
  std::string errorOf(const std::string& source) {
    return eval("(() => { try { " + source + "; return 'no error'; } catch (e) { return String(e.message); } })()");
  }

  /** The `code` of what [source] throws, or "no error" / "no code". */
  std::string codeOf(const std::string& source) {
    return eval(
      "(() => { try { " + source + "; return 'no error'; } catch (e) { return 'code' in e ? String(e.code) : 'no code'; } })()"
    );
  }

  /** Runs the jobs other threads posted, and the microtasks they queued. Returns how many jobs ran. */
  size_t pump() {
    size_t jobs = scheduler_->drain();
    runtime_->drainMicrotasks();
    return jobs;
  }

  /**
   * Pumps until the JavaScript expression [predicate] is truthy, or fails after [timeout]: the
   * test equivalent of an event loop.
   */
  void pumpUntil(const std::string& predicate, const std::chrono::milliseconds timeout = std::chrono::seconds(5)) {
    const auto deadline = std::chrono::steady_clock::now() + timeout;
    while (true) {
      pump();
      if (eval("Boolean(" + predicate + ")") == "true") {
        return;
      }
      if (std::chrono::steady_clock::now() > deadline) {
        throw test::Failure{"timed out waiting for: " + predicate};
      }
      scheduler_->waitForJob(std::chrono::milliseconds(10));
    }
  }

private:
  std::unique_ptr<facebook::hermes::HermesRuntime> runtime_;
  std::shared_ptr<cpp::ManualScheduler> scheduler_;
  bool ownsContext_;
  std::shared_ptr<cpp::Context> context_;
  std::unique_ptr<cpp::AttachedRuntime> attached_;
};
