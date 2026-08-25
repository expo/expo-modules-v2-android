#include <jni.h>

#include <expo-modules-v2/jni/JAsyncContext.h>
#include <expo-modules-v2/jsi/AttachedRuntime.h>
#include <expo-modules-v2/jsi/JavaScriptRuntime.h>
#include <expo-modules-v2/jsi/JavaScriptObject.h>
#include <expo-modules-v2/jsi/JavaScriptValue.h>
#include <kolibri/NativeObject.h>
#include <kolibri/binary/BinaryBuffer.h>
#include <kolibri/env.h>

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void*) {
  expo::kolibri::initVM(vm);
  JNIEnv* env = expo::kolibri::getEnv();
  expo::kolibri::JNativeObject::registerNatives(env);
  expo::kolibri::binary::BinaryBuffer::registerNatives(env);
  expo::modules::v2::jsi::JavaScriptValue::registerNatives(env);
  expo::modules::v2::jsi::JavaScriptObject::registerNatives(env);
  expo::modules::v2::jsi::JavaScriptRuntime::registerNatives(env);
  expo::modules::v2::jsi::AttachedRuntime::registerNatives(env);
  expo::modules::v2::JAsyncContext::registerNatives(env);
  return JNI_VERSION_1_6;
}
