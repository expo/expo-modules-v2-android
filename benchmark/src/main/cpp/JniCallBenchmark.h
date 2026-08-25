#pragma once

#include <jni.h>

namespace expo::modules::v2 {
  // Micro-benchmarks a Java method call (ArrayList.size() and ArrayList.get(int)) dispatched four
  // ways: raw JNI, our JavaClass token, our `ref->method()` accessor, and fbjni's JMethod. Returns
  // a ns/op report as a Java String. Must run on a JVM-attached thread.
  jstring runJniCallBench(JNIEnv* env);
} // namespace expo::modules::v2
