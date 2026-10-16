#pragma once

#include <cstddef>
#include <string_view>
#include <tuple>
#include <type_traits>

#include <expo-modules-cpp/Defines.h>

namespace expo::modules::v2::cpp::detail {
  /**
   * The return type and parameters of a callable: a function, a function pointer, a lambda or any
   * functor with one non-template `operator()`, and a member function pointer.
   *
   * A generic lambda (`[](auto x)`) has no single signature, so it cannot be exported: JavaScript
   * calls it with values the library has to convert, and it needs the types to convert to.
   */
  template<typename Signature>
  struct SignatureTraits;

  template<typename R, typename... A>
  struct SignatureTraits<R(A...)> {
    using Return = R;
    using Params = std::tuple<A...>;
    static constexpr size_t kArity = sizeof...(A);
  };

  template<typename R, typename... A>
  struct SignatureTraits<R(A...) noexcept> : SignatureTraits<R(A...)> {
  };

  template<typename T>
  struct CallableTraits : CallableTraits<decltype(&T::operator())> {
  };

  template<typename R, typename... A>
  struct CallableTraits<R(*)(A...)> : SignatureTraits<R(A...)> {
  };

  template<typename R, typename... A>
  struct CallableTraits<R(*)(A...) noexcept> : SignatureTraits<R(A...)> {
  };

  template<typename R, typename... A>
  struct CallableTraits<R(A...)> : SignatureTraits<R(A...)> {
  };

  // `&T::operator()` of a lambda or functor: the object parameter is not one of the parameters.
  template<typename C, typename R, typename... A>
  struct CallableTraits<R(C::*)(A...)> : SignatureTraits<R(A...)> {
  };

  template<typename C, typename R, typename... A>
  struct CallableTraits<R(C::*)(A...) const> : SignatureTraits<R(A...)> {
  };

  template<typename C, typename R, typename... A>
  struct CallableTraits<R(C::*)(A...) noexcept> : SignatureTraits<R(A...)> {
  };

  template<typename C, typename R, typename... A>
  struct CallableTraits<R(C::*)(A...) const noexcept> : SignatureTraits<R(A...)> {
  };

  /** The class a member function pointer belongs to. */
  template<typename M>
  struct MemberClass;

  template<typename C, typename R, typename... A>
  struct MemberClass<R(C::*)(A...)> {
    using Class = C;
  };

  template<typename C, typename R, typename... A>
  struct MemberClass<R(C::*)(A...) const> {
    using Class = C;
  };

  template<typename C, typename R, typename... A>
  struct MemberClass<R(C::*)(A...) noexcept> {
    using Class = C;
  };

  template<typename C, typename R, typename... A>
  struct MemberClass<R(C::*)(A...) const noexcept> {
    using Class = C;
  };

  /**
   * A member function pointer as a callable that takes the object first: `&C::method` becomes
   * `(C&, Args...) -> R`. Exports go through one path - a callable with an optional receiver - and
   * this is what lets a method join it.
   */
  template<typename M>
  struct MethodTraits;

  template<typename C, typename R, typename... A>
  struct MethodTraits<R(C::*)(A...)> {
    using Signature = R(C&, A...);
  };

  template<typename C, typename R, typename... A>
  struct MethodTraits<R(C::*)(A...) const> {
    using Signature = R(const C&, A...);
  };

  template<typename C, typename R, typename... A>
  struct MethodTraits<R(C::*)(A...) noexcept> : MethodTraits<R(C::*)(A...)> {
  };

  template<typename C, typename R, typename... A>
  struct MethodTraits<R(C::*)(A...) const noexcept> : MethodTraits<R(C::*)(A...) const> {
  };

  template<typename T, template<typename...> class Template>
  inline constexpr bool kIsSpecialization = false;

  template<template<typename...> class Template, typename... Args>
  inline constexpr bool kIsSpecialization<Template<Args...>, Template> = true;

  template<typename T>
  using Plain = std::remove_cvref_t<T>;

  /** The text of [T] as the compiler prints it, with the namespaces and the template arguments removed. */
  template<typename T>
  constexpr std::string_view typeName() {
#if defined(__clang__)
    constexpr std::string_view text = __PRETTY_FUNCTION__;
    constexpr std::string_view prefix = "T = ";
    constexpr std::string_view suffix = "]";
#elif defined(__GNUC__)
    constexpr std::string_view text = __PRETTY_FUNCTION__;
    constexpr std::string_view prefix = "T = ";
    constexpr std::string_view suffix = ";";
#else
    constexpr std::string_view text = "";
    constexpr std::string_view prefix = "";
    constexpr std::string_view suffix = "";
#endif
    if constexpr (text.empty()) {
      return "";
    } else {
      constexpr size_t start = text.find(prefix) + prefix.size();
      constexpr size_t end = text.find(suffix, start);
      constexpr std::string_view full = text.substr(start, end - start);

      // The last `::` before the first `<`: `ns::Outer<ns::Inner>` is `Outer`, `ns::Outer` is `Outer`.
      constexpr size_t angle = full.find('<');
      constexpr std::string_view head = full.substr(0, angle);
      constexpr size_t scope = head.rfind("::");
      constexpr std::string_view name = scope == std::string_view::npos ? head : head.substr(scope + 2);
      // The standard library's own spelling of the one type everybody calls `string`.
      return name == "basic_string" ? std::string_view("string") : name;
    }
  }
} // namespace expo::modules::v2::cpp::detail
