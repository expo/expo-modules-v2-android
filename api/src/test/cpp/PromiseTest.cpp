#include "Fixture.h"

#include <atomic>
#include <future>
#include <thread>

namespace {
  struct Result {
    int code = 0;
    std::string note;
  };
  EXPO_RECORD(Result, code, note)

  /** A type whose conversion always fails, to see what happens to a promise that carries it. */
  struct Unconvertible {};
} // namespace

namespace expo::modules::v2::cpp {
  template<>
  struct Converter<Unconvertible> {
    static js::Value toJS(js::Runtime&, const Unconvertible&) { detail::throwConversion("this value cannot cross"); }
  };
} // namespace expo::modules::v2::cpp

namespace {
  class Accumulator : public cpp::SharedObject {
  public:
    static void define(cpp::ClassDefinition<Accumulator>& cls) {
      cls.constructor<>();
      cls.function("addLater", [](Accumulator& self, int amount) -> cpp::Task<int> {
        co_await cpp::background();
        co_return self.total += amount;
      });
      cls.function("addShared", [](std::shared_ptr<Accumulator> self, int amount) -> cpp::Task<int> {
        co_await cpp::background();
        co_return self->total += amount;
      });
      cls.function("merge", [](Accumulator& self, Accumulator& other) -> cpp::Task<int> {
        co_return self.total + other.total;
      });
      cls.function("fail", [](Accumulator&) -> cpp::Task<int> { throw std::runtime_error("accumulator failed"); co_return 0; });
      cls.function("viaPromise", [](Accumulator& self, cpp::Promise<int> promise) { promise.resolve(self.total); });
    }

    std::atomic<int> total{0};
  };

  class Promises : public cpp::Module {
  public:
    void define(cpp::ModuleDefinition& def) override {
      def.function("now", [](int value, cpp::Promise<int> promise) { promise.resolve(value); });
      def.function("nothing", [](cpp::Promise<> promise) { promise.resolve(); });
      def.function("hold", [this](cpp::Promise<std::string> promise) { held.push_back(promise); });
      def.function("heldCount", [this] { return static_cast<int>(held.size()); });
      def.function("settleHeld", [this](std::string value) {
        for (auto& promise: held) {
          promise.resolve(value);
        }
        held.clear();
      });
      def.function("twice", [](cpp::Promise<int> promise) {
        promise.resolve(1);
        promise.resolve(2);
        promise.reject("ERR_IGNORED", "too late");
      });
      def.function("rejectThenResolve", [](cpp::Promise<int> promise) {
        promise.reject("ERR_FIRST", "first");
        promise.resolve(2);
      });
      def.function("isSettled", [](cpp::Promise<std::string> promise) {
        const bool before = promise.isSettled();
        promise.resolve(std::string(before ? "yes" : "no"));
        promise.resolve(std::string("ignored"));
      });
      def.function("throwing", [](cpp::Promise<int>) { throw std::runtime_error("before settling"); });

      // Every kind of result a promise carries.
      def.function("number", [](cpp::Promise<double> promise) { promise.resolve(2.5); });
      def.function("text", [](cpp::Promise<std::string> promise) { promise.resolve("text"); });
      def.function("list", [](cpp::Promise<std::vector<int>> promise) { promise.resolve({1, 2, 3}); });
      def.function("record", [](cpp::Promise<Result> promise) { promise.resolve(Result{7, "seven"}); });
      def.function("maybe", [](cpp::Promise<std::optional<int>> promise) { promise.resolve(std::nullopt); });
      def.function("map", [](cpp::Promise<std::map<std::string, int>> promise) { promise.resolve({{"a", 1}}); });
      def.function("big", [](cpp::Promise<std::string> promise) { promise.resolve(std::string(300000, 'x')); });
      def.function("object", [](cpp::Promise<std::shared_ptr<Accumulator>> promise) { promise.resolve(std::make_shared<Accumulator>()); });

      def.function("coroutine", [](int n) -> cpp::Task<int> { co_return n * 2; });
      def.function("hop", [](int n) -> cpp::Task<int> {
        co_await cpp::background();
        co_return n * 2;
      });
      def.function("nothingLater", []() -> cpp::Task<> {
        co_await cpp::background();
      });
      def.function("chained", [](int n) -> cpp::Task<int> {
        const int first = co_await doubleLater(n);
        const int second = co_await doubleLater(first);
        co_return first + second;
      });
      def.function("threads", [](js::Runtime& rt) -> cpp::Task<std::string> {
        const auto jsThread = std::this_thread::get_id();
        co_await cpp::background();
        const bool movedOff = std::this_thread::get_id() != jsThread;
        co_await cpp::onJSThread(rt);
        const bool backOn = std::this_thread::get_id() == jsThread;
        co_return std::string(movedOff ? "off" : "on") + "," + (backOn ? "back" : "stuck");
      });
      def.function("callbackAfterHop", [](js::Runtime& rt, js::Function callback) -> cpp::Task<double> {
        co_await cpp::background();
        co_await cpp::onJSThread(rt);
        co_return callback.call(rt, 20).getNumber() + 1;
      });
      def.function("unconvertible", [](cpp::Promise<Unconvertible> promise) { promise.resolve(Unconvertible{}); });

      def.function("inBackground", cpp::background([](int a, int b) { return a + b; }));
      def.function("inBackgroundText", cpp::background([](const std::string& text) { return text + "!"; }));
      def.function("inBackgroundVoid", cpp::background([](int) {}));
      def.function("inBackgroundThrows", cpp::background([](int) -> int { throw std::runtime_error("background failed"); }));
      def.function("backgroundThread", cpp::background([](int) {
        return std::this_thread::get_id() == mainThread() ? std::string("js thread") : std::string("pool thread");
      }));
      def.function("nextPending", [this](cpp::Promise<int> promise) { later = promise; });
      def.sharedClass<Accumulator>();
    }

