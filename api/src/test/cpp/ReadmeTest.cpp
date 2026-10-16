// The examples of expo-modules-cpp/README.md, as they are written there, so the guide cannot drift
// from the API: if one stops compiling or stops doing what the guide says, this fails.

#include "Fixture.h"

#include <thread>

// --- the first example -------------------------------------------------------------------------

class MathModule : public cpp::Module {
public:
  void define(cpp::ModuleDefinition& def) override {
    def.constant("PI", 3.14159);
    def.function("add", [](int a, int b) { return a + b; });
    def.function("scale", this, &MathModule::scale);
    def.property("factor", [this] { return factor_; }, [this](double value) { factor_ = value; });
  }

private:
  double scale(double value) const { return value * factor_; }

  double factor_ = 2;
};

EXPO_MODULE(MathModule)

TEST(readme_the_first_example_works_as_written) {
  JsEnv env(cpp::Context::create());
  CHECK_EQ(env.eval("expo.modules.MathModule.add(1, 2)"), "3");
  CHECK_EQ(env.eval("expo.modules.MathModule.PI"), "3.14159");
  CHECK_EQ(env.eval("expo.modules.MathModule.scale(5)"), "10");
  env.exec("expo.modules.MathModule.factor = 3");
  CHECK_EQ(env.eval("expo.modules.MathModule.scale(5)"), "15");
}

// --- records and enums -------------------------------------------------------------------------

namespace readme {
  struct Options {
    std::string title;
    int retries = 3;
    std::optional<std::string> subtitle;
  };
  EXPO_RECORD(Options, title, (retries, optional), subtitle)

  enum class Style { Light, Heavy };
  EXPO_ENUM(Style, (Light, "light"), (Heavy, "heavy"))  // 'light' / 'heavy'

  enum class Priority { Low = 0, High = 10 };
  EXPO_ENUM(Priority, (Low, 0), (High, 10))  // 0 / 10

  enum class Size { Small, Large };
  EXPO_ENUM(Size, Small, Large)  // 'Small' / 'Large'

  class Shapes : public cpp::Module {
  public:
    void define(cpp::ModuleDefinition& def) override {
      def.function("options", [](Options options) { return options.title + ":" + std::to_string(options.retries) + ":" + options.subtitle.value_or("-"); });
      def.function("style", [](Style style) { return style; });
      def.function("priority", [](Priority priority) { return priority; });
      def.function("size", [](Size size) { return size; });
    }
  };
} // namespace readme

TEST(readme_records_and_enums_cross_as_described) {
  JsEnv env;
  env.context().emplace<readme::Shapes>("Shapes");
  CHECK_EQ(env.eval("expo.modules.Shapes.options({ title: 't' })"), "t:3:-");
  CHECK_EQ(env.eval("expo.modules.Shapes.options({ title: 't', retries: 5, subtitle: 's' })"), "t:5:s");
  CHECK_EQ(env.eval("expo.modules.Shapes.style('heavy')"), "heavy");
  CHECK_EQ(env.eval("expo.modules.Shapes.priority(10)"), "10");
  CHECK_EQ(env.eval("expo.modules.Shapes.size('Large')"), "Large");
}

// --- shared objects ----------------------------------------------------------------------------

namespace readme {
  class Counter : public cpp::SharedObject {
  public:
    static void define(cpp::ClassDefinition<Counter>& cls) {
      cls.constructor<int>();  // new Counter(5)
      cls.function("increment", &Counter::increment);
      cls.function("reset", [](Counter& self) { self.value_ = 0; });
      cls.property("value", &Counter::value_);
      cls.constant("kind", "counter");  // same for every instance
      cls.constant("label", [](Counter& self) { return "#" + std::to_string(self.value_); });
    }

    explicit Counter(int start) : value_(start) {}
    int increment() { return ++value_; }

    int value_;
    cpp::Event<int> onChanged{this, "changed"};
  };

  class Counting : public cpp::Module {
  public:
    void define(cpp::ModuleDefinition& def) override {
      def.sharedClass<Counter>();
      def.function("make", [] { return std::make_shared<Counter>(1); });
      def.function("bump", [](Counter& counter) { return counter.increment(); });
    }
  };
} // namespace readme

