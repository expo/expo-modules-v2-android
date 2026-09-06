#include <expo-modules-v2/jsi/JavaScriptRuntime.h>

#include <iostream>

#if defined(__ANDROID__)
#include <android/log.h>
#endif

#include <expo-jsi/LazyObject.h>
#include <expo-modules-v2/converter/RecordPropertyCache.h>
#include <expo-modules-v2/jsi/JavaScriptObject.h>
#include <expo-modules-v2/jsi/JavaScriptValue.h>
#include <expo-modules-v2/jsi/ModulesHostObject.h>
#include <kolibri/ScopedNativeObject.h>
#include <kolibri/string_utils.h>

namespace expo::modules::v2::jsi {
  using expo::jsi::LazyObject;

  namespace {
    /**
     * `ExpoModulesCore.nativeLog`'s sink. stdout is the process's console on a desktop JVM and
     * nowhere at all on Android, where logcat is the only place a line can land.
     */
    void logLine(const std::string& message) {
#if defined(__ANDROID__)
      __android_log_print(ANDROID_LOG_INFO, "ExpoModulesCore", "%s", message.c_str());
#else
      std::cout << "[ExpoModulesCore] " << message << std::endl;
#endif
    }
  } // namespace

  JavaScriptRuntime::JavaScriptRuntime(
    JNIEnv* env,
    jobject registry,
    jobject asyncContext,
    std::unique_ptr<facebook::jsi::Runtime> runtime,
    std::string_view engineName,
    std::string_view globalName
  ) : ownedRuntime_(std::move(runtime)), runtime_(ownedRuntime_.get()) {
    asyncState_.emplace(env, *runtime_, asyncContext);
    sharedObjects_.emplace(*runtime_);
    installExpoModulesHostObject(env, registry, engineName, globalName);
  }

  JavaScriptRuntime::JavaScriptRuntime(
    JNIEnv* env,
    jobject registry,
    jobject asyncContext,
    facebook::jsi::Runtime& runtime,
    std::string_view engineName,
    std::string_view globalName
  ) : ownedRuntime_(nullptr), runtime_(&runtime) {
    asyncState_.emplace(env, *runtime_, asyncContext);
    sharedObjects_.emplace(*runtime_);
    installExpoModulesHostObject(env, registry, engineName, globalName);
  }

  JavaScriptRuntime::~JavaScriptRuntime() {
    // First: it tells Kotlin the runtime is gone, so an in-flight settle stops before it can reach
    // a half-destroyed runtime, and it drops every pending resolve/reject while `runtime_` is still
    // alive to destroy them against.
    asyncState_.reset();

    // Also before the runtime goes: these hold `jsi` values belonging to this runtime, and this is
    // its own thread.
    sharedObjects_.reset();
    modules_.reset();

    RecordPropertyCache::clearForRuntime(*runtime_);
    kolibri::invalidateScope(runtime_);
  }

  jobject JavaScriptRuntime::evaluate(JNIEnv* env, jstring script, jstring sourceURL) const {
    const std::string source = kolibri::toStdString(env, script);
    const std::string url = sourceURL ? kolibri::toStdString(env, sourceURL) : "<eval>";

    auto result = runtime_->evaluateJavaScript(
      std::make_shared<facebook::jsi::StringBuffer>(std::move(source)),
      url
    );

    return JavaScriptValue::create(env, runtime_, std::move(result));
  }

  jobject JavaScriptRuntime::createObject(JNIEnv* env) const {
    return JavaScriptObject::create(env, runtime_, facebook::jsi::Object(*runtime_));
  }

  jobject JavaScriptRuntime::getGlobal(JNIEnv* env) const {
    return JavaScriptObject::create(env, runtime_, runtime_->global());
  }

  facebook::jsi::Runtime& JavaScriptRuntime::runtime() const {
    return *runtime_;
  }

  const std::shared_ptr<ModulesHostObject>& JavaScriptRuntime::modules() const {
    return modules_;
  }

  jboolean JavaScriptRuntime::drainMicrotasks() const {
    return runtime_->drainMicrotasks();
  }

  static facebook::jsi::Object makeCoreObject(facebook::jsi::Runtime& rt, std::string_view engineName) {
    auto core = facebook::jsi::Object(rt);
    core.setProperty(rt, "apiVersion", facebook::jsi::String::createFromUtf8(rt, "2.0.0-alpha"));
    core.setProperty(
      rt,
      "engine",
      facebook::jsi::String::createFromUtf8(rt, std::string(engineName))
    );

    auto nativeLog = facebook::jsi::Function::createFromHostFunction(
      rt,
      facebook::jsi::PropNameID::forAscii(rt, "nativeLog"),
      1,
      [](
      facebook::jsi::Runtime& rt,
      const facebook::jsi::Value&,
      const facebook::jsi::Value* args,
      size_t count
    ) -> facebook::jsi::Value {
        if (count >= 1 && args[0].isString()) {
          logLine(args[0].getString(rt).utf8(rt));
        }
        return facebook::jsi::Value::undefined();
      }
    );
    core.setProperty(rt, "nativeLog", std::move(nativeLog));
    return core;
  }

  void JavaScriptRuntime::installExpoModulesHostObject(
    JNIEnv* env,
    jobject registry,
    std::string_view engineName,
    std::string_view globalName
  ) {
    facebook::jsi::Runtime& rt = *runtime_;

    const std::string nameSpace(globalName);

    modules_ = std::make_shared<ModulesHostObject>(env, registry);

    auto expoNamespace = facebook::jsi::Object(rt);
    expoNamespace.setProperty(
      rt,
      "modules",
      facebook::jsi::Object::createFromHostObject(rt, modules_)
    );
    rt.global().setProperty(rt, nameSpace.c_str(), std::move(expoNamespace));

    rt.global().setProperty(
      rt,
      "ExpoModulesCore",
      facebook::jsi::Object::createFromHostObject(
        rt,
        std::make_shared<LazyObject>(
          [engineName = std::string(engineName)](facebook::jsi::Runtime& runtime) {
            return std::make_shared<facebook::jsi::Object>(makeCoreObject(runtime, engineName));
          }
        )
      )
    );
  }

  void JavaScriptRuntime::registerNatives(JNIEnv* env) {
    kolibri::registerNative<JavaScriptRuntime>(env)
      .method<
        &JavaScriptRuntime::evaluate,
        kolibri::Ref<JavaScriptValue>(kolibri::NativePointer, jstring, jstring)
      >("evaluate")
      .method<
        &JavaScriptRuntime::getGlobal,
        kolibri::Ref<JavaScriptObject>(kolibri::NativePointer)
      >("global")
      .method<
        &JavaScriptRuntime::createObject,
        kolibri::Ref<JavaScriptObject>(kolibri::NativePointer)
      >("createObject")
      .method<&JavaScriptRuntime::drainMicrotasks>("drainMicrotasks")
      .commit();
  }
} // namespace expo::modules::v2::jsi
