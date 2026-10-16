#include "Fixture.h"

namespace {
  class Types : public cpp::Module {
  public:
    void define(cpp::ModuleDefinition& def) override {
      def.function("strings", [](std::vector<std::string> values) {
        std::reverse(values.begin(), values.end());
        return values;
      });
      def.function("sum", [](const std::vector<double>& values) {
        double total = 0;
        for (const double value: values) {
          total += value;
        }
        return total;
      });
      def.function("nested", [](std::vector<std::vector<int>> rows) { return rows; });
      def.function("triple", [](std::array<int, 3> values) { return std::array<int, 3>{values[2], values[1], values[0]}; });
      def.function("pair", [](std::pair<std::string, int> value) { return std::pair<int, std::string>(value.second, value.first); });
      def.function("tuple", [](std::tuple<int, std::string, bool> value) { return value; });
      def.function("set", [](std::set<int> values) { return values; });
      def.function("hashSet", [](std::unordered_set<std::string> values) { return values.size(); });
      def.function("map", [](std::map<std::string, int> values) {
        for (auto& entry: values) {
          entry.second *= 2;
        }
        return values;
      });
      def.function("hashMap", [](std::unordered_map<std::string, std::vector<std::string>> values) { return values.size(); });
      def.function("maybe", [](std::optional<int> value) { return value; });
      def.function("variant", [](std::variant<int, std::string, bool> value) { return value; });
      def.function("variantName", [](std::variant<int, std::string> value) {
        return std::visit(
          [](const auto& held) -> std::string {
            return std::is_same_v<std::decay_t<decltype(held)>, int> ? "int" : "string";
          },
          value
        );
      });
      def.function("variantNumber", [](std::variant<int, double> value) -> std::string {
        return std::holds_alternative<int>(value) ? "int" : "double";
      });
      def.function("seconds", [](std::chrono::seconds value) { return value; });
      def.function("millis", [](std::chrono::milliseconds value) { return value.count(); });
      def.function("fromMillis", [](int milliseconds) { return std::chrono::milliseconds(milliseconds); });
      def.function("path", [](std::filesystem::path value) { return value / "child"; });
      def.function("bytes", [](std::vector<uint8_t> bytes) {
        for (uint8_t& byte: bytes) {
          byte = static_cast<uint8_t>(byte + 1);
        }
        return bytes;
      });
      def.function("list", [](std::vector<int> numbers) { return numbers; });
      def.function("any", [](cpp::Any value) { return value; });
      def.function("anyKind", [](cpp::Any value) -> std::string {
        if (value.isNull()) return "null";
        if (value.isBool()) return "bool";
        if (value.isNumber()) return "number";
        if (value.isString()) return "string";
        if (value.isArray()) return "array";
        return "object";
      });
    }
  };

  struct Env : JsEnv {
    Env() { context().emplace<Types>("Types"); }
  };
} // namespace

TEST(converter_a_vector_is_a_javascript_array) {
  Env env;
  CHECK_EQ(env.eval("JSON.stringify(expo.modules.Types.strings(['a', 'b', 'c']))"), R"(["c","b","a"])");
  CHECK_EQ(env.eval("expo.modules.Types.sum([1, 2.5, 3])"), "6.5");
  CHECK_EQ(env.eval("expo.modules.Types.sum([])"), "0");
  CHECK_EQ(env.eval("Array.isArray(expo.modules.Types.strings([]))"), "true");
}

TEST(converter_vectors_nest) {
  Env env;
  CHECK_EQ(env.eval("JSON.stringify(expo.modules.Types.nested([[1, 2], [], [3]]))"), "[[1,2],[],[3]]");
}

TEST(converter_a_vector_rejects_what_is_not_an_array) {
  Env env;
  CHECK_EQ(
    env.errorOf("expo.modules.Types.strings('abc')"),
    "Invalid argument 1 of 'strings': expected an array, got a string"
  );
  CHECK_CONTAINS(env.errorOf("expo.modules.Types.strings([1])"), "expected a string, got a number");
}

TEST(converter_a_fixed_size_array_needs_exactly_that_many_elements) {
  Env env;
  CHECK_EQ(env.eval("JSON.stringify(expo.modules.Types.triple([1, 2, 3]))"), "[3,2,1]");
  CHECK_CONTAINS(env.errorOf("expo.modules.Types.triple([1, 2])"), "expected an array of 3 element(s), got 2");
}

