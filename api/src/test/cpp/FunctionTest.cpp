#include "Fixture.h"

namespace {
  int multiply(int a, int b) { return a * b; }

  class Basics : public cpp::Module {
  public:
    void define(cpp::ModuleDefinition& def) override {
      def.function("add", [](int a, int b) { return a + b; });
      def.function("multiply", &multiply);
      def.function("concat", [](const std::string& a, std::string b) { return a + b; });
      def.function("not", [](bool value) { return !value; });
      def.function("half", [](double value) { return value / 2; });
      def.function("nothing", [] {});
      def.function("greet", [](std::string name, std::optional<std::string> greeting, std::optional<std::string> punctuation) {
        return greeting.value_or("Hello") + ", " + name + punctuation.value_or(".");
      });
      def.function("scaled", this, &Basics::scale);
      def.function("calls", this, &Basics::calls);
      def.function("count", [this]() mutable { return ++counter_; });
      def.function("byte", [](uint8_t value) { return value; });
      def.function("big", [](int64_t value) { return value; });
      def.function("unsigned", [](uint32_t value) { return value; });
      def.function("isArray", [](js::Runtime& rt, const js::Value& value) {
        return value.isObject() && value.getObject(rt).isArray(rt);
      });
      def.function("callback", [](js::Runtime& rt, js::Function callback, int argument) {
        return callback.call(rt, argument).getNumber() + 1;
      });
      def.function("objectKeys", [](js::Runtime& rt, js::Object object) { return object.getPropertyNames(rt).size(rt); });
      def.function("echoValue", [](js::Value value) { return value; });
      def.function("textView", []() -> std::string_view { return "view"; });
      def.function("cString", []() -> const char* { return "c-string"; });
      def.function("nullString", []() -> const char* { return nullptr; });
    }

  private:
    double scale(double value) const { return value * 3; }

    int calls() { return ++counter_ * 10; }

    int counter_ = 0;
  };
} // namespace

TEST(function_adds_numbers) {
  JsEnv env;
  env.context().emplace<Basics>("Basics");
  CHECK_EQ(env.eval("expo.modules.Basics.add(1, 2)"), "3");
  CHECK_EQ(env.eval("expo.modules.Basics.add(-5, 2.9)"), "-3");
}

TEST(function_exports_a_function_pointer) {
  JsEnv env;
  env.context().emplace<Basics>("Basics");
  CHECK_EQ(env.eval("expo.modules.Basics.multiply(6, 7)"), "42");
}

TEST(function_takes_strings_by_value_and_by_const_reference) {
  JsEnv env;
  env.context().emplace<Basics>("Basics");
  CHECK_EQ(env.eval("expo.modules.Basics.concat('ab', 'cd')"), "abcd");
  CHECK_EQ(env.eval("expo.modules.Basics.concat('héllo ', 'wörld ☃')"), "héllo wörld ☃");
}

TEST(function_takes_and_returns_booleans_and_doubles) {
  JsEnv env;
  env.context().emplace<Basics>("Basics");
  CHECK_EQ(env.eval("expo.modules.Basics.not(true)"), "false");
  CHECK_EQ(env.eval("expo.modules.Basics.half(5)"), "2.5");
}

TEST(function_returning_void_returns_undefined) {
  JsEnv env;
  env.context().emplace<Basics>("Basics");
  CHECK_EQ(env.eval("typeof expo.modules.Basics.nothing()"), "undefined");
}

TEST(function_trailing_optional_arguments_can_be_left_out) {
  JsEnv env;
  env.context().emplace<Basics>("Basics");
  CHECK_EQ(env.eval("expo.modules.Basics.greet('Expo')"), "Hello, Expo.");
  CHECK_EQ(env.eval("expo.modules.Basics.greet('Expo', 'Hey')"), "Hey, Expo.");
  CHECK_EQ(env.eval("expo.modules.Basics.greet('Expo', 'Hey', '!')"), "Hey, Expo!");
  CHECK_EQ(env.eval("expo.modules.Basics.greet('Expo', null, '?')"), "Hello, Expo?");
}

TEST(function_a_non_optional_argument_cannot_be_left_out) {
  JsEnv env;
  env.context().emplace<Basics>("Basics");
  CHECK_CONTAINS(env.errorOf("expo.modules.Basics.greet()"), "greet expects 3 argument(s), but received 0");
  CHECK_CONTAINS(env.errorOf("expo.modules.Basics.add(1)"), "add expects 2 argument(s), but received 1");
}

TEST(function_too_many_arguments_are_rejected) {
  JsEnv env;
  env.context().emplace<Basics>("Basics");
  CHECK_CONTAINS(env.errorOf("expo.modules.Basics.greet('a', 'b', 'c', 'd')"), "greet expects 3 argument(s), but received 4");
  CHECK_CONTAINS(env.errorOf("expo.modules.Basics.nothing(1)"), "nothing expects 0 argument(s), but received 1");
}