TEST(readme_the_shared_object_example_works_as_written) {
  JsEnv env;
  env.context().emplace<readme::Counting>("Counting");
  CHECK_EQ(env.eval("const c = new expo.modules.Counting.Counter(5); [c.increment(), c.value, c.kind, c.label].join()"), "6,6,counter,#6");
  CHECK_EQ(env.eval("globalThis.k = expo.modules.Counting.make(); expo.modules.Counting.bump(k)"), "2");
  CHECK_EQ(env.eval("k.reset(); k.value"), "0");
}

// --- annotations -------------------------------------------------------------------------------

namespace readme::annotated {
  class Counter : public cpp::ExpoSharedObject<Counter> {
  public:
    EXPO_JS_CONSTRUCTOR(int)                       // new Counter(5)
    explicit Counter(int start) : value_(start) {}

    EXPO_JS(increment)
    int increment() { return ++value_; }

    EXPO_JS(value_, "value")                       // a name of its own
    int value_;

    EXPO_JS_CONSTANT(kind)                         // same for every instance
    static constexpr const char* kind = "counter";

    EXPO_JS_CONSTANT(label)                        // computed at the first read of each instance
    std::string label() { return "#" + std::to_string(value_); }

    EXPO_EVENT(onChanged, int);                    // subscribed to as 'changed'
  };

  class Counting : public cpp::ExpoModule<Counting> {
  public:
    EXPO_JS_CLASS(Counter)                         // new expo.modules.Counting.Counter(...)

    EXPO_JS(make)
    std::shared_ptr<Counter> make() { return std::make_shared<Counter>(1); }

    EXPO_JS(bump)
    static int bump(Counter& counter) { return counter.increment(); }
  };

  // EXPO_MODULE(Counting) registers it for every context; the test adds it to one.
} // namespace readme::annotated

TEST(readme_the_annotation_example_works_as_written) {
  JsEnv env;
  env.context().emplace<readme::annotated::Counting>("Counting");
  CHECK_EQ(env.eval("globalThis.c = new expo.modules.Counting.Counter(5); [c.increment(), c.value, c.kind, c.label].join()"), "6,6,counter,#6");
  CHECK_EQ(env.eval("globalThis.k = expo.modules.Counting.make(); expo.modules.Counting.bump(k)"), "2");
  CHECK_EQ(env.eval("globalThis.seen = []; c.addListener('changed', (n) => seen.push(n)); 0"), "0");
}

// --- events ------------------------------------------------------------------------------------

namespace readme {
  class Progress : public cpp::Module {
  public:
    void define(cpp::ModuleDefinition& def) override { def.function("report", [this] { onProgress.emit(7, "halfway"); }); }

    int started = 0;
    int stopped = 0;

    cpp::Event<std::string> onStateChange{this, "stateChange"};
    cpp::Event<int, std::string> onProgress{this, "progress", {
      .onStartObserving = [this] { started++; },
      .onStopObserving = [this] { stopped++; },
    }};
  };
} // namespace readme

TEST(readme_the_event_example_works_as_written) {
  JsEnv env;
  auto progress = env.context().emplace<readme::Progress>("Progress");
  env.exec("globalThis.seen = []; globalThis.sub = expo.modules.Progress.addListener('progress', (n, s) => seen.push(n + s));");
  CHECK_EQ(progress->started, 1);
  env.exec("expo.modules.Progress.report()");
  progress->onStateChange.emit("playing");
  progress->onStateChange("paused");
  CHECK_EQ(env.eval("seen.join()"), "7halfway");
  env.exec("sub.remove()");
  CHECK_EQ(progress->stopped, 1);
}

// --- async and errors --------------------------------------------------------------------------

namespace readme {
  std::string download(const std::string& url) {
    if (url == "bad") {
      throw std::runtime_error("no route");
    }
    return "body of " + url;
  }

  std::string readFile(const std::string& path) { return "text of " + path; }

