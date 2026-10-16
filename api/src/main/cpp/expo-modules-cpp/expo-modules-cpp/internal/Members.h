#pragma once

#include <memory>
#include <string>

#include <expo-modules-cpp/Defines.h>
#include <expo-modules-cpp/NativeObject.h>
#include <expo-modules-cpp/internal/Specs.h>

namespace expo::modules::v2::cpp::detail {
  class RuntimeData;

  /** What `Object.defineProperty` is told about a property. */
  struct PropertyFlags {
    bool writable = false;
    bool enumerable = false;
    bool configurable = false;
  };

  /**
   * A JavaScript function that calls [spec]. [owner] is kept alive for as long as the function is,
   * so a module's functions never call into a module that was freed; a member of a shared object
   * class needs none, because its spec lives for the life of the process.
   */
  js::Function makeHostFunction(
    js::Runtime& rt,
    const FunctionSpec& spec,
    std::shared_ptr<NativeObject> owner = nullptr
  );

  /** `Object.defineProperty(object, name, { value, ...flags })`. */
  void defineData(
    RuntimeData& data,
    const js::Object& object,
    const std::string& name,
    js::Value value,
    PropertyFlags flags
  );

  /** `Object.defineProperty(object, name, { get, set, enumerable, configurable })`. */
  void defineAccessor(
    RuntimeData& data,
    const js::Object& object,
    const std::string& name,
    js::Function getter,
    const js::Function* setter,
    PropertyFlags flags
  );

  /**
   * The getter of a lazy constant: it runs [spec] at the first read, then replaces itself on the
   * object it was read from with the plain read-only value, so later reads do not call back. A
   * getter that throws leaves the accessor in place and is called again at the next read.
   *
   * [owner] as in [makeHostFunction]. [hidden] keeps the value off `Object.keys`, as a shared
   * object's instance does.
   */
  js::Function makeConstantGetter(
    js::Runtime& rt,
    const ConstantSpec& spec,
    std::shared_ptr<NativeObject> owner,
    bool hidden
  );

  /** The property accessors and data properties of [properties] and [constants] on [object]. */
  void defineProperties(
    RuntimeData& data,
    const js::Object& object,
    const std::vector<PropertySpec>& properties,
    const std::vector<ConstantSpec>& constants,
    const std::shared_ptr<NativeObject>& owner,
    bool hiddenConstants
  );
} // namespace expo::modules::v2::cpp::detail
