#include "JniCallBenchmark.h"

#include <algorithm>
#include <chrono>
#include <cmath>
#include <fbjni/fbjni.h>
#include <iomanip>
#include <jni.h>
#include <mutex>
#include <sstream>
#include <vector>

#include <kolibri/box.h>
#include <kolibri/class.h>
#include <kolibri/env.h>

namespace expo::modules::v2 {
  namespace {
    using Clock = std::chrono::steady_clock;

    constexpr int kWarmupIters = 1'000'000;
    constexpr int kMeasureIters = 2'000'000;
    constexpr int kRounds = 10;
    constexpr int kListSize = 16;

    // Read barrier: forces the compiler to treat `value` as observed (and memory as clobbered)
    // without emitting a store, so measured calls can't be optimized away yet nothing extra is
    // timed. Cheaper and more precise than routing every result through a `volatile` sink.
    template<typename T>
    inline void doNotOptimize(const T& value) {
      asm volatile("" : : "r,m"(value) : "memory");
    }

    struct Sample {
      double minNs;
      double medianNs;
      double stddevNs;
    };

    // Runs `body(i)` for kMeasureIters in each of kRounds rounds and returns min/median/stddev of
    // the per-round ns/op. `i` is the iteration counter so bodies can vary their input and defeat
    // any loop-invariant assumptions. The min filters scheduler/GC noise; the stddev shows how
    // trustworthy a small median difference is (deltas within ~1 stddev of each other are noise,
    // not signal).
    template<typename Body>
    Sample measure(Body&& body) {
      for (int i = 0; i < kWarmupIters; i++) {
        body(i);
      }

      std::vector<double> perOp;
      perOp.reserve(kRounds);
      for (int round = 0; round < kRounds; round++) {
        const auto start = Clock::now();
        for (int i = 0; i < kMeasureIters; i++) {
          body(i);
        }
        const auto elapsed = Clock::now() - start;
        const double ns = std::chrono::duration<double, std::nano>(elapsed).count();
        perOp.push_back(ns / kMeasureIters);
      }
      std::sort(perOp.begin(), perOp.end());

      double mean = 0.0;
      for (double v: perOp) {
        mean += v;
      }
      mean /= kRounds;
      double variance = 0.0;
      for (double v: perOp) {
        variance += (v - mean) * (v - mean);
      }
      variance /= kRounds;

      return Sample{perOp.front(), perOp[perOp.size() / 2], std::sqrt(variance)};
    }

    void ensureFbjniInitialized(JNIEnv* env) {
      static std::once_flag once;
      std::call_once(once, [env] {
        JavaVM* vm = nullptr;
        env->GetJavaVM(&vm);
        facebook::jni::initialize(vm, [] {
        });
      });
    }

    void
    reportRow(std::ostringstream& out, const char* name, const Sample& s, double baselineMedian) {
      out << "  " << std::left << std::setw(20) << name << std::right << std::fixed
          << std::setprecision(2) << "min " << std::setw(7) << s.minNs << "   median "
          << std::setw(7) << s.medianNs << " ±" << std::setw(5) << s.stddevNs << " ns/op   "
          << std::setw(5) << (s.medianNs / baselineMedian) << "x\n";
    }
  } // namespace

