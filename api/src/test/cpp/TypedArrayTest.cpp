#include "Fixture.h"

#include <cstring>

namespace {
  class Bytes : public cpp::Module {
  public:
    void define(cpp::ModuleDefinition& def) override {
      def.function("fill", [](cpp::TypedArray array, int value) { std::memset(array.data(), value, array.byteLength()); });
      def.function("sum", [](cpp::TypedArray array) {
        int sum = 0;
        for (const uint8_t byte: array.bytes()) {
          sum += byte;
        }
        return sum;
      });
      def.function("firstTwo", [](cpp::TypedArray array) {
        uint8_t bytes[2];
        array.read(bytes, 0, 2);
        return std::to_string(bytes[0]) + "," + std::to_string(bytes[1]);
      });
      def.function("byteLength", [](cpp::TypedArray array) { return array.byteLength(); });
      def.function("copy", [](cpp::TypedArray output, cpp::TypedArray input) {
        std::memcpy(output.data(), input.data(), std::min(output.byteLength(), input.byteLength()));
      });
      def.function("same", [](cpp::TypedArray array) { return array; });
      def.function("lengthOrMinusOne", [](std::optional<cpp::TypedArray> array) {
        return array.has_value() ? static_cast<int>(array->byteLength()) : -1;
      });
      def.function("doubles", [](cpp::TypedArray array) {
        double total = 0;
        for (const double value: array.as<double>()) {
          total += value;
        }
        return total;
      });
      def.function("write", [](cpp::TypedArray array, int position) {
        const uint8_t data[2] = {9, 8};
        array.write(data, static_cast<size_t>(position), 2);
      });
    }
  };

  struct Env : JsEnv {
    Env() { context().emplace<Bytes>("Bytes"); }
  };
} // namespace

TEST(typed_array_a_write_lands_in_the_javascript_array) {
  Env env;
  CHECK_EQ(env.eval("const a = new Uint8Array(4); expo.modules.Bytes.fill(a, 7); a.join()"), "7,7,7,7");
}

TEST(typed_array_a_view_writes_only_its_own_window_of_the_buffer) {
  Env env;
  CHECK_EQ(
    env.eval("const b = new Uint8Array(8); expo.modules.Bytes.fill(new Uint8Array(b.buffer, 2, 3), 1); b.join()"),
    "0,0,1,1,1,0,0,0"
  );
}

TEST(typed_array_reads_see_the_javascript_bytes) {
  Env env;
  CHECK_EQ(env.eval("expo.modules.Bytes.sum(new Uint8Array([1, 2, 3]))"), "6");
  CHECK_EQ(env.eval("expo.modules.Bytes.firstTwo(new Uint8Array([5, 6, 7]))"), "5,6");
}

TEST(typed_array_every_kind_of_view_crosses_by_its_byte_length) {
  Env env;
  CHECK_EQ(env.eval("expo.modules.Bytes.byteLength(new Float64Array(2))"), "16");
  CHECK_EQ(env.eval("expo.modules.Bytes.byteLength(new Int16Array(3))"), "6");
  CHECK_EQ(env.eval("expo.modules.Bytes.byteLength(new DataView(new ArrayBuffer(5)))"), "5");
  CHECK_EQ(env.eval("expo.modules.Bytes.byteLength(new Uint8Array(new ArrayBuffer(10), 4, 3))"), "3");
}

TEST(typed_array_two_typed_arrays_can_cross_in_one_call) {
  Env env;
  CHECK_EQ(env.eval("const out = new Uint8Array(3); expo.modules.Bytes.copy(out, new Uint8Array([1, 2, 3])); out.join()"), "1,2,3");
}

TEST(typed_array_a_returned_typed_array_is_the_same_javascript_object) {
  Env env;
  CHECK_EQ(env.eval("const a = new Int16Array(1); expo.modules.Bytes.same(a) === a"), "true");
}

TEST(typed_array_an_optional_typed_array_takes_null) {
  Env env;
  CHECK_EQ(env.eval("expo.modules.Bytes.lengthOrMinusOne(null)"), "-1");
  CHECK_EQ(env.eval("expo.modules.Bytes.lengthOrMinusOne()"), "-1");
  CHECK_EQ(env.eval("expo.modules.Bytes.lengthOrMinusOne(new Uint8Array(3))"), "3");
}

TEST(typed_array_a_value_that_is_not_a_typed_array_or_a_data_view_is_rejected) {
  Env env;
  for (const char* value: {"[1, 2]", "new ArrayBuffer(2)", "{ buffer: new ArrayBuffer(2) }"}) {
    const std::string message = env.errorOf(std::string("expo.modules.Bytes.byteLength(") + value + ")");
    CHECK_CONTAINS(message, "typed array");
  }
  CHECK_CONTAINS(env.errorOf("expo.modules.Bytes.byteLength(new ArrayBuffer(2))"), "got an ArrayBuffer. Pass a view of the buffer");
}

TEST(typed_array_the_bytes_can_be_read_as_numbers) {
  Env env;
  CHECK_EQ(env.eval("expo.modules.Bytes.doubles(new Float64Array([1.5, 2.5]))"), "4");
  CHECK_CONTAINS(env.errorOf("expo.modules.Bytes.doubles(new Uint8Array(12))"), "whole, aligned number of elements");
}

TEST(typed_array_a_range_outside_the_array_is_refused) {
  Env env;
  CHECK_EQ(env.eval("const a = new Uint8Array(4); expo.modules.Bytes.write(a, 1); a.join()"), "0,9,8,0");
  CHECK_CONTAINS(env.errorOf("expo.modules.Bytes.write(new Uint8Array(4), 3)"), "outside the typed array");
}

TEST(typed_array_a_view_that_claims_more_than_its_buffer_holds_is_rejected) {
  Env env;
  // An own property shadows the getter on the prototype, so what a view says about itself is only
  // what it says.
  env.exec("globalThis.forged = (props) => Object.defineProperties(new Uint8Array(4), props);");

  const char* const lies[] = {
    "{ byteLength: { value: 1e9 } }",
    "{ byteLength: { value: -1 } }",
    "{ byteLength: { value: NaN } }",
    "{ byteLength: { value: Infinity } }",
    "{ byteLength: { value: 1.5 } }",
    "{ byteOffset: { value: 3 } }",
    "{ byteOffset: { value: -2 } }",
    "{ byteOffset: { value: 1e300 } }",
    "{ byteOffset: { value: 1e19 }, byteLength: { value: 0 } }",
    "{ byteLength: { value: '4' } }",
    "{ byteOffset: { value: undefined } }",
    "{ buffer: { value: {} } }",
    "{ buffer: { value: 5 } }",
    "{ buffer: { value: new Uint8Array(4) } }",
  };
  for (const char* const lie: lies) {
    const std::string source = std::string("expo.modules.Bytes.fill(forged(") + lie + "), 1)";
    CHECK_CONTAINS(env.errorOf(source), "this view's");
  }
}

TEST(typed_array_a_view_whose_properties_fit_its_buffer_is_still_accepted) {
  Env env;
  CHECK_EQ(env.eval("const a = Object.defineProperty(new Uint8Array(4), 'byteLength', { value: 2 }); expo.modules.Bytes.fill(a, 5); a.join()"), "5,5,0,0");
  CHECK_EQ(env.eval("expo.modules.Bytes.byteLength(new Uint8Array(new ArrayBuffer(0)))"), "0");
}
