#include <expo-modules-cpp/internal/Invoke.h>

#include <string>

#include <expo-modules-cpp/SharedObject.h>
#include <expo-modules-cpp/internal/Log.h>
#include <expo-modules-cpp/internal/ObjectNode.h>

namespace expo::modules::v2::cpp::detail {
  js::Value callFunction(
    const FunctionSpec& spec,
    js::Runtime& rt,
    const js::Value& thisValue,
    const js::Value* const args,
    const size_t count,
    NativeObject* const owner
  ) {
    try {
      return spec.invoke(
        spec,
        CallFrame{.rt = rt, .thisValue = thisValue, .args = args, .count = count, .owner = owner}
      );
    } catch (...) {
      rethrowAsJSError(rt);
    }
  }

  void throwArity(const FunctionSpec& spec, const size_t received, const size_t declared) {
    throw Exception(
      spec.name + " expects " + std::to_string(declared) + " argument(s), but received " + std::to_string(received)
    );
  }

  void rethrowConversion(
    const FunctionSpec& spec,
    const Phase phase,
    const size_t argument,
    const ConversionError& error
  ) {
    switch (phase) {
      case Phase::Decoding:
        throw Exception(
          "Invalid argument " + std::to_string(argument + 1) + " of '" + spec.name + "': " + error.what()
        );
      case Phase::Encoding:
        throw Exception("Cannot convert the result of '" + spec.name + "': " + error.what());
      case Phase::Running:
        // The native code's own conversion failing is its error to describe.
        throw;
    }
    throw;
  }

  NativeObject& receiverOf(const CallFrame& frame, const FunctionSpec& spec) {
    js::Runtime& rt = frame.rt;
    const ClassSpec* const expected = spec.receiverClass;

    if (frame.thisValue.isObject()) {
      const js::Object self = frame.thisValue.getObject(rt);
      if (ObjectNode* const node = ObjectNode::borrow(rt, self); node != nullptr && !node->isModule()) {
        auto& object = static_cast<SharedObject&>(node->object());
        if (object.isReleased()) [[unlikely]] {
          throw Exception("Cannot access '" + spec.name + "' on a released " + node->spec()->name);
        }
        if (node->spec()->isA(expected)) [[likely]] {
          return object;
        }
        throw Exception(
          "'" + spec.name + "' called on a " + node->spec()->name + ", which is not a " + expected->name
        );
      }
    }

    throw Exception(
      "'" + spec.name + "' called on something that is not a " + (expected != nullptr ? expected->name : "shared object")
    );
  }

  void logCurrentException(const char* const what) {
    try {
      throw;
    } catch (const std::exception& error) {
      logError(std::string(what) + ": " + error.what());
    } catch (...) {
      logError(what);
    }
  }

  const js::Value& undefinedValue() {
    static const js::Value kUndefined;
    return kUndefined;
  }
} // namespace expo::modules::v2::cpp::detail