    static std::thread::id& mainThread() {
      static std::thread::id id = std::this_thread::get_id();
      return id;
    }

    std::vector<cpp::Promise<std::string>> held;
    std::optional<cpp::Promise<int>> later;

  private:
    static cpp::Task<int> doubleLater(int n) {
      co_await cpp::background();
      co_return n * 2;
    }
  };

  struct Env : JsEnv {
    std::shared_ptr<Promises> promises;

    Env() {
      Promises::mainThread();
      promises = context().emplace<Promises>("Promises");
    }

    /** Waits for the promise [call] returns, and reports how it settled. */
    std::string settled(const std::string& call) {
      exec("globalThis.out = null; (" + call + ").then((v) => { globalThis.out = 'ok:' + JSON.stringify(v); }, (e) => { globalThis.out = 'err:' + e.code + '|' + e.message; });");
      pumpUntil("globalThis.out !== null");
      return eval("globalThis.out");
    }
  };
} // namespace


TEST(promise_a_promise_settled_before_returning_settles_inside_the_call) {
  Env env;
  // Born settled: the `then` callback is queued by the time the call returns, and nothing was posted.
  env.exec("globalThis.out = null; expo.modules.Promises.now(5).then((v) => { globalThis.out = v; });");
  CHECK_EQ(env.eval("globalThis.out"), "null");  // a callback never runs synchronously
  CHECK_EQ(env.pump(), size_t(0));
  CHECK_EQ(env.eval("globalThis.out"), "5");
}

TEST(promise_a_promise_with_no_value_resolves_undefined) {
  Env env;
  CHECK_EQ(env.settled("expo.modules.Promises.nothing()"), "ok:undefined");
}

TEST(promise_a_promise_settled_from_another_thread_settles_on_the_js_thread) {
  Env env;
  env.exec("globalThis.out = null; expo.modules.Promises.hold().then((v) => { globalThis.out = v; });");
  CHECK_EQ(env.eval("expo.modules.Promises.heldCount()"), "1");

  std::thread worker([&] { env.promises->held.front().resolve("from a worker"); });
  worker.join();

  CHECK_EQ(env.eval("globalThis.out"), "null");  // posted, not run
  env.pumpUntil("globalThis.out !== null");
  CHECK_EQ(env.eval("globalThis.out"), "from a worker");
}

TEST(promise_only_the_first_settle_counts) {
  Env env;
  CHECK_EQ(env.settled("expo.modules.Promises.twice()"), "ok:1");
  CHECK_EQ(env.settled("expo.modules.Promises.rejectThenResolve()"), "err:ERR_FIRST|first");
  CHECK_EQ(env.eval("expo.modules.Promises.isSettled !== undefined"), "true");
}

