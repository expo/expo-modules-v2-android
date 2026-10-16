#pragma once

#include <cstddef>
#include <cstdint>
#include <cstring>
#include <span>
#include <stdexcept>
#include <type_traits>
#include <utility>

#include <expo-modules-cpp/Converter.h>
#include <expo-modules-cpp/Defines.h>

namespace expo::modules::v2::cpp {
  /**
   * A typed array - a `Uint8Array`, a `Float32Array`, ... - or a `DataView` that JavaScript passed
   * in: Kotlin's `TypedArray`. Its bytes are the ones JavaScript sees, so writing to it changes the
   * JavaScript array in place, without a copy.
   *
   * ```cpp
   * def.function("fill", [](cpp::TypedArray array, int value) {
   *   std::memset(array.data(), value, array.byteLength());
   * });
   * ```
   *
   * The bytes belong to JavaScript, which may move or free them once the call returns, and are
   * only safe to touch on the JS thread. Use a typed array only in a synchronous export, and do not
   * keep it - or a pointer into it - after the call returns.
   */
  class TypedArray {
  public:
    TypedArray(js::Object object, uint8_t* const data, const size_t byteLength)
      : object_(std::move(object)), data_(data), byteLength_(byteLength) {
    }

    /** The JavaScript object itself. Returning the typed array returns this object. */
    [[nodiscard]] const js::Object& jsObject() const noexcept { return object_; }

    /** The first byte the array covers - for a view, not the first byte of its buffer. */
    [[nodiscard]] uint8_t* data() const noexcept { return data_; }

    /** The number of bytes the array covers. */
    [[nodiscard]] size_t byteLength() const noexcept { return byteLength_; }

    [[nodiscard]] std::span<uint8_t> bytes() const noexcept { return {data_, byteLength_}; }

    /**
     * The bytes as an array of [T]. Throws when they do not hold a whole number of [T], or when the
     * view does not start where a [T] may: a `Uint8Array` at an odd offset is not a `Float32Array`.
     */
    template<typename T>
    [[nodiscard]] std::span<T> as() const {
      static_assert(std::is_trivially_copyable_v<T>, "A typed array holds plain numbers");
      if (byteLength_ % sizeof(T) != 0 || reinterpret_cast<std::uintptr_t>(data_) % alignof(T) != 0) {
        throw std::invalid_argument("This typed array does not hold a whole, aligned number of elements of that type");
      }
      return {reinterpret_cast<T*>(data_), byteLength_ / sizeof(T)};
    }

    /** Copies [size] bytes, starting at [position] in this array, to [buffer]. */
    void read(uint8_t* const buffer, const size_t position, const size_t size) const {
      check(position, size);
      std::memcpy(buffer, data_ + position, size);
    }

    /** Copies [size] bytes from [buffer] into this array, starting at [position]. */
    void write(const uint8_t* const buffer, const size_t position, const size_t size) const {
      check(position, size);
      std::memcpy(data_ + position, buffer, size);
    }

  private:
    void check(const size_t position, const size_t size) const {
      if (position > byteLength_ || size > byteLength_ - position) {
        throw std::out_of_range("The range lies outside the typed array");
      }
    }

    js::Object object_;
    uint8_t* data_;
    size_t byteLength_;
  };

  /** A parameter takes a typed array or a DataView, and not a bare ArrayBuffer: it needs a view. */
  template<>
  struct Converter<TypedArray> {
    static TypedArray fromJS(js::Runtime& rt, const js::Value& value);

    static js::Value toJS(js::Runtime& rt, const TypedArray& value) { return js::Value(rt, value.jsObject()); }
  };
} // namespace expo::modules::v2::cpp
