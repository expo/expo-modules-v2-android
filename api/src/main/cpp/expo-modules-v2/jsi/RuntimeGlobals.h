#pragma once

#include <optional>

#include <jsi/jsi.h>

namespace expo::modules::v2 {
  class RuntimeGlobals {
  public:
    [[nodiscard]] static RuntimeGlobals& get(facebook::jsi::Runtime& rt);

    static void clearForRuntime(const facebook::jsi::Runtime& rt) noexcept;

    /** `Object.defineProperty`. */
    [[nodiscard]] const facebook::jsi::Function& objectDefineProperty();

    /** `ArrayBuffer.isView`. */
    [[nodiscard]] const facebook::jsi::Function& arrayBufferIsView();

    /**
     * The `%TypedArray%.prototype[Symbol.toStringTag]` getter. Called with a typed array as `this`,
     * it returns the name of its kind, read from the internal `[[TypedArrayName]]` slot: right for
     * a subclass, and no property of the array can change it. For anything else, a `DataView`
     * included, it returns `undefined`.
     */
    [[nodiscard]] const facebook::jsi::Function& typedArrayToStringTag();

    /** `Error`. */
    [[nodiscard]] const facebook::jsi::Function& errorConstructor();

    /** `Promise`: the receiver `promiseResolve` and `promiseReject` need. */
    [[nodiscard]] const facebook::jsi::Object& promise();

    /** `Promise.resolve`. */
    [[nodiscard]] const facebook::jsi::Function& promiseResolve();

    /** `Promise.reject`. */
    [[nodiscard]] const facebook::jsi::Function& promiseReject();

  private:
    static RuntimeGlobals& threadLocal();

    void activate(facebook::jsi::Runtime& rt);

    void clear() noexcept;

    [[nodiscard]] facebook::jsi::Object global(const char* name) const;

    facebook::jsi::Runtime* runtime_ = nullptr;
    std::optional<facebook::jsi::Function> objectDefineProperty_;
    std::optional<facebook::jsi::Function> arrayBufferIsView_;
    std::optional<facebook::jsi::Function> typedArrayToStringTag_;
    std::optional<facebook::jsi::Function> errorConstructor_;
    std::optional<facebook::jsi::Object> promise_;
    std::optional<facebook::jsi::Function> promiseResolve_;
    std::optional<facebook::jsi::Function> promiseReject_;
  };
}