TEST(promise_a_handle_reports_whether_it_settled) {
  Env env;
  CHECK_EQ(env.settled("expo.modules.Promises.isSettled()"), R"(ok:"no")");
}

TEST(promise_a_function_that_throws_before_settling_rejects_instead_of_throwing) {
  Env env;
  CHECK_EQ(env.settled("expo.modules.Promises.throwing()"), "err:undefined|before settling");
}

TEST(promise_results_cross_as_every_kind_of_value) {
  Env env;
  CHECK_EQ(env.settled("expo.modules.Promises.number()"), "ok:2.5");
  CHECK_EQ(env.settled("expo.modules.Promises.text()"), R"(ok:"text")");
  CHECK_EQ(env.settled("expo.modules.Promises.list()"), "ok:[1,2,3]");
  CHECK_EQ(env.settled("expo.modules.Promises.record()"), R"(ok:{"code":7,"note":"seven"})");
  CHECK_EQ(env.settled("expo.modules.Promises.maybe()"), "ok:null");
  CHECK_EQ(env.settled("expo.modules.Promises.map()"), R"(ok:{"a":1})");
  CHECK_EQ(env.eval("globalThis.size = 0; expo.modules.Promises.big().then((v) => { globalThis.size = v.length; }); 0"), "0");
  env.pumpUntil("globalThis.size > 0");
  CHECK_EQ(env.eval("globalThis.size"), "300000");
}

TEST(promise_a_shared_object_result_is_a_facade) {
  Env env;
  env.exec("globalThis.out = null; expo.modules.Promises.object().then((v) => { globalThis.out = v; });");
  env.pumpUntil("globalThis.out !== null");
  CHECK_EQ(env.eval("globalThis.out.viaPromise !== undefined && typeof globalThis.out.addLater"), "function");
}

TEST(promise_a_result_that_does_not_convert_rejects_the_promise) {
  Env env;
  CHECK_EQ(env.settled("expo.modules.Promises.unconvertible()"), "err:undefined|this value cannot cross");
}

TEST(promise_each_call_gets_its_own_promise_and_every_one_settles) {
  Env env;
  env.exec(
    "globalThis.results = [];"
    "[0, 1, 2].forEach((i) => { expo.modules.Promises.hold().then((v) => results.push(v + i)); });"
  );
  CHECK_EQ(env.eval("expo.modules.Promises.heldCount()"), "3");
  env.exec("expo.modules.Promises.settleHeld('v')");
  env.pumpUntil("results.length === 3");
  CHECK_EQ(env.eval("results.join()"), "v0,v1,v2");
}

TEST(promise_a_coroutine_that_never_suspends_settles_inside_the_call) {
  Env env;
  env.exec("globalThis.out = null; expo.modules.Promises.coroutine(21).then((v) => { globalThis.out = v; });");
  CHECK_EQ(env.pump(), size_t(0));
  CHECK_EQ(env.eval("globalThis.out"), "42");
}

TEST(promise_a_coroutine_can_continue_on_another_thread) {
  Env env;
  CHECK_EQ(env.settled("expo.modules.Promises.hop(21)"), "ok:42");
}

TEST(promise_a_coroutine_returning_nothing_resolves_undefined) {
  Env env;
  CHECK_EQ(env.settled("expo.modules.Promises.nothingLater()"), "ok:undefined");
}

TEST(promise_a_coroutine_can_wait_for_another) {
  Env env;
  CHECK_EQ(env.settled("expo.modules.Promises.chained(3)"), "ok:18");
}

TEST(promise_a_coroutine_can_leave_the_js_thread_and_come_back) {
  Env env;
  CHECK_EQ(env.settled("expo.modules.Promises.threads()"), R"(ok:"off,back")");
}

TEST(promise_a_coroutine_can_call_a_javascript_function_once_back_on_the_js_thread) {
  Env env;
  CHECK_EQ(env.settled("expo.modules.Promises.callbackAfterHop((n) => n * 2)"), "ok:41");
}

