#include <expo-modules-cpp/Any.h>

namespace expo::modules::v2::cpp::detail {
  Any anyFromJS(js::Runtime& rt, const js::Value& value, const int depth) {
    if (depth > kMaxAnyDepth) [[unlikely]] {
      throwConversion("the value is nested more than " + std::to_string(kMaxAnyDepth) + " levels deep - is it cyclic?");
    }

    if (value.isUndefined() || value.isNull()) {
      return Any(nullptr);
    }
    if (value.isBool()) {
      return Any(value.getBool());
    }
    if (value.isNumber()) {
      return Any(value.getNumber());
    }
    if (value.isString()) {
      return Any(value.getString(rt).utf8(rt));
    }
    if (!value.isObject()) {
      throwExpected(rt, "null, a boolean, a number, a string, an array or an object", value);
    }

    const js::Object object = value.getObject(rt);
    if (object.isArray(rt)) {
      const js::Array array = object.getArray(rt);
      const size_t size = array.size(rt);
      Any::Array elements;
      elements.reserve(size);
      for (size_t i = 0; i < size; i++) {
        elements.push_back(anyFromJS(rt, array.getValueAtIndex(rt, i), depth + 1));
      }
      return Any(std::move(elements));
    }
    if (object.isFunction(rt) || object.isArrayBuffer(rt)) {
      throwExpected(rt, "null, a boolean, a number, a string, an array or an object", value);
    }

    const js::Array keys = object.getPropertyNames(rt);
    const size_t size = keys.size(rt);
    Any::Object entries;
    entries.reserve(size);
    for (size_t i = 0; i < size; i++) {
      const js::String key = keys.getValueAtIndex(rt, i).getString(rt);
      entries.emplace_back(key.utf8(rt), anyFromJS(rt, object.getProperty(rt, key), depth + 1));
    }
    return Any(std::move(entries));
  }

  js::Value anyToJS(js::Runtime& rt, const Any& value, const int depth) {
    if (depth > kMaxAnyDepth) [[unlikely]] {
      throwConversion("the value is nested more than " + std::to_string(kMaxAnyDepth) + " levels deep");
    }

    return std::visit(
      [&](const auto& held) -> js::Value {
        using Held = std::decay_t<decltype(held)>;
        if constexpr (std::is_same_v<Held, std::nullptr_t>) {
          return js::Value::null();
        } else if constexpr (std::is_same_v<Held, bool>) {
          return js::Value(held);
        } else if constexpr (std::is_same_v<Held, double>) {
          return js::Value(held);
        } else if constexpr (std::is_same_v<Held, std::string>) {
          return js::Value(js::String::createFromUtf8(rt, held));
        } else if constexpr (std::is_same_v<Held, Any::Array>) {
          js::Array array(rt, held.size());
          for (size_t i = 0; i < held.size(); i++) {
            array.setValueAtIndex(rt, i, anyToJS(rt, held[i], depth + 1));
          }
          return js::Value(std::move(array));
        } else {
          js::Object object(rt);
          for (const auto& [key, item]: held) {
            object.setProperty(rt, js::String::createFromUtf8(rt, key), anyToJS(rt, item, depth + 1));
          }
          return js::Value(std::move(object));
        }
      },
      value.storage()
    );
  }
} // namespace expo::modules::v2::cpp::detail