TEST(converter_pairs_and_tuples_are_fixed_length_arrays) {
  Env env;
  CHECK_EQ(env.eval("JSON.stringify(expo.modules.Types.pair(['x', 7]))"), R"([7,"x"])");
  CHECK_EQ(env.eval("JSON.stringify(expo.modules.Types.tuple([1, 'two', true]))"), R"([1,"two",true])");
  CHECK_CONTAINS(env.errorOf("expo.modules.Types.tuple([1, 'two'])"), "expected an array of 3 element(s), got 2");
}

TEST(converter_sets_hold_unique_values) {
  Env env;
  CHECK_EQ(env.eval("JSON.stringify(expo.modules.Types.set([3, 1, 3, 2]))"), "[1,2,3]");
  CHECK_EQ(env.eval("expo.modules.Types.hashSet(['a', 'b', 'a'])"), "2");
}

TEST(converter_a_map_is_a_javascript_object) {
  Env env;
  CHECK_EQ(env.eval("JSON.stringify(expo.modules.Types.map({ a: 1, b: 2 }))"), R"({"a":2,"b":4})");
  CHECK_EQ(env.eval("expo.modules.Types.hashMap({ x: ['a'], y: [] })"), "2");
  CHECK_CONTAINS(env.errorOf("expo.modules.Types.map({ a: 'x' })"), "expected a number, got a string");
  CHECK_CONTAINS(env.errorOf("expo.modules.Types.map(3)"), "expected an object, got a number");
}

TEST(converter_map_keys_may_be_long_and_not_ascii) {
  Env env;
  const std::string longKey(300, 'k');
  // A std::map orders its keys by byte, so the result is read by key, not by position.
  CHECK_EQ(
    env.eval(
      "const o = { 'héllo': 1, '日本語': 2, '" + longKey + "': 3 }; const out = expo.modules.Types.map(o);"
      "[Object.keys(out).length, out['héllo'], out['日本語'], out['" + longKey + "']].join()"
    ),
    "3,2,4,6"
  );
}

TEST(converter_maps_with_many_keys_keep_every_one) {
  Env env;
  CHECK_EQ(
    env.eval(
      "const o = {}; for (let i = 0; i < 1000; i++) { o['key' + i] = i; }"
      "const out = expo.modules.Types.map(o); Object.keys(out).length + ':' + out.key999"
    ),
    "1000:1998"
  );
}

TEST(converter_a_map_key_may_look_like_a_number) {
  Env env;
  CHECK_EQ(env.eval("JSON.stringify(expo.modules.Types.map({ 1: 5, 20: 6 }))"), R"({"1":10,"20":12})");
}

TEST(converter_an_optional_is_null_or_a_value) {
  Env env;
  CHECK_EQ(env.eval("expo.modules.Types.maybe(5)"), "5");
  CHECK_EQ(env.eval("expo.modules.Types.maybe(null)"), "null");
  CHECK_EQ(env.eval("expo.modules.Types.maybe(undefined)"), "null");
  CHECK_EQ(env.eval("expo.modules.Types.maybe()"), "null");
  CHECK_EQ(env.eval("expo.modules.Types.maybe(0)"), "0");
  CHECK_CONTAINS(env.errorOf("expo.modules.Types.maybe('x')"), "expected a number, got a string");
}

TEST(converter_a_variant_takes_the_first_alternative_that_fits) {
  Env env;
  CHECK_EQ(env.eval("expo.modules.Types.variantName(3)"), "int");
  CHECK_EQ(env.eval("expo.modules.Types.variantName('3')"), "string");
  CHECK_EQ(env.eval("typeof expo.modules.Types.variant(true)"), "boolean");
  CHECK_EQ(env.eval("typeof expo.modules.Types.variant('s')"), "string");
  CHECK_CONTAINS(env.errorOf("expo.modules.Types.variantName({})"), "expected a number or a string, got an object");
}

TEST(converter_a_whole_number_is_an_int_and_a_fraction_a_double_in_a_variant_of_both) {
  Env env;
  CHECK_EQ(env.eval("expo.modules.Types.variantNumber(3)"), "int");
  CHECK_EQ(env.eval("expo.modules.Types.variantNumber(3.5)"), "double");
  CHECK_EQ(env.eval("expo.modules.Types.variantNumber(-0.25)"), "double");
  CHECK_EQ(env.eval("expo.modules.Types.variantNumber(1e300)"), "double");
  // Only when nothing else wants the number does an int take it, fraction dropped.
  CHECK_EQ(env.eval("expo.modules.Types.variantName(3.5)"), "int");
}

