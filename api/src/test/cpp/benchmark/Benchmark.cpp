// How much does the C++ API cost over a hand-written JSI host function?
//
// Each case calls an export from a JavaScript loop and reports the nanoseconds a call takes, less
// the loop itself. The "raw" rows are the same export written directly against JSI - the floor
// any API on top of JSI is measured against.
//
//   cmake --build <dir> --target expo-modules-cpp-benchmark && <dir>/expo-modules-cpp-benchmark

#include <chrono>
#include <cstdio>
#include <string>
#include <vector>

#include <hermes/hermes.h>

#include <expo-modules-cpp/Expo.h>

namespace js = facebook::jsi;
namespace cpp = expo::modules::v2::cpp;

namespace {
  struct Point {
    double x = 0;
    double y = 0;
  };
  EXPO_RECORD(Point, x, y)

  class Counter : public cpp::SharedObject {
  public:
    static void define(cpp::ClassDefinition<Counter>& cls) {
      cls.constructor<>();
      cls.function("increment", &Counter::increment);
    }

    int increment() { return ++count_; }

    int count_ = 0;
  };

  class Bench : public cpp::Module {
  public:
    void define(cpp::ModuleDefinition& def) override {
      def.function("add", [](int a, int b) { return a + b; });
      def.function("addDouble", [](double a, double b) { return a + b; });
      def.function("nothing", [] {});
      def.function("concat", [](const std::string& a, const std::string& b) { return a + b; });
      def.function("sum", [](const std::vector<double>& values) {
        double total = 0;
        for (const double value: values) {
          total += value;
        }
        return total;
      });
      def.function("swap", [](Point p) { return Point{p.y, p.x}; });
      def.function("optional", [](int a, std::optional<int> b) { return a + b.value_or(0); });
      def.function("make", [] { return std::make_shared<Counter>(); });
      def.function("bump", [](Counter& counter) { return counter.increment(); });
      def.function("promise", [](int n, cpp::Promise<int> promise) { promise.resolve(n); });
      def.function("coroutine", [](int n) -> cpp::Task<int> { co_return n; });
      def.sharedClass<Counter>();
    }
  };

  /** The same exports, written straight against JSI. */
  void installRaw(js::Runtime& rt, js::Object& target) {
    const auto define = [&](const char* name, unsigned int arity, js::HostFunctionType fn) {
      target.setProperty(rt, name, js::Function::createFromHostFunction(rt, js::PropNameID::forAscii(rt, name), arity, std::move(fn)));
    };
    define("add", 2, [](js::Runtime& r, const js::Value&, const js::Value* args, size_t count) -> js::Value {
      if (count != 2) {
        throw js::JSError(r, "add expects 2 arguments");
      }
      return js::Value(args[0].asNumber() + args[1].asNumber());
    });
    define("nothing", 0, [](js::Runtime&, const js::Value&, const js::Value*, size_t) -> js::Value {
      return js::Value::undefined();
    });
    define("concat", 2, [](js::Runtime& r, const js::Value&, const js::Value* args, size_t) -> js::Value {
      return js::Value(js::String::createFromUtf8(r, args[0].getString(r).utf8(r) + args[1].getString(r).utf8(r)));
    });
    define("sum", 1, [](js::Runtime& r, const js::Value&, const js::Value* args, size_t) -> js::Value {
      const js::Array array = args[0].getObject(r).getArray(r);
      double total = 0;
      for (size_t i = 0, n = array.size(r); i < n; i++) {
        total += array.getValueAtIndex(r, i).getNumber();
      }
      return js::Value(total);
    });
    define("swap", 1, [](js::Runtime& r, const js::Value&, const js::Value* args, size_t) -> js::Value {
      const js::Object in = args[0].getObject(r);
      js::Object out(r);
      out.setProperty(r, "x", in.getProperty(r, "y"));
      out.setProperty(r, "y", in.getProperty(r, "x"));
      return js::Value(std::move(out));
    });
  }

  double nanosecondsPerCall(js::Runtime& rt, const std::string& setup, const std::string& call, const int iterations) {
    rt.evaluateJavaScript(std::make_shared<js::StringBuffer>(setup), "setup.js");
    const std::string loop = "(function () { for (let i = 0; i < " + std::to_string(iterations) + "; i++) { " + call + "; } })()";
    const auto program = std::make_shared<js::StringBuffer>(loop);

    rt.evaluateJavaScript(program, "warmup.js");
    double best = 1e18;
    for (int round = 0; round < 5; round++) {
      const auto start = std::chrono::steady_clock::now();
      rt.evaluateJavaScript(program, "loop.js");
      const auto elapsed = std::chrono::duration<double, std::nano>(std::chrono::steady_clock::now() - start).count();
      best = std::min(best, elapsed / iterations);
    }
    return best;
  }
} // namespace

int main() {
  auto rt = facebook::hermes::makeHermesRuntime();
  auto scheduler = std::make_shared<cpp::ManualScheduler>();
  auto context = cpp::Context::create(false);
  context->emplace<Bench>("Bench");
  auto attached = cpp::AttachedRuntime::attach(*rt, context, scheduler);

  js::Object raw(*rt);
  installRaw(*rt, raw);
  rt->global().setProperty(*rt, "raw", raw);

  constexpr int kIterations = 200000;
  const std::string setup =
    "globalThis.m = expo.modules.Bench; globalThis.empty = function () {};"
    "globalThis.numbers = Array.from({ length: 100 }, (_, i) => i);"
    "globalThis.counter = m.make(); globalThis.point = { x: 1, y: 2 };";

  struct Row {
    const char* label;
    const char* rawCall;
    const char* apiCall;
  };
  const Row rows[] = {
    {"no arguments, no result", "raw.nothing()", "m.nothing()"},
    {"add(int, int)", "raw.add(1, 2)", "m.add(1, 2)"},
    {"add(double, double)", "raw.add(1.5, 2.5)", "m.addDouble(1.5, 2.5)"},
    {"concat(string, string)", "raw.concat('hello, ', 'world')", "m.concat('hello, ', 'world')"},
    {"sum(array of 100 numbers)", "raw.sum(numbers)", "m.sum(numbers)"},
    {"record in, record out", "raw.swap(point)", "m.swap(point)"},
    {"optional argument left out", nullptr, "m.optional(1)"},
    {"shared object method", nullptr, "m.bump(counter)"},
    {"shared object method via facade", nullptr, "counter.increment()"},
    {"create a shared object", nullptr, "m.make()"},
    {"promise resolved in the call", nullptr, "m.promise(1)"},
    {"coroutine that never suspends", nullptr, "m.coroutine(1)"},
  };

  const double loopCost = nanosecondsPerCall(*rt, setup, "empty()", kIterations);
  std::printf("Loop of an empty JS function call: %.0f ns (subtracted from the rows below)\n\n", loopCost);
  std::printf("%-36s %10s %10s %9s\n", "call", "raw JSI", "C++ API", "overhead");

  for (const Row& row: rows) {
    const double api = nanosecondsPerCall(*rt, setup, row.apiCall, kIterations) - loopCost;
    if (row.rawCall != nullptr) {
      const double rawCost = nanosecondsPerCall(*rt, setup, row.rawCall, kIterations) - loopCost;
      std::printf("%-36s %8.0f ns %8.0f ns %+7.0f ns\n", row.label, rawCost, api, api - rawCost);
    } else {
      std::printf("%-36s %10s %8.0f ns\n", row.label, "-", api);
    }
    rt->drainMicrotasks();
  }

  attached.reset();
  context->close();
  return 0;
}