  std::string sha256(const std::string& text) { return "hash of " + text; }

  class Async : public cpp::Module {
  public:
    void define(cpp::ModuleDefinition& def) override {
      // A promise to settle yourself, from any thread. The first settle counts.
      def.function("load", [](std::string url, cpp::Promise<std::string> promise) {
        std::thread([=] {
          try { promise.resolve(download(url)); }
          catch (const std::exception& e) { promise.reject("ERR_DOWNLOAD", e.what()); }
        }).detach();
      });

      // A coroutine.
      def.function("read", [](std::string path) -> cpp::Task<std::string> {
        co_await cpp::background();  // off the JS thread, for the blocking part
        std::string text = readFile(path);
        co_return text;  // JavaScript gets it, on the JS thread
      });

      // The same thing, without writing a coroutine.
      def.function("sha256", cpp::background([](std::string text) { return sha256(text); }));

      def.function("find", [](std::string path) -> int {
        throw cpp::Exception("ERR_NOT_FOUND", "no such file: " + path);  // error.code === 'ERR_NOT_FOUND'
      });
      def.function("fail", []() -> int {
        throw std::runtime_error("failed");  // an Error with that message
      });
    }
  };
} // namespace readme

TEST(readme_the_async_and_error_examples_work_as_written) {
  JsEnv env;
  env.context().emplace<readme::Async>("Async");
  const auto settled = [&](const std::string& call) {
    env.exec("globalThis.out = null; " + call + ".then((v) => { globalThis.out = v; }, (e) => { globalThis.out = e.code + '|' + e.message; });");
    env.pumpUntil("globalThis.out !== null");
    return env.eval("globalThis.out");
  };
  CHECK_EQ(settled("expo.modules.Async.load('x')"), "body of x");
  CHECK_EQ(settled("expo.modules.Async.load('bad')"), "ERR_DOWNLOAD|no route");
  CHECK_EQ(settled("expo.modules.Async.read('f')"), "text of f");
  CHECK_EQ(settled("expo.modules.Async.sha256('t')"), "hash of t");
  CHECK_EQ(env.errorOf("expo.modules.Async.fail()"), "failed");
  CHECK_EQ(env.codeOf("expo.modules.Async.find('p')"), "ERR_NOT_FOUND");
}

// --- hosting -----------------------------------------------------------------------------------

TEST(readme_the_hosting_example_works_as_written) {
  auto runtime = facebook::hermes::makeHermesRuntime();
  auto scheduler = std::make_shared<cpp::ManualScheduler>();  // the constructing thread is the JS thread
  auto attached = cpp::AttachedRuntime::attach(*runtime, cpp::Context::create(), scheduler);

  const auto source = std::make_shared<js::StringBuffer>("String(expo.modules.MathModule.add(1, 2))");
  CHECK_EQ(runtime->evaluateJavaScript(source, "app.js").toString(*runtime).utf8(*runtime), "3");
  scheduler->drain();  // run what other threads posted
  runtime->drainMicrotasks();
}

// --- reaching the JS thread --------------------------------------------------------------------

namespace readme {
  class Timers : public cpp::Module {
  public:
    void define(cpp::ModuleDefinition& def) override {
      def.function("subscribe", [this](js::Runtime& rt, js::Function fn) {
        runtime = &rt;
        callback = std::make_shared<js::Function>(std::move(fn));
      });
    }

    js::Runtime* runtime = nullptr;
    std::shared_ptr<js::Function> callback;
  };
} // namespace readme

TEST(readme_running_a_job_on_the_js_thread_works_as_written) {
  JsEnv env;
  auto timers = env.context().emplace<readme::Timers>("Timers");
  env.exec("globalThis.seen = []; expo.modules.Timers.subscribe((n) => seen.push(n));");
  std::thread([&] {
    cpp::runOnJSThread(*timers->runtime, [callback = timers->callback](js::Runtime& rt) { callback->call(rt, 42); });
  }).join();
  env.pumpUntil("seen.length > 0");
  CHECK_EQ(env.eval("seen.join()"), "42");
}