TEST(converter_a_duration_that_does_not_fit_its_count_is_an_error) {
  Env env;
  for (const char* const bad: {"NaN", "Infinity", "-Infinity", "1e300", "-1e300", "9.3e18"}) {
    CHECK_CONTAINS(env.errorOf(std::string("expo.modules.Types.seconds(") + bad + ")"), "expected an integer");
    CHECK_CONTAINS(env.errorOf(std::string("expo.modules.Types.millis(") + bad + ")"), "expected an integer");
  }
  CHECK_EQ(env.eval("expo.modules.Types.millis(-0.0004)"), "0");
  CHECK_EQ(env.eval("expo.modules.Types.seconds(2.9)"), "2");
}

TEST(converter_a_duration_is_a_number_of_seconds) {
  Env env;
  CHECK_EQ(env.eval("expo.modules.Types.seconds(90)"), "90");
  CHECK_EQ(env.eval("expo.modules.Types.millis(1.5)"), "1500");
  CHECK_EQ(env.eval("expo.modules.Types.fromMillis(2500)"), "2.5");
}

TEST(converter_a_path_is_a_string) {
  Env env;
  CHECK_EQ(env.eval("expo.modules.Types.path('/tmp/dir')"), "/tmp/dir/child");
}

TEST(converter_bytes_are_an_array_buffer) {
  Env env;
  CHECK_EQ(
    env.eval("const out = expo.modules.Types.bytes(new Uint8Array([1, 2, 255]).buffer); [out instanceof ArrayBuffer, new Uint8Array(out).join()].join('|')"),
    "true|2,3,0"
  );
}

TEST(converter_bytes_also_take_a_view_and_copy_only_its_window) {
  Env env;
  CHECK_EQ(
    env.eval("new Uint8Array(expo.modules.Types.bytes(new Uint8Array([9, 1, 2, 3, 9]).subarray(1, 4))).join()"),
    "2,3,4"
  );
  CHECK_EQ(env.eval("new Uint8Array(expo.modules.Types.bytes(new DataView(new ArrayBuffer(2)))).join()"), "1,1");
}

TEST(converter_bytes_reject_a_plain_array) {
  Env env;
  CHECK_CONTAINS(env.errorOf("expo.modules.Types.bytes([1, 2])"), "an ArrayBuffer, a typed array such as a Uint8Array, or a DataView");
}

TEST(converter_a_list_of_small_numbers_is_not_bytes) {
  Env env;
  CHECK_EQ(env.eval("JSON.stringify(expo.modules.Types.list([1, 2, 3]))"), "[1,2,3]");
}

TEST(converter_any_carries_json_like_values) {
  Env env;
  CHECK_EQ(env.eval("expo.modules.Types.any(null)"), "null");
  CHECK_EQ(env.eval("expo.modules.Types.any(undefined)"), "null");
  CHECK_EQ(env.eval("expo.modules.Types.any(true)"), "true");
  CHECK_EQ(env.eval("expo.modules.Types.any(2.5)"), "2.5");
  CHECK_EQ(env.eval("expo.modules.Types.any('s')"), "s");
  CHECK_EQ(
    env.eval("JSON.stringify(expo.modules.Types.any({ a: [1, 'x', { b: null }], c: false, d: 1.5 }))"),
    R"({"a":[1,"x",{"b":null}],"c":false,"d":1.5})"
  );
}

TEST(converter_any_keeps_the_order_of_an_objects_keys) {
  Env env;
  CHECK_EQ(env.eval("Object.keys(expo.modules.Types.any({ z: 1, a: 2, m: 3 })).join()"), "z,a,m");
}

TEST(converter_any_reports_the_kind_it_holds) {
  Env env;
  CHECK_EQ(env.eval("[null, true, 1, 's', [], {}].map((v) => expo.modules.Types.anyKind(v)).join()"), "null,bool,number,string,array,object");
}

TEST(converter_any_rejects_a_function_and_a_cycle) {
  Env env;
  CHECK_CONTAINS(env.errorOf("expo.modules.Types.any(() => 1)"), "got a function");
  CHECK_CONTAINS(env.errorOf("const o = {}; o.self = o; expo.modules.Types.any(o)"), "is it cyclic?");
}

TEST(converter_a_view_that_claims_more_than_its_buffer_holds_is_rejected_as_bytes_too) {
  Env env;
  CHECK_CONTAINS(
    env.errorOf("expo.modules.Types.bytes(Object.defineProperty(new Uint8Array(4), 'byteLength', { value: 1e9 }))"),
    "this view's"
  );
  CHECK_CONTAINS(
    env.errorOf("expo.modules.Types.bytes(Object.defineProperty(new Uint8Array(4), 'byteOffset', { value: 99 }))"),
    "this view's"
  );
}
