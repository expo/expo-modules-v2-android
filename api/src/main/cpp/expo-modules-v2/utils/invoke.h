#pragma once

#include <stdexcept>
#include <string>
#include <type_traits>

#include <kolibri/defines.h>

namespace expo::modules::v2 {
  [[noreturn]] inline void throwUnhandled(
    const int code
  ) {
    throw std::invalid_argument(
      std::string("This visitor has no handler for code ") + std::to_string(code)
    );
  }

  template<typename V, auto A>
  concept Handles = requires(std::remove_reference_t<V>& v)
  {
    v.template operator()<A>();
  };

  template<typename V, typename Arg>
  concept HandlesArgument = requires(std::remove_reference_t<V>& v, const Arg& arg)
  {
    v(arg);
  };

  template<typename R, auto A, typename V>
  ALWAYS_INLINE R invokeFor(V& v) {
    if constexpr (Handles<V, A>) {
      return v.template operator()<A>();
    } else {
      throwUnhandled(static_cast<int>(A));
    }
  }

  template<typename R, auto A, typename V, typename Arg>
  ALWAYS_INLINE R invokeWith(V& v, const Arg& arg) {
    if constexpr (HandlesArgument<V, Arg>) {
      return v(arg);
    } else {
      throwUnhandled(static_cast<int>(A));
    }
  }

  template<typename E>
  [[noreturn]] void throwUnknown(const E code) {
    throwUnhandled(static_cast<int>(code));
  }
} // namespace expo::modules::v2
