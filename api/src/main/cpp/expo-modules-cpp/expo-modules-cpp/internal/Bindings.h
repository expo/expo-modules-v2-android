#pragma once

#include <memory>
#include <string>
#include <type_traits>
#include <utility>

#include <expo-modules-cpp/Traits.h>

// Member pointers as callables.
//
// Every export reaches the library as a callable whose parameter types the compiler can read, and
// a pointer to a member is not one: it takes the object in a way `std::invoke` knows and a
// signature does not show. These functors are that pointer with its signature spelled out, so that
// `&Counter::increment` is exported by the same code path as a lambda.

namespace expo::modules::v2::cpp::detail {
  /**
   * `&C::method` as a callable that takes the object first - how a shared object class exports a
   * member: `(C&, Args...) -> R`, or `(const C&, Args...) -> R` for a const member.
   */
  template<typename M>
  struct UnboundMethod;

  template<typename C, typename R, bool NoExcept, typename... A>
  struct UnboundMethod<R (C::*)(A...) noexcept(NoExcept)> {
    R (C::*method)(A...) noexcept(NoExcept);

    R operator()(C& self, A... args) const { return (self.*method)(std::forward<A>(args)...); }
  };

  template<typename C, typename R, bool NoExcept, typename... A>
  struct UnboundMethod<R (C::*)(A...) const noexcept(NoExcept)> {
    R (C::*method)(A...) const noexcept(NoExcept);

    R operator()(const C& self, A... args) const { return (self.*method)(std::forward<A>(args)...); }
  };

  /** `&C::method` bound to an object - how a module exports a member of itself: `(Args...) -> R`. */
  template<typename M>
  struct BoundMethod;

  template<typename C, typename R, bool NoExcept, typename... A>
  struct BoundMethod<R (C::*)(A...) noexcept(NoExcept)> {
    C* self;
    R (C::*method)(A...) noexcept(NoExcept);

    R operator()(A... args) const { return (self->*method)(std::forward<A>(args)...); }
  };

  template<typename C, typename R, bool NoExcept, typename... A>
  struct BoundMethod<R (C::*)(A...) const noexcept(NoExcept)> {
    const C* self;
    R (C::*method)(A...) const noexcept(NoExcept);

    R operator()(A... args) const { return (self->*method)(std::forward<A>(args)...); }
  };

  /** A data member read through the object it belongs to. */
  template<typename C, typename V>
  struct MemberReader {
    V C::* member;

    const V& operator()(const C& self) const { return self.*member; }
  };

  /** A data member written through the object it belongs to. */
  template<typename C, typename V>
  struct MemberWriter {
    V C::* member;

    void operator()(C& self, V value) const { self.*member = std::move(value); }
  };

  template<typename C, typename V>
  struct BoundMemberReader {
    const C* self;
    V C::* member;

    const V& operator()() const { return self->*member; }
  };

  template<typename C, typename V>
  struct BoundMemberWriter {
    C* self;
    V C::* member;

    void operator()(V value) const { self->*member = std::move(value); }
  };

  /** A constructor: `std::make_shared<T>(args...)`. */
  template<typename T, typename... Args>
  struct MakeShared {
    std::shared_ptr<T> operator()(Args... args) const { return std::make_shared<T>(std::forward<Args>(args)...); }
  };

  /** A constant's value, held until it is converted. */
  template<typename T>
  struct ConstantValue {
    T value;

    const T& operator()() const { return value; }
  };

  /** What a constant given as [T] is stored as: a string literal becomes a `std::string`. */
  template<typename T>
  using ConstantStorage = std::conditional_t<
    std::is_convertible_v<std::decay_t<T>, const char*>,
    std::string,
    std::decay_t<T>
  >;

  /** A function, function pointer or functor with a single `operator()`: something to export. */
  template<typename T>
  concept Callable = std::is_function_v<std::remove_pointer_t<std::decay_t<T>>> ||
                     requires { &std::decay_t<T>::operator(); };

  template<typename T>
  concept ObjectPointer = std::is_pointer_v<std::decay_t<T>> &&
                          std::is_object_v<std::remove_pointer_t<std::decay_t<T>>>;

  template<typename T>
  concept MemberFunctionPointer = std::is_member_function_pointer_v<std::decay_t<T>>;

  template<typename T>
  concept MemberObjectPointer = std::is_member_object_pointer_v<std::decay_t<T>>;

  /** The class and the type of a data member pointer. */
  template<typename M>
  struct DataMember;

  template<typename C, typename V>
  struct DataMember<V C::*> {
    using Class = C;
    using Value = V;
  };
} // namespace expo::modules::v2::cpp::detail