TEST(function_a_wrong_argument_type_names_the_function_and_the_argument) {
  JsEnv env;
  env.context().emplace<Basics>("Basics");
  CHECK_EQ(
    env.errorOf("expo.modules.Basics.add(1, 'two')"),
    "Invalid argument 2 of 'add': expected a number, got a string"
  );
  CHECK_EQ(
    env.errorOf("expo.modules.Basics.concat({}, 'x')"),
    "Invalid argument 1 of 'concat': expected a string, got an object"
  );
  CHECK_EQ(
    env.errorOf("expo.modules.Basics.not(1)"),
    "Invalid argument 1 of 'not': expected a boolean, got a number"
  );
  CHECK_CONTAINS(env.errorOf("expo.modules.Basics.add(null, 1)"), "expected a number, got null");
  CHECK_CONTAINS(env.errorOf("expo.modules.Basics.add(undefined, 1)"), "expected a number, got undefined");
}

TEST(function_integers_are_checked_against_their_range) {
  JsEnv env;
  env.context().emplace<Basics>("Basics");
  CHECK_EQ(env.eval("expo.modules.Basics.byte(255)"), "255");
  CHECK_CONTAINS(env.errorOf("expo.modules.Basics.byte(256)"), "expected an integer between 0 and 255, got 256");
  CHECK_CONTAINS(env.errorOf("expo.modules.Basics.byte(-1)"), "expected an integer between 0 and 255");
  CHECK_CONTAINS(env.errorOf("expo.modules.Basics.add(NaN, 1)"), "expected an integer");
  CHECK_CONTAINS(env.errorOf("expo.modules.Basics.add(Infinity, 1)"), "expected an integer");
  CHECK_CONTAINS(env.errorOf("expo.modules.Basics.add(2147483648, 1)"), "expected an integer between -2147483648 and 2147483647");
  CHECK_EQ(env.eval("expo.modules.Basics.unsigned(4294967295)"), "4294967295");
  CHECK_CONTAINS(env.errorOf("expo.modules.Basics.unsigned(4294967296)"), "expected an integer");
}

TEST(function_integers_keep_their_whole_part) {
  JsEnv env;
  env.context().emplace<Basics>("Basics");
  CHECK_EQ(env.eval("expo.modules.Basics.add(1.9, 2.9)"), "3");
  CHECK_EQ(env.eval("expo.modules.Basics.add(-1.9, 0)"), "-1");
}

TEST(function_a_fraction_is_dropped_the_same_way_for_every_integer_type) {
  JsEnv env;
  env.context().emplace<Basics>("Basics");
  // Toward zero, so a fraction below zero is zero for a type that has no negative numbers too.
  CHECK_EQ(env.eval("expo.modules.Basics.unsigned(-0.5)"), "0");
  CHECK_EQ(env.eval("expo.modules.Basics.byte(-0.9)"), "0");
  CHECK_EQ(env.eval("expo.modules.Basics.add(-0.5, 0)"), "0");
  CHECK_EQ(env.eval("expo.modules.Basics.big(-0.5)"), "0");
  CHECK_EQ(env.eval("expo.modules.Basics.byte(255.9)"), "255");
  // The edge itself is still the edge.
  CHECK_CONTAINS(env.errorOf("expo.modules.Basics.byte(-1)"), "expected an integer");
  CHECK_CONTAINS(env.errorOf("expo.modules.Basics.byte(-1.0000001)"), "expected an integer");
  CHECK_CONTAINS(env.errorOf("expo.modules.Basics.byte(256)"), "expected an integer");
  CHECK_CONTAINS(env.errorOf("expo.modules.Basics.unsigned(-1)"), "expected an integer");
  CHECK_EQ(env.eval("expo.modules.Basics.add(-2147483648.5, 0)"), "-2147483648");
  CHECK_CONTAINS(env.errorOf("expo.modules.Basics.add(-2147483649, 0)"), "expected an integer");
  CHECK_EQ(env.eval("expo.modules.Basics.big(-(2 ** 63)) === -(2 ** 63)"), "true");
}

TEST(function_64_bit_integers_are_exact_up_to_2_pow_53) {
  JsEnv env;
  env.context().emplace<Basics>("Basics");
  CHECK_EQ(env.eval("expo.modules.Basics.big(9007199254740991)"), "9007199254740991");
  CHECK_EQ(env.eval("expo.modules.Basics.big(-9007199254740991)"), "-9007199254740991");
  CHECK_CONTAINS(env.errorOf("expo.modules.Basics.big(9223372036854775808)"), "expected an integer");
}

TEST(function_a_method_is_bound_to_its_module) {
  JsEnv env;
  env.context().emplace<Basics>("Basics");
  CHECK_EQ(env.eval("expo.modules.Basics.scaled(2)"), "6");
  CHECK_EQ(env.eval("expo.modules.Basics.calls() + ',' + expo.modules.Basics.calls()"), "10,20");
}

TEST(function_a_lambda_keeps_its_own_state) {
  JsEnv env;
  env.context().emplace<Basics>("Basics");
  CHECK_EQ(env.eval("const m = expo.modules.Basics; [m.count(), m.count(), m.count()].join()"), "1,2,3");
}

