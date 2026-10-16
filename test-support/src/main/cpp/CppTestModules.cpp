// Modules written with the C++ API (api/src/main/cpp/expo-modules-cpp), served by the bridge next to
// the Kotlin ones. They live in this library, not in the bridge, because that is how a module
// library outside the bridge uses the API.
//
// They are added to one runtime's context on request, and not registered with `EXPO_MODULE` as the
// library loads: a static registration is served by every runtime in the process, and the tests that
// list `expo.modules` would see these in all of them. `StaticProbe` is registered for the one test
// that checks the static path, and taken off again.
//
// Driven by test-app's CppModulesTest.

#include <atomic>
#include <chrono>
#include <string>
#include <thread>
#include <vector>

#include <expo-modules-cpp/Expo.h>

#include "CppTestModules.h"

namespace cpp = expo::modules::v2::cpp;
namespace js = facebook::jsi;

namespace {
  struct Reading {
    std::string sensor;
    double value = 0;
    std::optional<std::string> unit;
  };
  EXPO_RECORD(Reading, sensor, value, unit)

  enum class Mode { Fast, Slow };
  EXPO_ENUM(Mode, (Fast, "fast"), (Slow, "slow"))

  class CppCounter : public cpp::SharedObject {
  public:
    static void define(cpp::ClassDefinition<CppCounter>& cls) {
      cls.constructor<int>();
      cls.function("increment", &CppCounter::increment);
      cls.property("value", &CppCounter::value_);
      cls.constant("kind", "counter");
    }

    explicit CppCounter(int start) : value_(start) {
    }

    int increment() {
      onChanged.emit(++value_);
      return value_;
    }

    int value_;
    cpp::Event<int> onChanged{this, "changed"};
  };

  class CppCalc : public cpp::Module {
  public:
    void define(cpp::ModuleDefinition& def) override {
      def.constant("PI", 3.14159);
      def.function("add", [](int a, int b) { return a + b; });
      def.function("greet", [](std::string name, std::optional<std::string> greeting) {
        return greeting.value_or("Hello") + ", " + name + "!";
      });
      def.function("mode", [](Mode mode) { return mode == Mode::Fast ? Mode::Slow : Mode::Fast; });
      def.function("reading", [](Reading reading) {
        reading.value *= 2;
        return reading;
      });
      def.property("factor", [this] { return factor_; }, [this](double value) { factor_ = value; });
      def.function("scaled", this, &CppCalc::scaled);
      def.function("fail", []() -> int { throw cpp::Exception("ERR_CPP", "failed in C++"); });
      def.function("make", [] { return std::make_shared<CppCounter>(10); });

      // Settled from another thread: the result has to find its way back to the JS thread through
      // the Kotlin scheduler.
      def.function("later", [](int value, cpp::Promise<int> promise) {
        std::thread([value, promise] {
          std::this_thread::sleep_for(std::chrono::milliseconds(5));
          promise.resolve(value * 2);
        }).detach();
      });
      def.function("rejectLater", [](cpp::Promise<int> promise) {
        std::thread([promise] { promise.reject("ERR_LATER", "rejected later"); }).detach();
      });
      def.function("hop", [](int n) -> cpp::Task<int> {
        co_await cpp::background();
        co_return n + 1;
      });
      def.function("hopBack", [](js::Runtime& rt, js::Function callback) -> cpp::Task<double> {
        co_await cpp::background();
        co_await cpp::onJSThread(rt);
        co_return callback.call(rt, 20).getNumber() + 1;
      });
      def.function("inBackground", cpp::background([](int a, int b) { return a * b; }));

      // Emits from a native thread.
      def.function("tickFromThread", [this](int value) {
        std::thread([this, value] { onTick.emit(value); }).detach();
      });
      def.function("tick", [this](int value) { onTick.emit(value); });
      def.sharedClass<CppCounter>();
    }

    double scaled(double value) const { return value * factor_; }

    double factor_ = 3;
    cpp::Event<int> onTick{this, "tick"};
  };

  /** Shadowed by a Kotlin module of the same name: the Kotlin one is served. */
  class Shadowed : public cpp::Module {
  public:
    void define(cpp::ModuleDefinition& def) override {
      def.function("who", [] { return std::string("c++"); });
    }
  };

  class Lifecycle : public cpp::Module {
  public:
    void define(cpp::ModuleDefinition& def) override {
      def.function("destroyedCount", [] { return destroyed.load(); });
      def.function("name", [] { return std::string("lifecycle"); });
    }

    void onDestroy() override { destroyed++; }

    static std::atomic<int> destroyed;
  };

  std::atomic<int> Lifecycle::destroyed{0};
} // namespace

/** A module registered statically, the way an app's modules are, for the test that checks that path. */
class StaticProbe : public cpp::Module {
public:
  void define(cpp::ModuleDefinition& def) override {
    def.function("probe", [] { return std::string("static"); });
  }
};

namespace expo::modules::v2 {
  void installCppTestModules(cpp::Context& context) {
    context.emplace<CppCalc>("CppCalc");
    context.emplace<Shadowed>("ShadowedByKotlin");
    context.emplace<Lifecycle>("CppLifecycle");
  }

  void registerStaticProbe() {
    cpp::detail::registerModuleFactory({
      .name = "CppStaticProbe",
      .create = [] { return std::static_pointer_cast<cpp::Module>(std::make_shared<StaticProbe>()); },
      .typeKey = cpp::detail::typeKey<StaticProbe>(),
    });
  }

  void unregisterStaticProbe() {
    cpp::detail::unregisterModuleFactory("CppStaticProbe");
  }
} // namespace expo::modules::v2