  jstring runJniCallBench(JNIEnv* env) {
    ensureFbjniInitialized(env);

    // A java.util.ArrayList<String>; every approach calls size()/get(i) on this same object so we
    // compare pure dispatch, not object state. get(i) rotates over the list to keep the argument
    // from being loop-invariant.
    auto list = kolibri::JArrayList::constructor(env, static_cast<jint>(kListSize));
    for (int i = 0; i < kListSize; i++) {
      list->add(env, std::string("item"));
    }
    jobject listObj = list.get();

    // --- raw JNI baseline: env captured once, method ids cached once ---
    jclass arrayListClass = env->GetObjectClass(listObj);
    const jmethodID sizeMid = env->GetMethodID(arrayListClass, "size", "()I");
    const jmethodID getMid = env->GetMethodID(arrayListClass, "get", "(I)Ljava/lang/Object;");

    // --- fbjni: class + method resolved once (as one would cache them in real code) ---
    using namespace facebook::jni;
    static const alias_ref<JClass> fbClass = findClassStatic("java/util/ArrayList");
    static const auto fbSize = fbClass->getMethod<jint()>("size");
    static const auto fbGet = fbClass->getMethod<JObject(jint)>("get");
    const alias_ref<JObject> fbList = wrap_alias(listObj);

    // --- HashMap + a boxed value, for the string-argument case: put("key", v) marshals a
    // std::string key into a temporary jstring. The key is held constant so the map never grows
    // (varying it would measure map growth/rehashing, not put dispatch); the std::string is still
    // rebuilt each iteration and passed into an opaque call, so it can't be hoisted. ---
    auto map = kolibri::JHashMap::constructor(env);
    auto boxed = kolibri::JInteger::valueOf(env, static_cast<jint>(7));
    jobject mapObj = map.get();
    jobject valObj = boxed.get();
    jclass hashMapClass = env->GetObjectClass(mapObj);
    const jmethodID putMid = env->GetMethodID(
      hashMapClass,
      "put",
      "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;"
    );
    static const alias_ref<JClass> fbMapClass = findClassStatic("java/util/HashMap");
    static const auto fbPut =
        fbMapClass->getMethod<JObject(alias_ref<JObject>, alias_ref<JObject>)>("put");
    const alias_ref<JObject> fbMap = wrap_alias(mapObj);
    const alias_ref<JObject> fbVal = wrap_alias(valObj);

    // --- static int field read (java.lang.Integer.MAX_VALUE). Field access never runs Java code,
    // so it cannot throw; our token (after dropping the field exception check) and fbjni's
    // `noexcept` getStaticFieldValue both skip it, and the raw baseline does too. ---
    static constexpr kolibri::StaticField<kolibri::JInteger, "MAX_VALUE", jint> maxValueField{};
    jclass integerClass = env->FindClass("java/lang/Integer");
    const jfieldID maxValueFid = env->GetStaticFieldID(integerClass, "MAX_VALUE", "I");
    static const alias_ref<JClass> fbIntegerClass = findClassStatic("java/lang/Integer");
    static const auto fbMaxField = fbIntegerClass->getStaticField<jint>("MAX_VALUE");

    // ---------- size(): int return, no args ----------
    const Sample rawSize =
        measure([&](int) { doNotOptimize(env->CallIntMethod(listObj, sizeMid)); });
    const Sample tokenSize = measure([&](int) { doNotOptimize(kolibri::JArrayList::size(env, list)); });
    const Sample arrowSize = measure([&](int) { doNotOptimize(list->size(env)); });
    const Sample fbjniSize = measure([&](int) { doNotOptimize(fbSize(fbList)); });

    // ---------- get(i): object return + one arg (each result is a local ref that is freed)
    // ---------- get() can throw (IndexOutOfBounds), so our token and fbjni both check for a
    // pending exception after the call; the raw baseline does the same so the comparison is
    // apples-to-apples. (size() above is `noexcept` in our token and skips the check, so its raw
    // baseline skips it too.)
    const Sample rawGet = measure([&](int i) {
      jobject e = env->CallObjectMethod(listObj, getMid, i & (kListSize - 1));
      if (env->ExceptionCheck()) [[unlikely]] {
        env->ExceptionClear();
      }
      doNotOptimize(e);
      env->DeleteLocalRef(e);
    });
    const Sample tokenGet = measure([&](int i) {
      kolibri::Ref<> e = kolibri::JArrayList::get(env, list, i & (kListSize - 1));
      doNotOptimize(e.get());
    });
    const Sample arrowGet = measure([&](int i) {
      kolibri::Ref<> e = list->get(env, i & (kListSize - 1));
      doNotOptimize(e.get());
    });
    const Sample fbjniGet = measure([&](int i) {
      local_ref<JObject> e = fbGet(fbList, i & (kListSize - 1));
      doNotOptimize(e.get());
    });

    // ---------- put("key", v): String arg (temp jstring, retained path) + object return ----------
    const Sample rawPut = measure([&](int) {
      jstring k = env->NewStringUTF("key");
      jobject prev = env->CallObjectMethod(mapObj, putMid, k, valObj);
      if (env->ExceptionCheck()) [[unlikely]] {
        env->ExceptionClear();
      }
      doNotOptimize(prev);
      if (prev != nullptr) {
        env->DeleteLocalRef(prev);
      }
      env->DeleteLocalRef(k);
    });
    const Sample tokenPut = measure([&](int) {
      kolibri::Ref<> prev = kolibri::JHashMap::put(env, map, std::string("key"), boxed);
      doNotOptimize(prev.get());
    });
    const Sample arrowPut = measure([&](int) {
      kolibri::Ref<> prev = map->put(env, std::string("key"), boxed);
      doNotOptimize(prev.get());
    });
    const Sample fbjniPut = measure([&](int) {
      local_ref<JString> k = make_jstring("key");
      local_ref<JObject> prev = fbPut(fbMap, wrap_alias(static_cast<jobject>(k.get())), fbVal);
      doNotOptimize(prev.get());
    });

    // ---------- Integer.MAX_VALUE: static int field read (no exception check on any side)
    // ----------
    const Sample rawField =
        measure([&](int) { doNotOptimize(env->GetStaticIntField(integerClass, maxValueFid)); });
    const Sample tokenField = measure([&](int) { doNotOptimize(maxValueField(env)); });
    const Sample fbjniField =
        measure([&](int) { doNotOptimize(fbIntegerClass->getStaticFieldValue(fbMaxField)); });

    // For context: the cost our tokens pay on every call to re-acquire the JNIEnv (fbjni caches it
    // in a thread-local; the raw baseline captured it once).
    const Sample getEnvOnly = measure([&](int) { doNotOptimize(kolibri::getEnv()); });

    std::ostringstream out;
    out << "\n=== JNI method-call dispatch (lower is better; xN vs raw JNI) ===\n"
        << "  " << kMeasureIters << " iters x " << kRounds << " rounds\n\n"
        << "ArrayList.size()  [jint, 0 args]\n";
    reportRow(out, "raw JNI", rawSize, rawSize.medianNs);
    reportRow(out, "JavaClass token", tokenSize, rawSize.medianNs);
    reportRow(out, "ref->size()", arrowSize, rawSize.medianNs);
    reportRow(out, "fbjni JMethod", fbjniSize, rawSize.medianNs);
    out << "\nArrayList.get(i)  [Object, 1 arg, local ref freed]\n";
    reportRow(out, "raw JNI", rawGet, rawGet.medianNs);
    reportRow(out, "JavaClass token", tokenGet, rawGet.medianNs);
    reportRow(out, "ref->get(i)", arrowGet, rawGet.medianNs);
    reportRow(out, "fbjni JMethod", fbjniGet, rawGet.medianNs);
    out << "\nHashMap.put(\"key\", v)  [std::string arg -> temp jstring, object return]\n";
    reportRow(out, "raw JNI", rawPut, rawPut.medianNs);
    reportRow(out, "JavaClass token", tokenPut, rawPut.medianNs);
    reportRow(out, "ref->put(k,v)", arrowPut, rawPut.medianNs);
    reportRow(out, "fbjni JMethod", fbjniPut, rawPut.medianNs);
    out << "\nInteger.MAX_VALUE  [static int field read, never throws]\n";
    reportRow(out, "raw JNI", rawField, rawField.medianNs);
    reportRow(out, "JavaClass token", tokenField, rawField.medianNs);
    reportRow(out, "fbjni field", fbjniField, rawField.medianNs);
    out << "\ncontext\n";
    reportRow(out, "getEnv() only", getEnvOnly, rawSize.medianNs);

    return env->NewStringUTF(out.str().c_str());
  }
} // namespace expo::modules::v2