TEST(promise_a_function_run_in_the_background_returns_a_promise) {
  Env env;
  CHECK_EQ(env.settled("expo.modules.Promises.inBackground(2, 3)"), "ok:5");
  CHECK_EQ(env.settled("expo.modules.Promises.inBackgroundText('wow')"), R"(ok:"wow!")");
  CHECK_EQ(env.settled("expo.modules.Promises.inBackgroundVoid(1)"), "ok:undefined");
  CHECK_EQ(env.settled("expo.modules.Promises.inBackgroundThrows(1)"), "err:undefined|background failed");
  CHECK_EQ(env.settled("expo.modules.Promises.backgroundThread(1)"), R"(ok:"pool thread")");
}

TEST(promise_a_function_run_in_the_background_checks_its_arguments_at_the_call) {
  Env env;
  CHECK_EQ(env.errorOf("expo.modules.Promises.inBackground(1, 'two')"), "Invalid argument 2 of 'inBackground': expected a number, got a string");
  CHECK_CONTAINS(env.errorOf("expo.modules.Promises.inBackground(1)"), "inBackground expects 2 argument(s), but received 1");
}

TEST(promise_a_shared_object_method_returns_a_promise) {
  Env env;
  CHECK_EQ(env.settled("new expo.modules.Promises.Accumulator().addLater(5)"), "ok:5");
  CHECK_EQ(env.settled("new expo.modules.Promises.Accumulator().addShared(6)"), "ok:6");
}

TEST(promise_a_shared_object_method_takes_another_shared_object) {
  Env env;
  env.exec("globalThis.a = new expo.modules.Promises.Accumulator(); globalThis.b = new expo.modules.Promises.Accumulator();");
  CHECK_EQ(env.settled("a.addLater(3)"), "ok:3");
  CHECK_EQ(env.settled("b.addLater(4)"), "ok:4");
  CHECK_EQ(env.settled("a.merge(b)"), "ok:7");
}

TEST(promise_a_failing_shared_object_method_rejects) {
  Env env;
  CHECK_EQ(env.settled("new expo.modules.Promises.Accumulator().fail()"), "err:undefined|accumulator failed");
}

TEST(promise_a_shared_object_method_may_take_a_promise) {
  Env env;
  env.exec("globalThis.a = new expo.modules.Promises.Accumulator();");
  CHECK_EQ(env.settled("a.addLater(2).then(() => a.viaPromise())"), "ok:2");
}

TEST(promise_a_member_keeps_its_object_alive_until_the_coroutine_ends) {
  Env env;
  env.exec("globalThis.out = null; new expo.modules.Promises.Accumulator().addLater(9).then((v) => { globalThis.out = v; }); 0");
  for (int pass = 0; pass < 3; pass++) {
    env.rt().instrumentation().collectGarbage("test");
  }
  env.pumpUntil("globalThis.out !== null");
  CHECK_EQ(env.eval("globalThis.out"), "9");
}

TEST(promise_a_promise_that_is_never_settled_stays_pending) {
  Env env;
  env.exec("globalThis.state = 'pending'; expo.modules.Promises.hold().then(() => { globalThis.state = 'settled'; });");
  env.pump();
  CHECK_EQ(env.eval("globalThis.state"), "pending");
}

TEST(promise_settling_after_the_runtime_closed_does_nothing) {
  std::optional<cpp::Promise<int>> late;
  {
    auto context = cpp::Context::create(false);
    auto promises = context->emplace<Promises>("Promises");
    JsEnv env(context);
    env.exec("expo.modules.Promises.nextPending().then(() => {});");
    late = promises->later;
  }
  // The runtime, its scheduler and its data are gone; the handle must neither crash nor settle.
  late->resolve(1);
  late->reject("ERR_LATE", "late");
  CHECK(late->isSettled());
}

TEST(promise_a_job_posted_before_the_runtime_closed_finds_it_gone) {
  // The scheduler outlives the runtime here, as a host's queue does, so the job is still queued when
  // the runtime goes away and runs after it did.
  auto scheduler = std::make_shared<cpp::ManualScheduler>();
  auto context = cpp::Context::create(false);
  auto promises = context->emplace<Promises>("Promises");

  {
    auto runtime = facebook::hermes::makeHermesRuntime();
    auto attached = cpp::AttachedRuntime::attach(*runtime, context, scheduler);
    runtime->evaluateJavaScript(
      std::make_shared<js::StringBuffer>("globalThis.state = 'pending'; expo.modules.Promises.hold().then(() => { globalThis.state = 'settled'; });"),
      "test.js"
    );
    std::thread([&] { promises->held.front().resolve("posted"); }).join();

    attached.reset();
    runtime.reset();
  }

  // The job finds the runtime gone and does nothing.
  CHECK_EQ(scheduler->drain(), size_t(1));
}

