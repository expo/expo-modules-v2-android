#pragma once

#include <cstdint>
#include <memory>
#include <optional>
#include <string>
#include <string_view>
#include <vector>

#include <expo-modules-cpp/Defines.h>
#include <expo-modules-cpp/NativeObject.h>

namespace expo::modules::v2::cpp::detail {
  /** One call from JavaScript, as the host function received it. */
  struct CallFrame {
    js::Runtime& rt;
    const js::Value& thisValue;
    const js::Value* args;
    size_t count;
    /**
     * The module whose function this is, or null for a member of a shared object class, whose
     * receiver is its owner. A coroutine keeps it alive until it ends, because the callable lives
     * in it.
     */
    NativeObject* owner = nullptr;
  };

  struct FunctionSpec;
  struct ClassSpec;

  /** The instantiation of `Invoker` for one exported callable. */
  using InvokeFn = js::Value (*)(const FunctionSpec&, const CallFrame&);

  /**
   * An exported function: everything the library needs to call a C++ callable from JavaScript. The
   * conversion of the arguments, the call and the conversion of the result are all inside [invoke],
   * which is instantiated once per callable, so a call costs one indirect jump past the host
   * function and no allocation.
   */
  struct FunctionSpec {
    std::string name;
    InvokeFn invoke = nullptr;
    /** The callable, type-erased. [invoke] knows what it is. */
    std::shared_ptr<void> callable;
    /** The `length` of the JavaScript function: the parameters JavaScript passes. */
    uint32_t arity = 0;
    /**
     * For a member of a shared object class: the class its receiver has to be. The receiver is the
     * `this` the function was called on, and it is checked before anything else runs.
     */
    const ClassSpec* receiverClass = nullptr;
  };

  struct PropertySpec {
    std::string name;
    FunctionSpec getter;
    std::optional<FunctionSpec> setter;
  };

  /**
   * A property JavaScript reads and then keeps as a plain read-only value: Kotlin's `@Constant`.
   *
   * [lazy] says when the getter runs. A value given up front is read when the JavaScript object is
   * built; a getter runs at the first read - on a shared object, the first read of each instance -
   * and a failing getter is simply called again at the next read.
   */
  struct ConstantSpec {
    std::string name;
    FunctionSpec getter;
    bool lazy = false;
  };

  /**
   * The description of a shared object class: process-wide and built once, so every runtime builds
   * its prototype from the same table and an instance serves all of them.
   */
  struct ClassSpec {
    std::string name;
    /** The class this one extends, whose members the prototype inherits. */
    const ClassSpec* parent = nullptr;
    std::optional<FunctionSpec> constructor;
    std::vector<FunctionSpec> functions;
    std::vector<PropertySpec> properties;
    std::vector<ConstantSpec> constants;

    /** Whether an instance of this class is acceptable where [other] is declared. */
    [[nodiscard]] bool isA(const ClassSpec* other) const noexcept {
      for (const ClassSpec* spec = this; spec != nullptr; spec = spec->parent) {
        if (spec == other) {
          return true;
        }
      }
      return false;
    }
  };

  /** A class a module exports, under the name JavaScript constructs it by. */
  struct ClassBinding {
    std::string jsName;
    const ClassSpec* spec;
  };

  /** What a module exports. Built once, when the module joins a context. */
  struct ModuleSpec {
    std::vector<FunctionSpec> functions;
    std::vector<PropertySpec> properties;
    std::vector<ConstantSpec> constants;
    std::vector<ClassBinding> classes;
  };

  /**
   * The members every module object and shared object prototype carries for the event emitter.
   * They are installed non-configurable, so an export of the same name would fail halfway through
   * building the object. The same list is in `ModuleBuilder.RESERVED_EXPORT_NAMES` in Kotlin.
   */
  [[nodiscard]] bool isReservedExportName(std::string_view name) noexcept;

  /**
   * Remembers the names a module or class exports, and throws `std::invalid_argument` for one that
   * is reserved or taken. [owner] names the module or class in the message.
   */
  class ExportNames {
  public:
    explicit ExportNames(std::string owner, bool isClass) : owner_(std::move(owner)), isClass_(isClass) {
    }

    void claim(const std::string& name);

    void setOwner(std::string owner) { owner_ = std::move(owner); }

  private:
    std::string owner_;
    bool isClass_;
    std::vector<std::string> names_;
  };
} // namespace expo::modules::v2::cpp::detail
