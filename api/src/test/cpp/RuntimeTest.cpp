#include "Fixture.h"

#include <atomic>
#include <thread>

namespace {
  class Callbacks : public cpp::Module {
  public:
    void define(cpp::ModuleDefinition& def) override {
      def.function("store", [this](js::Runtime& rt, js::Function callback) {
        runtime = &rt;
        stored = std::make_shared<js::Function>(std::move(callback));
      });
      def.function("onThisThread", [](js::Runtime& rt) { return cpp::isOnJSThread(rt); });
      def.function("callLater", [this](js::Runtime& rt) {
        cpp::runOnJSThread(rt, [stored = stored](js::Runtime& runtime) { stored->call(runtime, 5); });
      });
    }

    js::Runtime* runtime = nullptr;
    std::shared_ptr<js::Function> stored;
  };

  struct Env : JsEnv {
    std::shared_ptr<Callbacks> callbacks;

    Env() {
      callbacks = context().emplace<Callbacks>("Callbacks");
      exec("globalThis.seen = []; expo.modules.Callbacks.store((n) => seen.push(n));");
    }
  };
} // namespace

TEST(runtime_a_job_posted_from_another_thread_runs_on_the_js_thread_when_it_drains) {
  Env env;
  std::thread worker([&] {
    cpp::runOnJSThread(*env.callbacks->runtime, [stored = env.callbacks->stored](js::Runtime& rt) { stored->call(rt, 7); });
  });
  worker.join();

  CHECK_EQ(env.eval("seen.length"), "0");
  env.pumpUntil("seen.length > 0");
  CHECK_EQ(env.eval("seen.join()"), "7");
}

TEST(runtime_a_job_posted_on_the_js_thread_runs_at_once) {
  Env env;
  env.exec("expo.modules.Callbacks.callLater()");
  CHECK_EQ(env.eval("seen.join()"), "5");
}

TEST(runtime_the_job_receives_the_runtime_it_was_posted_to) {
  Env env;
  js::Runtime* seen = nullptr;
  cpp::runOnJSThread(env.rt(), [&](js::Runtime& rt) { seen = &rt; });
  CHECK(seen == &env.rt());
}

TEST(runtime_a_job_for_a_runtime_without_an_attachment_does_nothing) {
  auto runtime = facebook::hermes::makeHermesRuntime();
  bool ran = false;
  cpp::runOnJSThread(*runtime, [&](js::Runtime&) { ran = true; });
  CHECK(!ran);
  CHECK(!cpp::isOnJSThread(*runtime));
}

TEST(runtime_a_job_posted_before_the_runtime_was_detached_does_not_run_after) {
  auto runtime = facebook::hermes::makeHermesRuntime();
  auto scheduler = std::make_shared<cpp::ManualScheduler>();
  auto attached = cpp::AttachedRuntime::attach(*runtime, cpp::Context::create(false), scheduler);

  std::atomic<bool> ran{false};
  std::thread([&] { cpp::runOnJSThread(*runtime, [&](js::Runtime&) { ran = true; }); }).join();

  attached.reset();
  scheduler->drain();
  CHECK(!ran.load());
}

TEST(runtime_the_js_thread_is_known_from_any_thread) {
  Env env;
  CHECK(cpp::isOnJSThread(env.rt()));
  CHECK_EQ(env.eval("expo.modules.Callbacks.onThisThread()"), "true");

  bool onWorker = true;
  std::thread([&] { onWorker = cpp::isOnJSThread(env.rt()); }).join();
  CHECK(!onWorker);
}

namespace {
  std::string evaluate(js::Runtime& rt, const std::string& source) {
    return rt.evaluateJavaScript(std::make_shared<js::StringBuffer>(source), "test.js").toString(rt).utf8(rt);
  }
} // namespace

TEST(runtime_attaching_again_serves_the_new_modules_and_not_the_detached_ones) {
  auto runtime = facebook::hermes::makeHermesRuntime();
  auto scheduler = std::make_shared<cpp::ManualScheduler>();

  auto first = cpp::Context::create(false);
  first->emplace<Callbacks>("First");
  {
    auto attached = cpp::AttachedRuntime::attach(*runtime, first, scheduler);
    CHECK_EQ(evaluate(*runtime, "typeof expo.modules.First"), "object");
  }
  first->close();

  auto second = cpp::Context::create(false);
  second->emplace<Callbacks>("Second");
  auto attached = cpp::AttachedRuntime::attach(*runtime, second, scheduler);

  CHECK_EQ(evaluate(*runtime, "typeof expo.modules.Second"), "object");
  CHECK_EQ(evaluate(*runtime, "typeof expo.modules.First"), "undefined");
  CHECK_EQ(evaluate(*runtime, "Object.keys(expo.modules).join()"), "Second");

  attached.reset();
  second->close();
}

TEST(runtime_attaching_again_keeps_the_modules_the_host_owns) {
  auto runtime = facebook::hermes::makeHermesRuntime();
  auto scheduler = std::make_shared<cpp::ManualScheduler>();
  evaluate(*runtime, "globalThis.expo = { modules: { Native: { answer: 42 } } }; 0");

  for (const char* const name: {"First", "Second", "Third"}) {
    auto context = cpp::Context::create(false);
    context->emplace<Callbacks>(name);
    auto attached = cpp::AttachedRuntime::attach(*runtime, context, scheduler);

    // The host's module stays reachable however many times a context came and went, and each context
    // brings only its own.
    CHECK_EQ(evaluate(*runtime, "expo.modules.Native.answer"), "42");
    CHECK_EQ(evaluate(*runtime, std::string("typeof expo.modules.") + name), "object");
    CHECK_EQ(
      evaluate(*runtime, "Object.keys(expo.modules).sort().join()"),
      std::string(name) < std::string("Native") ? std::string(name) + ",Native" : std::string("Native,") + name
    );

    attached.reset();
    context->close();
  }
}