namespace {
  std::atomic<int> gLiveGuards{0};

  /** Counts the coroutine frames still holding a local. */
  struct FrameGuard {
    FrameGuard() { gLiveGuards++; }

    ~FrameGuard() { gLiveGuards--; }

    FrameGuard(const FrameGuard&) = delete;
    FrameGuard& operator=(const FrameGuard&) = delete;
  };

  class Parked : public cpp::Module {
  public:
    void define(cpp::ModuleDefinition& def) override {
      def.function("park", [](js::Runtime& rt) -> cpp::Task<int> {
        FrameGuard guard;
        co_await cpp::background();
        co_await cpp::onJSThread(rt);
        co_return 1;
      });
    }
  };
} // namespace

TEST(promise_a_coroutine_waiting_for_a_scheduler_that_never_runs_it_is_destroyed_with_it) {
  CHECK_EQ(gLiveGuards.load(), 0);
  {
    JsEnv env;
    env.context().emplace<Parked>("Parked");
    env.exec("expo.modules.Parked.park().then(() => {});");

    // The coroutine hops to the pool and queues its resume on the JS thread; nobody drains it.
    CHECK(env.scheduler().waitForJob(std::chrono::seconds(5)));
    CHECK_EQ(gLiveGuards.load(), 1);
  }
  // The scheduler went away with the job in its queue: the suspended coroutine must go with it,
  // rather than stay parked with everything it holds - and the scheduler it would keep alive.
  CHECK_EQ(gLiveGuards.load(), 0);
}

namespace {
  std::atomic<int> gLiveModules{0};
  std::atomic<bool> gGate{false};
  std::atomic<int> gReadValue{0};

  /** A module whose coroutine reads a member after it was suspended. */
  class Fragile : public cpp::Module {
  public:
    Fragile() { gLiveModules++; }

    ~Fragile() override { gLiveModules--; }

    void define(cpp::ModuleDefinition& def) override {
      def.function("slow", [this]() -> cpp::Task<int> {
        co_await cpp::background();
        while (!gGate.load()) {
          std::this_thread::sleep_for(std::chrono::milliseconds(1));
        }
        // Reads the closure's capture and a member: both live in the module.
        gReadValue = value_;
        co_return value_;
      });
    }

    int value_ = 42;
  };
} // namespace

TEST(promise_a_module_coroutine_keeps_its_module_alive_after_the_runtime_is_torn_down) {
  gLiveModules = 0;
  gGate = false;
  gReadValue = 0;
  {
    JsEnv env;
    env.context().emplace<Fragile>("Fragile");
    env.exec("expo.modules.Fragile.slow().then(() => {});");
    CHECK_EQ(gLiveModules.load(), 1);
  }
  // The runtime and the context are gone, with a coroutine still waiting on the pool: it must have
  // kept the module it is part of.
  CHECK_EQ(gLiveModules.load(), 1);

  gGate = true;
  for (int i = 0; i < 5000 && gReadValue.load() == 0; i++) {
    std::this_thread::sleep_for(std::chrono::milliseconds(1));
  }
  CHECK_EQ(gReadValue.load(), 42);

  // Once it ended, so does the module.
  for (int i = 0; i < 5000 && gLiveModules.load() != 0; i++) {
    std::this_thread::sleep_for(std::chrono::milliseconds(1));
  }
  CHECK_EQ(gLiveModules.load(), 0);
}

TEST(promise_an_exception_after_a_promise_settled_is_logged_not_lost) {
  class Late : public cpp::Module {
  public:
    void define(cpp::ModuleDefinition& def) override {
      def.function("both", [](cpp::Promise<int> promise) {
        promise.resolve(1);
        throw std::runtime_error("after the fact");
      });
    }
  };

  JsEnv env;
  env.context().emplace<Late>("Late");
  env.exec("globalThis.out = null; expo.modules.Late.both().then((v) => { globalThis.out = v; });");
  env.pumpUntil("globalThis.out !== null");
  CHECK_EQ(env.eval("globalThis.out"), "1");
}
