#include <jni.h>

#include "ReactRuntime.h"

#include <expo-modules-v2/jni/JAsyncContext.h>
#include <expo-modules-v2/jni/JEventSupport.h>
#include <expo-modules-v2/jni/JModuleRegistry.h>
#include <expo-modules-v2/jni/JSharedObjectRegistry.h>
#include <expo-modules-v2/jsi/AttachedRuntime.h>
#include <expo-modules-v2/jsi/JavaScriptObject.h>
#include <expo-modules-v2/jsi/JavaScriptRuntime.h>
#include <expo-modules-v2/jsi/JavaScriptValue.h>
#include <kolibri/NativeObject.h>
#include <kolibri/binary/BinaryBuffer.h>
#include <kolibri/env.h>

// The Android build ships one .so, so this replaces :api's own OnLoad.cpp (excluded from the CMake
// glob) and registers the Android runtime's natives alongside the shared ones.
JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void*) {
  expo::kolibri::initVM(vm);
  JNIEnv* env = expo::kolibri::getEnv();
  expo::kolibri::JNativeObject::registerNatives(env);
  expo::kolibri::binary::BinaryBuffer::registerNatives(env);
  expo::modules::v2::jsi::JavaScriptValue::registerNatives(env);
  expo::modules::v2::jsi::JavaScriptObject::registerNatives(env);
  expo::modules::v2::jsi::JavaScriptRuntime::registerNatives(env);
  // Usable in an Android app without React Native, where a host such as hermes-tests-environment
  // creates the runtime; ReactRuntime below is the React Native path.
  expo::modules::v2::jsi::AttachedRuntime::registerNatives(env);
  expo::modules::v2::JAsyncContext::registerNatives(env);
  expo::modules::v2::JEventNatives::registerNatives(env);
  expo::modules::v2::JSharedObjectRegistry::registerNatives(env);
  expo::modules::v2::JModuleRegistry::registerNatives(env);
  expo::modules::v2::react::ReactRuntime::registerNatives(env);
  return JNI_VERSION_1_6;
}
