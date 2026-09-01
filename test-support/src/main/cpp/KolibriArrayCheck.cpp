#include "KolibriArrayCheck.h"

#include <array>
#include <span>
#include <string_view>
#include <utility>
#include <vector>

#include <kolibri/array.h>
#include <kolibri/box.h>
#include <kolibri/class.h>
#include <kolibri/env.h>
#include <kolibri/exception.h>
#include <kolibri/native_method.h>
#include <kolibri/string_utils.h>
#include <kolibri/utils.h>

// Compile coverage: force every member of both JArray specializations and PinnedArray.
template struct expo::kolibri::JArray<jint>;
template struct expo::kolibri::JArray<jdouble>;
template struct expo::kolibri::JArray<expo::kolibri::JString>;
template class expo::kolibri::PinnedArray<jfloat>;
template class expo::kolibri::PinnedArray<const jfloat>;

namespace expo::modules::v2 {
  namespace kolibri = expo::kolibri;

  namespace {
    // The full-signature contract native methods are registered with.
    constexpr auto kSumSignature = kolibri::jni_signature<jdouble, jdoubleArray>();
    static_assert(std::string_view{kSumSignature.data()} == "([D)D");
    constexpr auto kScaleSignature = kolibri::jni_signature<jdoubleArray, jdoubleArray, jdouble>();
    static_assert(std::string_view{kScaleSignature.data()} == "([DD)[D");
    constexpr auto kNamesSignature =
        kolibri::jni_signature<kolibri::Ref<kolibri::JArray<kolibri::JString>>>();
    static_assert(std::string_view{kNamesSignature.data()} == "()[Ljava/lang/String;");

    struct JStringOps : kolibri::JavaClass<JStringOps> {
      static constexpr std::string_view descriptor = "java/lang/String";
      static constexpr Method<"toCharArray", kolibri::Ref<kolibri::JCharArray>()> toCharArray{};
    };

    struct JArrays : kolibri::JavaClass<JArrays> {
      static constexpr std::string_view descriptor = "java/util/Arrays";
      static constexpr StaticMethod<"copyOf", kolibri::Ref<kolibri::JIntArray>(jintArray, jint)>
          copyOf{};
    };

    double fixtureSum(JNIEnv* env, jdoubleArray values) {
      kolibri::UnownedRef<kolibri::JDoubleArray> array{values};
      double total = 0;
      for (const jdouble value: array->pinReadOnly(env)) {
        total += value;
      }
      return total;
    }

    jdoubleArray fixtureScale(JNIEnv* env, jdoubleArray values, jdouble factor) {
      kolibri::UnownedRef<kolibri::JDoubleArray> array{values};
      std::vector<jdouble> scaled = array->toVector(env);
      for (jdouble& value: scaled) {
        value *= factor;
      }
      auto result = kolibri::JDoubleArray::create(env, std::span<const jdouble>{scaled});
      return reinterpret_cast<jdoubleArray>(result.release());
    }

    // Mutates the caller's array in place: the pin's destructor commits the writes.
    void fixtureFill(JNIEnv* env, jintArray values, jint start) {
      kolibri::UnownedRef<kolibri::JIntArray> array{values};
      auto pinned = array->pin(env);
      for (jsize i = 0; i < pinned.size(); i++) {
        pinned[i] = start + i;
      }
    }
  }

  void bindKolibriArrayFixture() {
    JNIEnv* env = kolibri::getEnv();
    kolibri::registerNative(env, "io/github/expo/modules/v2/testapp/KolibriArrayFixture")
      .method<&fixtureSum>("sum")
      .method<&fixtureScale>("scale")
      .method<&fixtureFill>("fill")
      .commit();
  }

