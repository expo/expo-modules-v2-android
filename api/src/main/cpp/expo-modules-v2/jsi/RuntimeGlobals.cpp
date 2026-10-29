#include <expo-modules-v2/jsi/RuntimeGlobals.h>

namespace expo::modules::v2 {
  RuntimeGlobals& RuntimeGlobals::threadLocal() {
    thread_local RuntimeGlobals globals;
    return globals;
  }

  RuntimeGlobals& RuntimeGlobals::get(facebook::jsi::Runtime& rt) {
    RuntimeGlobals& globals = threadLocal();
    globals.activate(rt);
    return globals;
  }

  void RuntimeGlobals::clearForRuntime(const facebook::jsi::Runtime& rt) noexcept {
    RuntimeGlobals& globals = threadLocal();
    if (globals.runtime_ == &rt) {
      globals.clear();
    }
  }

  void RuntimeGlobals::activate(facebook::jsi::Runtime& rt) {
    if (runtime_ == &rt) [[likely]] {
      return;
    }

    clear();
    runtime_ = &rt;
  }

  void RuntimeGlobals::clear() noexcept {
    objectDefineProperty_.reset();
    arrayBufferIsView_.reset();
    typedArrayToStringTag_.reset();
    errorConstructor_.reset();
    promiseResolve_.reset();
    promiseReject_.reset();
    promise_.reset();
    runtime_ = nullptr;
  }

  facebook::jsi::Object RuntimeGlobals::global(const char* name) const {
    return runtime_->global().getPropertyAsObject(*runtime_, name);
  }

  const facebook::jsi::Function& RuntimeGlobals::objectDefineProperty() {
    if (!objectDefineProperty_.has_value()) [[unlikely]] {
      objectDefineProperty_.emplace(global("Object")
        .getPropertyAsFunction(*runtime_, "defineProperty"));
    }

    return *objectDefineProperty_;
  }

  const facebook::jsi::Function& RuntimeGlobals::arrayBufferIsView() {
    if (!arrayBufferIsView_.has_value()) [[unlikely]] {
      arrayBufferIsView_.emplace(
        global("ArrayBuffer").getPropertyAsFunction(*runtime_, "isView")
      );
    }

    return *arrayBufferIsView_;
  }

  const facebook::jsi::Function& RuntimeGlobals::typedArrayToStringTag() {
    if (!typedArrayToStringTag_.has_value()) [[unlikely]] {
      facebook::jsi::Runtime& rt = *runtime_;
      const facebook::jsi::Object object = global("Object");

      // `%TypedArray%` has no global name; it is the prototype every typed array constructor shares.
      const facebook::jsi::Value typedArrayPrototype = object
        .getPropertyAsFunction(rt, "getPrototypeOf")
        .call(rt, global("Int8Array").getProperty(rt, "prototype"));
      const facebook::jsi::Value toStringTag = global("Symbol").getProperty(rt, "toStringTag");

      typedArrayToStringTag_.emplace(
        object
        .getPropertyAsFunction(rt, "getOwnPropertyDescriptor")
        .call(rt, typedArrayPrototype, toStringTag)
        .asObject(rt)
        .getPropertyAsFunction(rt, "get")
      );
    }

    return *typedArrayToStringTag_;
  }

  const facebook::jsi::Function& RuntimeGlobals::errorConstructor() {
    if (!errorConstructor_.has_value()) [[unlikely]] {
      errorConstructor_.emplace(
        runtime_->global().getPropertyAsFunction(*runtime_, "Error")
      );
    }

    return *errorConstructor_;
  }

  const facebook::jsi::Object& RuntimeGlobals::promise() {
    if (!promise_.has_value()) [[unlikely]] {
      promise_.emplace(global("Promise"));
    }

    return *promise_;
  }

  const facebook::jsi::Function& RuntimeGlobals::promiseResolve() {
    if (!promiseResolve_.has_value()) [[unlikely]] {
      promiseResolve_.emplace(
        promise().getPropertyAsFunction(*runtime_, "resolve")
      );
    }

    return *promiseResolve_;
  }

  const facebook::jsi::Function& RuntimeGlobals::promiseReject() {
    if (!promiseReject_.has_value()) [[unlikely]] {
      promiseReject_.emplace(
        promise().getPropertyAsFunction(*runtime_, "reject")
      );
    }

    return *promiseReject_;
  }
}
