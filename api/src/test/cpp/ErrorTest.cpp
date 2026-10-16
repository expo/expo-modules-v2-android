#include "Fixture.h"

namespace {
  /** An error with a code of its own, as Kotlin subclasses `JavaScriptThrowable`. */
  class Refusal : public cpp::Exception {
  public:
    Refusal() : Exception("ERR_REFUSED", "refused") {
    }
  };

  struct NotAnException {};

  class Fragile : public cpp::SharedObject {
  public:
    static void define(cpp::ClassDefinition<Fragile>& cls) {
      cls.constructor<>();
      cls.function("crack", &Fragile::crack);
    }

    int crack() { throw std::runtime_error("cracked"); }
  };

  class Failing : public cpp::Module {
  public:
    void define(cpp::ModuleDefinition& def) override {
      def.function("plain", []() -> int { throw std::runtime_error("plain failure"); });
      def.function("coded", []() -> int { throw cpp::Exception("ERR_CODED", "coded failure"); });
      def.function("subclass", []() -> int { throw Refusal(); });
      def.function("uncoded", []() -> int { throw cpp::Exception("no code here"); });
      def.function("alien", []() -> int { throw NotAnException{}; });
      def.function("invalid", []() -> int { throw std::invalid_argument("bad input"); });
      def.function("fragile", []() { return std::make_shared<Fragile>(); });
      def.function("codedLater", [](cpp::Promise<int>) { throw cpp::Exception("ERR_LATER", "refused later"); });
      def.function("rejectLater", [](cpp::Promise<int> promise) { promise.reject("ERR_REJECTED", "rejected"); });
      def.function("coroutine", []() -> cpp::Task<int> { throw cpp::Exception("ERR_CORO", "coroutine failed"); co_return 1; });
      def.function("callback", [](js::Runtime& rt, js::Function callback) { callback.call(rt); });
      def.sharedClass<Fragile>();
    }
  };

  struct Env : JsEnv {
    Env() { context().emplace<Failing>("Failing"); }

    /** The fields of the error a call throws, as `code|message|isError`. */
    std::string errorOfCall(const std::string& call) {
      return eval(
        "(() => { try { " + call + "; return 'no-throw'; } "
        "catch (e) { return e.code + '|' + e.message + '|' + (e instanceof Error); } })()"
      );
    }

    std::string settled(const std::string& call) {
      exec("globalThis.out = null;" + call + ".then(() => { globalThis.out = 'resolved'; }, (e) => { globalThis.out = e.code + '|' + e.message; });");
      pumpUntil("globalThis.out !== null");
      return eval("globalThis.out");
    }
  };
} // namespace

TEST(error_a_std_exception_is_an_error_with_its_message_and_no_code) {
  Env env;
  CHECK_EQ(env.errorOfCall("expo.modules.Failing.plain()"), "undefined|plain failure|true");
  CHECK_EQ(env.errorOfCall("expo.modules.Failing.invalid()"), "undefined|bad input|true");
}

TEST(error_an_exception_with_a_code_reports_it) {
  Env env;
  CHECK_EQ(env.errorOfCall("expo.modules.Failing.coded()"), "ERR_CODED|coded failure|true");
}

TEST(error_a_subclass_of_exception_reports_its_code) {
  Env env;
  CHECK_EQ(env.errorOfCall("expo.modules.Failing.subclass()"), "ERR_REFUSED|refused|true");
}

TEST(error_an_exception_without_a_code_has_no_code_property) {
  Env env;
  CHECK_EQ(env.codeOf("expo.modules.Failing.uncoded()"), "no code");
  CHECK_EQ(env.errorOfCall("expo.modules.Failing.uncoded()"), "undefined|no code here|true");
}

TEST(error_something_that_is_not_an_exception_is_still_an_error) {
  Env env;
  CHECK_CONTAINS(env.errorOf("expo.modules.Failing.alien()"), "not an exception");
}

TEST(error_a_shared_object_method_throws_the_same_kind_of_error) {
  Env env;
  CHECK_EQ(env.errorOfCall("expo.modules.Failing.fragile().crack()"), "undefined|cracked|true");
}

TEST(error_a_javascript_error_from_a_callback_passes_through_unchanged) {
  Env env;
  CHECK_EQ(env.errorOf("expo.modules.Failing.callback(() => { throw new TypeError('from js'); })"), "from js");
  CHECK_EQ(
    env.eval("(() => { try { expo.modules.Failing.callback(() => { throw new TypeError('x'); }); } catch (e) { return e instanceof TypeError; } })()"),
    "true"
  );
}

TEST(error_a_function_that_throws_after_taking_a_promise_rejects_it) {
  Env env;
  CHECK_EQ(env.settled("expo.modules.Failing.codedLater()"), "ERR_LATER|refused later");
}

TEST(error_a_promise_can_be_rejected_with_a_code) {
  Env env;
  CHECK_EQ(env.settled("expo.modules.Failing.rejectLater()"), "ERR_REJECTED|rejected");
}

TEST(error_a_coroutine_that_throws_rejects_the_promise) {
  Env env;
  CHECK_EQ(env.settled("expo.modules.Failing.coroutine()"), "ERR_CORO|coroutine failed");
}

TEST(error_an_async_export_with_a_bad_argument_throws_at_the_call) {
  class Async : public cpp::Module {
  public:
    void define(cpp::ModuleDefinition& def) override {
      def.function("later", [](int, cpp::Promise<int> promise) { promise.resolve(1); });
    }
  };

  JsEnv env;
  env.context().emplace<Async>("Async");
  CHECK_EQ(env.errorOf("expo.modules.Async.later('x')"), "Invalid argument 1 of 'later': expected a number, got a string");
  CHECK_EQ(env.errorOf("expo.modules.Async.later()"), "later expects 1 argument(s), but received 0");
}