  std::string runKolibriArrayCheck() {
    using namespace expo::kolibri;
    JNIEnv* env = getEnv();
    const auto fail = [](const std::string& what) { return "failed: " + what; };

    // Creation: a fresh array has the requested size and is zeroed.
    auto ints = JIntArray::create(env, 5);
    if (ints->size(env) != 5) {
      return fail("create(size)/size");
    }
    if (ints->toVector(env) != std::vector<jint>(5, 0)) {
      return fail("a new array is not zeroed");
    }

    // Creation from a span round-trips exactly (endianness/width canary included).
    const std::array<jdouble, 4> source{0.5, 1.5, -2.5, 1.5e300};
    auto doubles = JDoubleArray::create(env, std::span<const jdouble>{source});
    if (doubles->toVector(env) != std::vector<jdouble>(source.begin(), source.end())) {
      return fail("create(span)/toVector");
    }

    // Partial region copies, both directions.
    const std::array<jdouble, 2> patch{9.5, 8.5};
    doubles->setRegion(env, 1, std::span<const jdouble>{patch});
    std::array<jdouble, 2> readBack{};
    doubles->getRegion(env, 1, std::span<jdouble>{readBack});
    if (readBack != patch) {
      return fail("setRegion/getRegion");
    }

    // Bounds violations surface as the pending Java exception, converted and cleared.
    try {
      std::array<jdouble, 2> out{};
      doubles->getRegion(env, 3, std::span<jdouble>{out});
      return fail("out-of-bounds getRegion did not throw");
    } catch (const JavaException&) {
    }

    // A mutable pin writes back on destruction.
    {
      auto pinned = ints->pin(env);
      for (jsize i = 0; i < pinned.size(); i++) {
        pinned[i] = i * 2;
      }
    }
    if (ints->toVector(env) != std::vector<jint>{0, 2, 4, 6, 8}) {
      return fail("pin write-back on destroy");
    }

    // commit() makes writes visible while the pin stays alive; abort() afterwards must leave the
    // committed value in place and the destructor must not release twice.
    {
      auto pinned = ints->pin(env);
      pinned[0] = 42;
      pinned.commit();
      std::array<jint, 1> first{};
      ints->getRegion(env, 0, std::span<jint>{first});
      if (first[0] != 42) {
        return fail("pin commit");
      }
      pinned.abort();
    }
    if (ints->toVector(env) != std::vector<jint>{42, 2, 4, 6, 8}) {
      return fail("pin abort after commit");
    }

    // A read-only pin observes the same elements; moving a pin keeps it usable exactly once.
    {
      auto pinned = doubles->pinReadOnly(env);
      auto moved = std::move(pinned);
      std::vector<jdouble> viaPin(moved.begin(), moved.end());
      if (viaPin != doubles->toVector(env)) {
        return fail("pinReadOnly/move");
      }
    }

    // Zero-length arrays work on every path.
    auto empty = JDoubleArray::create(env, std::span<const jdouble>{});
    if (empty->size(env) != 0 || !empty->toVector(env).empty()) {
      return fail("empty array");
    }
    {
      auto pinned = empty->pinReadOnly(env);
      if (pinned.size() != 0 || pinned.begin() != pinned.end()) {
        return fail("empty pin");
      }
    }

    // Object arrays: elements default to null, set/get round-trips a string, bounds throw.
    auto names = JArray<JString>::create(env, 2);
    if (names->size(env) != 2 || names->getElement(env, 0)) {
      return fail("object array create");
    }
    auto hello = Ref<JString>::adopt(env, toJString(env, "żółć"));
    names->setElement(env, 0, hello);
    auto got = names->getElement(env, 0);
    if (!got || toStdString(env, reinterpret_cast<jstring>(got.get())) != "żółć") {
      return fail("object array set/get");
    }
    try {
      names->setElement(env, 5, hello);
      return fail("out-of-bounds setElement did not throw");
    } catch (const JavaException&) {
    }

    // JObjectArray holds anything; a boxed Integer survives.
    auto objects = JObjectArray::create(env, 1);
    objects->setElement(env, 0, JInteger::valueOf(env, static_cast<jint>(7)));
    auto boxed = objects->getElement(env, 0);
    if (!boxed || JInteger::intValue(env, UnownedRef<JInteger>(boxed.get())) != 7) {
      return fail("JObjectArray");
    }

    // Nested arrays: int[][] composes from the same building blocks.
    auto nested = JArray<JArray<jint>>::create(env, 2);
    const std::array<jint, 3> inner{7, 8, 9};
    nested->setElement(env, 1, JIntArray::create(env, std::span<const jint>{inner}));
    auto innerBack = nested->getElement(env, 1);
    if (!innerBack || innerBack->toVector(env) != std::vector<jint>{7, 8, 9}) {
      return fail("nested array set/get");
    }
    if (nested->getElement(env, 0)) {
      return fail("nested array initial element is not null");
    }

    // Method tokens with array signatures resolve and marshal: an instance method returning an
    // array, and a static method taking a raw array argument (a Ref works in the same slot).
    auto text = Ref<JString>::adopt(env, toJString(env, "abc"));
    auto chars = JStringOps::toCharArray(env, text);
    if (chars->toVector(env) != std::vector<jchar>{'a', 'b', 'c'}) {
      return fail("toCharArray token");
    }
    auto copy = JArrays::copyOf(env, ints, static_cast<jint>(2));
    if (copy->toVector(env) != std::vector<jint>{42, 2}) {
      return fail("Arrays.copyOf token");
    }

    // A null ref refuses element access instead of crashing.
    try {
      Ref<JIntArray> null;
      (void)null->size(env);
      return fail("null array access did not throw");
    } catch (const std::runtime_error&) {
    }

    return "ok";
  }
} // namespace expo::modules::v2