TEST(function_receives_the_runtime_and_takes_no_argument_for_it) {
  JsEnv env;
  env.context().emplace<Basics>("Basics");
  CHECK_EQ(env.eval("expo.modules.Basics.isArray([1])"), "true");
  CHECK_EQ(env.eval("expo.modules.Basics.isArray({})"), "false");
  CHECK_EQ(env.eval("expo.modules.Basics.isArray.length"), "1");
}

TEST(function_a_js_value_parameter_accepts_anything_and_can_be_left_out) {
  JsEnv env;
  env.context().emplace<Basics>("Basics");
  CHECK_EQ(env.eval("String(expo.modules.Basics.echoValue(42))"), "42");
  CHECK_EQ(env.eval("expo.modules.Basics.echoValue('s')"), "s");
  CHECK_EQ(env.eval("expo.modules.Basics.echoValue()"), "undefined");
  CHECK_EQ(env.eval("const o = {}; expo.modules.Basics.echoValue(o) === o"), "true");
}

TEST(function_calls_back_into_a_javascript_function) {
  JsEnv env;
  env.context().emplace<Basics>("Basics");
  CHECK_EQ(env.eval("expo.modules.Basics.callback((n) => n * 10, 4)"), "41");
  CHECK_EQ(
    env.errorOf("expo.modules.Basics.callback(1, 4)"),
    "Invalid argument 1 of 'callback': expected a function, got a number"
  );
}

TEST(function_a_js_object_parameter_is_checked) {
  JsEnv env;
  env.context().emplace<Basics>("Basics");
  CHECK_EQ(env.eval("expo.modules.Basics.objectKeys({ a: 1, b: 2 })"), "2");
  CHECK_CONTAINS(env.errorOf("expo.modules.Basics.objectKeys(5)"), "expected an object, got a number");
}

TEST(function_results_may_be_views_and_c_strings) {
  JsEnv env;
  env.context().emplace<Basics>("Basics");
  CHECK_EQ(env.eval("expo.modules.Basics.textView()"), "view");
  CHECK_EQ(env.eval("expo.modules.Basics.cString()"), "c-string");
  CHECK_EQ(env.eval("expo.modules.Basics.nullString()"), "null");
}

TEST(function_the_js_function_has_the_name_and_length_of_the_export) {
  JsEnv env;
  env.context().emplace<Basics>("Basics");
  CHECK_EQ(env.eval("expo.modules.Basics.add.name + '/' + expo.modules.Basics.add.length"), "add/2");
  CHECK_EQ(env.eval("expo.modules.Basics.greet.length"), "3");
  CHECK_EQ(env.eval("expo.modules.Basics.nothing.length"), "0");
}

TEST(function_exports_are_enumerable_and_the_emitter_members_are_not) {
  JsEnv env;
  env.context().emplace<Basics>("Basics");
  CHECK_EQ(env.eval("Object.keys(expo.modules.Basics).includes('add')"), "true");
  CHECK_EQ(env.eval("Object.keys(expo.modules.Basics).includes('addListener')"), "false");
  CHECK_EQ(env.eval("typeof expo.modules.Basics.addListener"), "function");
}

TEST(function_a_name_may_not_be_reserved_or_repeated) {
  class Reserved : public cpp::Module {
  public:
    void define(cpp::ModuleDefinition& def) override {
      def.function("addListener", [] {});
    }
  };
  class Repeated : public cpp::Module {
  public:
    void define(cpp::ModuleDefinition& def) override {
      def.function("twice", [] {});
      def.constant("twice", 1);
    }
  };

  JsEnv env;
  try {
    env.context().emplace<Reserved>("Reserved");
    CHECK(false);
  } catch (const std::invalid_argument& error) {
    CHECK_CONTAINS(error.what(), "Export 'addListener' of 'Reserved' is reserved");
  }
  try {
    env.context().emplace<Repeated>("Repeated");
    CHECK(false);
  } catch (const std::invalid_argument& error) {
    CHECK_CONTAINS(error.what(), "Export 'twice' is already declared in 'Repeated'");
  }
}

TEST(function_module_names_are_unique_within_a_context) {
  JsEnv env;
  env.context().emplace<Basics>("Basics");
  CHECK_THROWS(env.context().emplace<Basics>("Basics"));
}

TEST(function_an_unknown_module_is_undefined_and_the_namespace_lists_the_known_ones) {
  JsEnv env;
  env.context().emplace<Basics>("Basics");
  CHECK_EQ(env.eval("typeof expo.modules.Missing"), "undefined");
  CHECK_EQ(env.eval("Object.keys(expo.modules).join()"), "Basics");
}

TEST(function_the_modules_object_is_read_only) {
  JsEnv env;
  env.context().emplace<Basics>("Basics");
  CHECK_CONTAINS(env.errorOf("expo.modules.Other = 1"), "expo.modules is read-only (attempted to set 'Other')");
}

TEST(function_a_module_object_is_built_once) {
  JsEnv env;
  env.context().emplace<Basics>("Basics");
  CHECK_EQ(env.eval("expo.modules.Basics === expo.modules.Basics"), "true");
}
