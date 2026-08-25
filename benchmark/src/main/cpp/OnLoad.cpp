#include <jni.h>

#include "JniCallBenchmark.h"
#include <kolibri/JavaClass.h>
#include <kolibri/env.h>
#include <kolibri/native_method.h>

namespace {
  /**
   * Token for the benchmark harness's Kotlin entry point. Registered here rather than in
   * JniCallBenchmark.cpp: that TU includes fbjni (one of the benchmarked dispatch mechanisms),
   * whose makeNativeMethod macro breaks kolibri's native_method.h.
   */
  struct JNativeBenchmarks : expo::kolibri::JavaClass<JNativeBenchmarks> {
    static constexpr std::string_view descriptor = "expo/modules/v2/benchmark/NativeBenchmarks";

    static void registerNatives(JNIEnv* env) {
      JavaClass::registerNatives(env)
        .method<&expo::modules::v2::runJniCallBench>("runJniCallBenchmark")
        .commit();
    }
  };
} // namespace

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void*) {
  // The shared kolibri state lives in libexpo-kolibri.dylib, whose own JNI_OnLoad already ran
  // (NativeBenchmarks loads it first via ExpoModulesV2.ensureLoaded); initVM is a bare pointer
  // assignment, so repeating it here is harmless.
  expo::kolibri::initVM(vm);
  JNIEnv* env = expo::kolibri::getEnv();
  JNativeBenchmarks::registerNatives(env);
  return JNI_VERSION_1_6;
}
